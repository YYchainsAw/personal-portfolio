package xyz.yychainsaw.portfolio.system.operations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public final class MaintenanceRunMapper {
    static final String LATEST_ALLOWLISTED_RUNS_SQL = """
            select distinct on (run_type)
                   run_type, status, started_at, finished_at, artifact_checksum
            from portfolio.maintenance_run
            where run_type in (:runTypes)
            order by run_type, started_at desc, id desc
            """;
    private static final RowMapper<MaintenanceRunSnapshot> ROW_MAPPER =
            MaintenanceRunMapper::mapRow;

    private final JdbcClient jdbc;

    public MaintenanceRunMapper(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "operations JDBC client is required");
    }

    public List<MaintenanceRunSnapshot> findLatestAllowlistedRuns() {
        return List.copyOf(jdbc.sql(LATEST_ALLOWLISTED_RUNS_SQL)
                .param("runTypes", MaintenanceView.supportedTypes())
                .query(ROW_MAPPER)
                .list());
    }

    static MaintenanceRunSnapshot mapRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new MaintenanceRunSnapshot(
                resultSet.getString("run_type"),
                resultSet.getString("status"),
                instant(resultSet, "started_at"),
                instant(resultSet, "finished_at"),
                resultSet.getString("artifact_checksum"));
    }

    private static Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record MaintenanceRunSnapshot(
            String runType,
            String status,
            Instant startedAt,
            Instant finishedAt,
            String artifactChecksum) {}
}
