package xyz.yychainsaw.portfolio.media.staging;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Isolated
class LocalStagingVolumeBindingMigrationTest extends PostgresIntegrationTestBase {
    private static final String VOLUME_A = "a".repeat(64);
    private static final String VOLUME_B = "b".repeat(64);

    @Autowired JdbcClient jdbc;
    @Autowired LocalStorageService storage;

    private String actualVolumeId;

    @BeforeEach
    void restoreKnownStartingBinding() {
        actualVolumeId = storage.volumeId();
        restoreActualBinding();
    }

    @AfterEach
    void restoreActualBinding() {
        if (actualVolumeId != null) {
            assertThat(migratorJdbc().sql("""
                            update portfolio.local_staging_policy
                            set volume_id=:volumeId
                            where singleton_key=1
                            """)
                    .param("volumeId", actualVolumeId)
                    .update()).isOne();
        }
    }

    @Test
    void migrationAddsNullableCanonicalVolumeIdAndExactFunctionSecurity() {
        ColumnShape column = migratorJdbc().sql("""
                        select data_type, character_maximum_length, is_nullable
                        from information_schema.columns
                        where table_schema='portfolio'
                          and table_name='local_staging_policy'
                          and column_name='volume_id'
                        """)
                .query((resultSet, rowNumber) -> new ColumnShape(
                        resultSet.getString("data_type"),
                        resultSet.getInt("character_maximum_length"),
                        resultSet.getString("is_nullable")))
                .single();
        FunctionShape function = migratorJdbc().sql("""
                        select procedure.prosecdef,
                               pg_catalog.array_to_string(procedure.proconfig, ',')
                                   as configuration
                        from pg_catalog.pg_proc procedure
                        where procedure.oid=
                            'portfolio.claim_local_staging_volume(text)'::regprocedure
                        """)
                .query((resultSet, rowNumber) -> new FunctionShape(
                        resultSet.getBoolean("prosecdef"),
                        resultSet.getString("configuration")))
                .single();
        Boolean publicCanExecute = migratorJdbc().sql("""
                        select has_function_privilege(
                            'public',
                            'portfolio.claim_local_staging_volume(text)',
                            'EXECUTE'
                        )
                        """)
                .query(Boolean.class)
                .single();
        Boolean capabilityCanExecute = migratorJdbc().sql("""
                        select has_function_privilege(
                            'portfolio_runtime_access',
                            'portfolio.claim_local_staging_volume(text)',
                            'EXECUTE'
                        )
                        """)
                .query(Boolean.class)
                .single();
        List<String> executeGrantees = migratorJdbc().sql("""
                        select case
                                   when role.rolname is null then 'PUBLIC'
                                   else role.rolname::text
                               end
                        from pg_catalog.pg_proc procedure
                        cross join lateral pg_catalog.aclexplode(
                            case
                                when procedure.proacl is null then
                                    pg_catalog.acldefault('f', procedure.proowner)
                                else procedure.proacl
                            end
                        ) privilege
                        left join pg_catalog.pg_roles role
                          on role.oid=privilege.grantee
                        where procedure.oid=
                            'portfolio.claim_local_staging_volume(text)'::regprocedure
                          and privilege.privilege_type='EXECUTE'
                        order by 1
                        """)
                .query(String.class)
                .list();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(column)
                    .isEqualTo(new ColumnShape("character varying", 64, "YES"));
            softly.assertThat(function.securityDefiner()).isTrue();
            softly.assertThat(function.configuration())
                    .isEqualTo("search_path=pg_catalog, pg_temp");
            softly.assertThat(publicCanExecute).isFalse();
            softly.assertThat(capabilityCanExecute).isTrue();
            softly.assertThat(executeGrantees)
                    .containsExactly("portfolio_runtime_access", "test_migrator")
                    .doesNotContain("PUBLIC", "test_runtime");
        });
    }

    @Test
    void invalidClaimsUseInvalidParameterSqlStateAndNeverChangeTheBinding() {
        List<String> invalidCalls = List.of(
                "select portfolio.claim_local_staging_volume(null::text)",
                "select portfolio.claim_local_staging_volume('a')",
                "select portfolio.claim_local_staging_volume('A" + "a".repeat(63) + "')",
                "select portfolio.claim_local_staging_volume('g" + "a".repeat(63) + "')",
                "select portfolio.claim_local_staging_volume('" + "a".repeat(65) + "')");

        for (String sql : invalidCalls) {
            assertSqlState("22023", () -> jdbc.sql(sql).query(Boolean.class).single());
            assertThat(storedVolumeId()).isEqualTo(actualVolumeId);
        }
        assertSqlState("23514", () -> migratorJdbc().sql("""
                        update portfolio.local_staging_policy
                        set volume_id=:volumeId
                        where singleton_key=1
                        """)
                .param("volumeId", "A".repeat(64))
                .update());
        assertThat(storedVolumeId()).isEqualTo(actualVolumeId);
    }

    @Test
    void theSameVolumeCanClaimRepeatedlyAndRemainsExact() {
        clearBindingForClaimTest();

        assertThat(claim(actualVolumeId)).isTrue();
        assertThat(claim(actualVolumeId)).isTrue();
        assertThat(storedVolumeId()).isEqualTo(actualVolumeId);
    }

    @Test
    void concurrentDifferentVolumesAllowExactlyOneFirstClaim() throws Exception {
        clearBindingForClaimTest();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ClaimOutcome> first = executor.submit(
                    () -> concurrentClaim(VOLUME_A, ready, start));
            Future<ClaimOutcome> second = executor.submit(
                    () -> concurrentClaim(VOLUME_B, ready, start));
            assertThat(ready.await(10, SECONDS)).isTrue();
            start.countDown();

            List<ClaimOutcome> outcomes = List.of(
                    first.get(15, SECONDS), second.get(15, SECONDS));
            String stored = storedVolumeId();
            assertThat(outcomes)
                    .extracting(ClaimOutcome::claimed)
                    .containsExactlyInAnyOrder(true, false);
            assertThat(outcomes)
                    .filteredOn(ClaimOutcome::claimed)
                    .extracting(ClaimOutcome::volumeId)
                    .containsExactly(stored);
            assertThat(stored).isIn(VOLUME_A, VOLUME_B);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void runtimeCannotBypassTheClaimFunctionWithDirectVolumeWrites() {
        assertSqlState("42501", () -> jdbc.sql("""
                        update portfolio.local_staging_policy
                        set volume_id=:volumeId
                        where singleton_key=1
                        """)
                .param("volumeId", VOLUME_A)
                .update());
        assertThat(storedVolumeId()).isEqualTo(actualVolumeId);
    }

    private ClaimOutcome concurrentClaim(
            String volumeId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, SECONDS)) {
            throw new IllegalStateException("test volume claim start timed out");
        }
        return new ClaimOutcome(volumeId, claim(volumeId));
    }

    private boolean claim(String volumeId) {
        return jdbc.sql("select portfolio.claim_local_staging_volume(:volumeId)")
                .param("volumeId", volumeId)
                .query(Boolean.class)
                .single();
    }

    private void clearBindingForClaimTest() {
        assertThat(migratorJdbc().sql("""
                        update portfolio.local_staging_policy
                        set volume_id=null
                        where singleton_key=1
                        """)
                .update()).isOne();
        assertThat(storedVolumeId()).isNull();
    }

    private String storedVolumeId() {
        return migratorJdbc().sql("""
                        select volume_id
                        from portfolio.local_staging_policy
                        where singleton_key=1
                        """)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private static void assertSqlState(String expected, Runnable operation) {
        Throwable failure = catchThrowable(operation::run);
        assertThat(failure).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(SQLException.class);
        assertThat(((SQLException) root).getSQLState()).isEqualTo(expected);
    }

    private record ColumnShape(String dataType, int maximumLength, String nullable) {}

    private record FunctionShape(boolean securityDefiner, String configuration) {}

    private record ClaimOutcome(String volumeId, boolean claimed) {}
}
