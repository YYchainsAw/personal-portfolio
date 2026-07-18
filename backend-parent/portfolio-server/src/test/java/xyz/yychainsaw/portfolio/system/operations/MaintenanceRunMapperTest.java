package xyz.yychainsaw.portfolio.system.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.yychainsaw.portfolio.system.operations.MaintenanceRunMapper.MaintenanceRunSnapshot;

@ExtendWith(MockitoExtension.class)
class MaintenanceRunMapperTest {
    @Mock ResultSet resultSet;

    @Test
    void querySelectsOnlyTheLatestAllowlistedRedactedColumns() {
        String sql = MaintenanceRunMapper.LATEST_ALLOWLISTED_RUNS_SQL;

        assertThat(sql)
                .contains("distinct on (run_type)")
                .contains("where run_type in (:runTypes)")
                .contains("order by run_type, started_at desc, id desc")
                .contains("run_type, status, started_at, finished_at, artifact_checksum")
                .doesNotContain(
                        "error_summary",
                        "details",
                        "payload",
                        "object_key",
                        "bucket",
                        "path",
                        "credential",
                        "secret");
        assertThat(MaintenanceView.supportedTypes()).containsExactly(
                "DATABASE_BACKUP",
                "MEDIA_BACKUP",
                "ANALYTICS_AGGREGATE",
                "CONTACT_RETENTION",
                "MEDIA_CLEANUP_SCAN",
                "DEPLOYMENT",
                "RESTORE_DRILL");
    }

    @Test
    void rowMappingNormalizesDatabaseTimestampsAndKeepsNullableFields()
            throws Exception {
        OffsetDateTime started = OffsetDateTime.ofInstant(
                Instant.parse("2026-07-18T01:00:00Z"), ZoneOffset.ofHours(8));
        given(resultSet.getString("run_type")).willReturn("DATABASE_BACKUP");
        given(resultSet.getString("status")).willReturn("RUNNING");
        given(resultSet.getObject("started_at", OffsetDateTime.class))
                .willReturn(started);
        given(resultSet.getObject("finished_at", OffsetDateTime.class))
                .willReturn(null);
        given(resultSet.getString("artifact_checksum")).willReturn(null);

        MaintenanceRunSnapshot result = MaintenanceRunMapper.mapRow(resultSet, 0);

        assertThat(result.runType()).isEqualTo("DATABASE_BACKUP");
        assertThat(result.status()).isEqualTo("RUNNING");
        assertThat(result.startedAt()).isEqualTo(Instant.parse("2026-07-18T01:00:00Z"));
        assertThat(result.finishedAt()).isNull();
        assertThat(result.artifactChecksum()).isNull();
    }
}
