package xyz.yychainsaw.portfolio.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
class MigrationSmokeTest extends PostgresIntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @Test
    void flywayAppliesFoundationVersionsInOrderAndCreatesSessionTables() {
        List<String> versions = migratorJdbc()
                .sql("select version from flyway_schema_history where success order by installed_rank")
                .query(String.class).list();
        assertThat(versions).contains("1", "2");
        assertThat(versions).containsSubsequence("1", "2");

        Integer tableCount = jdbc.sql("""
                select count(*) from information_schema.tables
                where table_schema='portfolio' and table_name in
                ('spring_session','spring_session_attributes','admin_user',
                 'totp_recovery_code','admin_session_metadata','audit_log')
                """).query(Integer.class).single();
        assertThat(tableCount).isEqualTo(6);
    }

    @Test
    void runtimeAccountCannotCreateTables() {
        assertThatThrownBy(() -> jdbc.sql("create table runtime_must_not_create(id integer)").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
    }
}
