package xyz.yychainsaw.portfolio.media.staging;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LocalStagingReservationRepository {
    private static final int MAXIMUM_BOUNDED_RESERVATION_READ = 1_000_001;
    private static final Pattern VOLUME_ID = Pattern.compile("[0-9a-f]{64}");
    private static final int ADVISORY_NAMESPACE = 1_342_178_387;
    private static final int POLICY_LOCK = 1;
    private static final int CAPACITY_LOCK = 2;
    private static final RowMapper<LocalStagingPolicy> POLICY_MAPPER =
            (resultSet, rowNumber) -> new LocalStagingPolicy(
                    resultSet.getInt("active_capacity"),
                    resultSet.getInt("scan_entry_ceiling"),
                    resultSet.getInt("worst_case_entries_per_reservation"),
                    resultSet.getInt("reserved_headroom"));
    private static final RowMapper<LocalStagingReservation> RESERVATION_MAPPER =
            (resultSet, rowNumber) -> new LocalStagingReservation(
                    resultSet.getObject("asset_id", UUID.class),
                    resultSet.getString("sha256"),
                    resultSet.getString("mime_type"),
                    resultSet.getLong("generation"),
                    resultSet.getObject("cleanup_job_id", UUID.class),
                    resultSet.getObject("reserved_at", OffsetDateTime.class),
                    resultSet.getObject("cleanup_after", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public LocalStagingReservationRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    public void acquirePolicyLock() {
        acquireTransactionLock(POLICY_LOCK);
    }

    public void acquireCapacityLock() {
        acquireTransactionLock(CAPACITY_LOCK);
    }

    public Optional<LocalStagingPolicy> findPolicy() {
        return jdbc.sql("""
                        select active_capacity, scan_entry_ceiling,
                               worst_case_entries_per_reservation,
                               reserved_headroom
                        from portfolio.local_staging_policy
                        where singleton_key=1
                        """)
                .query(POLICY_MAPPER)
                .optional();
    }

    public int insertPolicyIfAbsent(LocalStagingPolicy policy) {
        Objects.requireNonNull(policy, "local staging policy is required");
        return jdbc.sql("""
                        insert into portfolio.local_staging_policy(
                            singleton_key, active_capacity, scan_entry_ceiling,
                            worst_case_entries_per_reservation, reserved_headroom
                        ) values (
                            1, :activeCapacity, :scanEntryCeiling,
                            :worstCaseEntries, :reservedHeadroom
                        )
                        on conflict (singleton_key) do nothing
                        """)
                .param("activeCapacity", policy.activeCapacity())
                .param("scanEntryCeiling", policy.scanEntryCeiling())
                .param("worstCaseEntries", policy.worstCaseEntriesPerReservation())
                .param("reservedHeadroom", policy.reservedHeadroom())
                .update();
    }

    public boolean claimVolumeId(String volumeId) {
        requireCanonicalVolumeId(volumeId);
        return jdbc.sql("select portfolio.claim_local_staging_volume(:volumeId)")
                .param("volumeId", volumeId)
                .query(Boolean.class)
                .single();
    }

    public boolean volumeMatches(String volumeId) {
        requireCanonicalVolumeId(volumeId);
        return jdbc.sql("""
                        select exists (
                            select 1
                            from portfolio.local_staging_policy
                            where singleton_key=1
                              and volume_id=:volumeId
                        )
                        """)
                .param("volumeId", volumeId)
                .query(Boolean.class)
                .single();
    }

    public long countActiveReservations() {
        return jdbc.sql("select count(*) from portfolio.local_staging_reservation")
                .query(Long.class)
                .single();
    }

    public OffsetDateTime databaseNow() {
        return jdbc.sql("select clock_timestamp()")
                .query(OffsetDateTime.class)
                .single();
    }

    public void insert(LocalStagingReservation reservation) {
        Objects.requireNonNull(reservation, "local staging reservation is required");
        int inserted = jdbc.sql("""
                        insert into portfolio.local_staging_reservation(
                            asset_id, sha256, mime_type, generation,
                            cleanup_job_id, reserved_at, cleanup_after
                        ) values (
                            :assetId, :sha256, :mimeType, :generation,
                            :cleanupJobId, :reservedAt, :cleanupAfter
                        )
                        """)
                .param("assetId", reservation.assetId())
                .param("sha256", reservation.sha256())
                .param("mimeType", reservation.mimeType())
                .param("generation", reservation.generation())
                .param("cleanupJobId", reservation.cleanupJobId())
                .param("reservedAt", reservation.reservedAt(), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("cleanupAfter", reservation.cleanupAfter(), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("local staging reservation insert failed");
        }
    }

    public Optional<LocalStagingReservation> findByAssetId(UUID assetId) {
        Objects.requireNonNull(assetId, "local staging asset id is required");
        return jdbc.sql("""
                        select asset_id, sha256, mime_type, generation,
                               cleanup_job_id, reserved_at, cleanup_after
                        from portfolio.local_staging_reservation
                        where asset_id=:assetId
                        """)
                .param("assetId", assetId)
                .query(RESERVATION_MAPPER)
                .optional();
    }

    public Optional<LocalStagingReservation> findByAssetIdForUpdate(UUID assetId) {
        Objects.requireNonNull(assetId, "local staging asset id is required");
        return jdbc.sql("""
                        select asset_id, sha256, mime_type, generation,
                               cleanup_job_id, reserved_at, cleanup_after
                        from portfolio.local_staging_reservation
                        where asset_id=:assetId
                        for update
                        """)
                .param("assetId", assetId)
                .query(RESERVATION_MAPPER)
                .optional();
    }

    public List<LocalStagingReservation> findAllBounded(int limit) {
        if (limit < 1 || limit > MAXIMUM_BOUNDED_RESERVATION_READ) {
            throw new IllegalArgumentException(
                    "local staging reservation limit is invalid");
        }
        return List.copyOf(jdbc.sql("""
                        select asset_id, sha256, mime_type, generation,
                               cleanup_job_id, reserved_at, cleanup_after
                        from portfolio.local_staging_reservation
                        order by asset_id
                        limit :limit
                        """)
                .param("limit", limit)
                .query(RESERVATION_MAPPER)
                .list());
    }

    public boolean hasStalledReservation() {
        return jdbc.sql("""
                        select exists (
                            select 1
                            from portfolio.local_staging_reservation
                            where reserved_at
                                < clock_timestamp()
                                  - interval '7 days'
                        )
                        """)
                .query(Boolean.class)
                .single();
    }

    public boolean advanceSuccessorExact(
            LocalStagingReservation expected, UUID nextCleanupJobId) {
        Objects.requireNonNull(expected, "expected local staging reservation is required");
        Objects.requireNonNull(nextCleanupJobId, "next cleanup job id is required");
        long nextGeneration;
        try {
            nextGeneration = Math.addExact(expected.generation(), 1L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("local staging successor is invalid");
        }
        if (nextCleanupJobId.equals(expected.cleanupJobId())) {
            throw new IllegalArgumentException("local staging successor is invalid");
        }
        return jdbc.sql("""
                        update portfolio.local_staging_reservation
                        set generation=:nextGeneration,
                            cleanup_job_id=:nextCleanupJobId
                        where asset_id=:assetId
                          and sha256=:sha256
                          and mime_type=:mimeType
                          and generation=:generation
                          and cleanup_job_id=:cleanupJobId
                        """)
                .param("nextGeneration", nextGeneration)
                .param("nextCleanupJobId", nextCleanupJobId)
                .param("assetId", expected.assetId())
                .param("sha256", expected.sha256())
                .param("mimeType", expected.mimeType())
                .param("generation", expected.generation())
                .param("cleanupJobId", expected.cleanupJobId())
                .update() == 1;
    }

    public boolean deleteExact(LocalStagingReservation expected) {
        Objects.requireNonNull(expected, "expected local staging reservation is required");
        return jdbc.sql("""
                        delete from portfolio.local_staging_reservation
                        where asset_id=:assetId
                          and sha256=:sha256
                          and mime_type=:mimeType
                          and generation=:generation
                          and cleanup_job_id=:cleanupJobId
                        """)
                .param("assetId", expected.assetId())
                .param("sha256", expected.sha256())
                .param("mimeType", expected.mimeType())
                .param("generation", expected.generation())
                .param("cleanupJobId", expected.cleanupJobId())
                .update() == 1;
    }

    private void acquireTransactionLock(int lock) {
        jdbc.sql("select pg_catalog.pg_advisory_xact_lock(:namespace, :lock)")
                .param("namespace", ADVISORY_NAMESPACE)
                .param("lock", lock)
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .single();
    }

    private static void requireCanonicalVolumeId(String volumeId) {
        if (volumeId == null || !VOLUME_ID.matcher(volumeId).matches()) {
            throw new IllegalArgumentException("local staging volume id is invalid");
        }
    }
}
