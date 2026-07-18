package xyz.yychainsaw.portfolio.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Isolated
class DatabaseTestCleanerTest extends PostgresIntegrationTestBase {
    @Test
    void rollbackRestoresEveryImmutableTriggerAndExactPublicationSeeds() {
        DatabaseTestCleaner.clearAuthenticationState();

        assertThatThrownBy(DatabaseTestCleaner::failAfterDisablingImmutableTriggersForTest)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced test-cleanup rollback");

        List<String> triggerStates = migratorJdbc().sql("""
                        select trigger_state.tgname || ':'
                               || trigger_state.tgenabled::text
                        from pg_catalog.pg_trigger trigger_state
                        join pg_catalog.pg_class target
                          on target.oid=trigger_state.tgrelid
                        join pg_catalog.pg_namespace namespace
                          on namespace.oid=target.relnamespace
                        where namespace.nspname='portfolio'
                          and trigger_state.tgname in (
                              'audit_log_reject_mutation',
                              'audit_log_reject_truncate',
                              'revision_media_reference_immutable',
                              'content_revision_immutable'
                          )
                          and not trigger_state.tgisinternal
                        order by trigger_state.tgname
                        """)
                .query(String.class)
                .list();
        assertThat(triggerStates).containsExactly(
                "audit_log_reject_mutation:O",
                "audit_log_reject_truncate:O",
                "content_revision_immutable:O",
                "revision_media_reference_immutable:O");

        Boolean exactSeeds = migratorJdbc().sql("""
                        select count(*)=2
                           and count(*) filter (where
                               aggregate_type='SITE'
                               and aggregate_id='00000000-0000-0000-0000-000000000001'
                               and status='ARCHIVED'
                               and current_revision_id is null
                               and current_slug is null
                               and version=0
                               and published_at is null)=1
                           and count(*) filter (where
                               aggregate_type='PROJECT_CATALOG'
                               and aggregate_id='00000000-0000-0000-0000-000000000002'
                               and status='ARCHIVED'
                               and current_revision_id is null
                               and current_slug is null
                               and version=0
                               and published_at is null)=1
                        from portfolio.publication
                        """)
                .query(Boolean.class)
                .single();
        assertThat(exactSeeds).isTrue();
    }
}
