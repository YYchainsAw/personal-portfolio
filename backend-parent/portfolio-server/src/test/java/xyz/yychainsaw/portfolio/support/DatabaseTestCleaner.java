package xyz.yychainsaw.portfolio.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Narrow, dependency-aware cleanup for state that intentionally survives a production
 * transaction but must not leak between integration-test classes.
 */
public final class DatabaseTestCleaner {
    private static final UUID SITE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_CATALOG_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    private DatabaseTestCleaner() {}

    public static void clearPublishingHistory() {
        inTransaction(DatabaseTestCleaner::clearPublishingHistory);
    }

    public static void clearAuthenticationState() {
        inTransaction(connection -> {
            clearPublishingHistory(connection);

            requireTriggerEnabled(
                    connection, "audit_log", "audit_log_reject_mutation");
            requireTriggerEnabled(
                    connection, "audit_log", "audit_log_reject_truncate");
            execute(connection, """
                    alter table portfolio.audit_log
                    disable trigger audit_log_reject_mutation
                    """);
            execute(connection, "delete from portfolio.audit_log");
            execute(connection, """
                    alter table portfolio.audit_log
                    enable trigger audit_log_reject_mutation
                    """);
            requireTriggerEnabled(
                    connection, "audit_log", "audit_log_reject_mutation");
            requireTriggerEnabled(
                    connection, "audit_log", "audit_log_reject_truncate");

            execute(connection, "delete from portfolio.admin_session_metadata");
            execute(connection, "delete from portfolio.spring_session_attributes");
            execute(connection, "delete from portfolio.spring_session");
            execute(connection, "delete from portfolio.totp_recovery_code");
            execute(connection, "delete from portfolio.admin_user");
        });
    }

    public static void clearMediaReferences() {
        inTransaction(connection -> {
            clearPublishingHistory(connection);
            execute(connection, "delete from portfolio.hero_media");
            execute(connection, "delete from portfolio.project_media");
            execute(connection, "delete from portfolio.content_block_media");
            execute(connection, "delete from portfolio.content_block_video");
            execute(connection, "delete from portfolio.content_block_action");
            execute(connection, "delete from portfolio.resume_document");
        });
    }

    static void failAfterDisablingImmutableTriggersForTest() {
        inTransaction(connection -> {
            requireTriggerEnabled(
                    connection, "audit_log", "audit_log_reject_mutation");
            requireTriggerEnabled(
                    connection,
                    "revision_media_reference",
                    "revision_media_reference_immutable");
            requireTriggerEnabled(
                    connection, "content_revision", "content_revision_immutable");
            execute(connection, """
                    alter table portfolio.audit_log
                    disable trigger audit_log_reject_mutation
                    """);
            execute(connection, """
                    alter table portfolio.revision_media_reference
                    disable trigger revision_media_reference_immutable
                    """);
            execute(connection, """
                    alter table portfolio.content_revision
                    disable trigger content_revision_immutable
                    """);
            throw new IllegalStateException("forced test-cleanup rollback");
        });
    }

    private static void clearPublishingHistory(Connection connection) throws SQLException {
        requireTriggerEnabled(
                connection, "revision_media_reference", "revision_media_reference_immutable");
        requireTriggerEnabled(
                connection, "content_revision", "content_revision_immutable");

        execute(connection, """
                alter table portfolio.revision_media_reference
                disable trigger revision_media_reference_immutable
                """);
        execute(connection, "delete from portfolio.revision_media_reference");
        execute(connection, """
                alter table portfolio.revision_media_reference
                enable trigger revision_media_reference_immutable
                """);
        execute(connection, "delete from portfolio.slug_redirect");
        execute(connection, "delete from portfolio.publication");
        execute(connection, """
                alter table portfolio.content_revision
                disable trigger content_revision_immutable
                """);
        execute(connection, "delete from portfolio.content_revision");
        execute(connection, """
                alter table portfolio.content_revision
                enable trigger content_revision_immutable
                """);

        requireTriggerEnabled(
                connection, "revision_media_reference", "revision_media_reference_immutable");
        requireTriggerEnabled(
                connection, "content_revision", "content_revision_immutable");

        try (PreparedStatement insert = connection.prepareStatement("""
                insert into portfolio.publication (
                    aggregate_type, aggregate_id, status, version
                ) values (?, ?, 'ARCHIVED', 0), (?, ?, 'ARCHIVED', 0)
                """)) {
            insert.setString(1, "SITE");
            insert.setObject(2, SITE_ID);
            insert.setString(3, "PROJECT_CATALOG");
            insert.setObject(4, PROJECT_CATALOG_ID);
            if (insert.executeUpdate() != 2) {
                throw new IllegalStateException(
                        "publishing cleanup did not restore both singleton pointers");
            }
        }
        requireExactPublicationSeeds(connection);
    }

    private static void requireExactPublicationSeeds(Connection connection)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                select aggregate_type, aggregate_id, status, current_revision_id,
                       current_slug, version, published_at
                from portfolio.publication
                order by aggregate_type, aggregate_id
                """);
                ResultSet rows = query.executeQuery()) {
            requireSeed(rows, "PROJECT_CATALOG", PROJECT_CATALOG_ID);
            requireSeed(rows, "SITE", SITE_ID);
            if (rows.next()) {
                throw new IllegalStateException(
                        "publishing cleanup retained an unexpected publication pointer");
            }
        }
    }

    private static void requireSeed(ResultSet rows, String type, UUID id)
            throws SQLException {
        if (!rows.next()
                || !type.equals(rows.getString("aggregate_type"))
                || !id.equals(rows.getObject("aggregate_id", UUID.class))
                || !"ARCHIVED".equals(rows.getString("status"))
                || rows.getObject("current_revision_id") != null
                || rows.getString("current_slug") != null
                || rows.getLong("version") != 0L
                || rows.getObject("published_at") != null) {
            throw new IllegalStateException(
                    "publishing cleanup did not restore the exact " + type + " seed");
        }
    }

    private static void requireTriggerEnabled(
            Connection connection, String table, String trigger) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                select trigger_state.tgenabled
                from pg_catalog.pg_trigger trigger_state
                join pg_catalog.pg_class target
                  on target.oid=trigger_state.tgrelid
                join pg_catalog.pg_namespace namespace
                  on namespace.oid=target.relnamespace
                where namespace.nspname='portfolio'
                  and target.relname=?
                  and trigger_state.tgname=?
                  and not trigger_state.tgisinternal
                """)) {
            query.setString(1, table);
            query.setString(2, trigger);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || !"O".equals(rows.getString(1)) || rows.next()) {
                    throw new IllegalStateException(
                            "test cleanup requires enabled trigger " + trigger);
                }
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void inTransaction(SqlWork work) {
        try (Connection connection =
                PostgresIntegrationTestBase.migratorDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (Throwable failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw propagate(failure);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("test database cleanup failed", failure);
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("test database cleanup failed", failure);
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws Exception;
    }
}
