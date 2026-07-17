package xyz.yychainsaw.portfolio.media.staging;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.media.storage.LocalStagingAuditExpectation;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public class LocalStagingReconciliationService {
    private static final String MIGRATION_REQUIRED =
            "LOCAL_STAGING_MIGRATION_REQUIRED";
    private static final Logger LOGGER =
            LoggerFactory.getLogger(LocalStagingReconciliationService.class);

    private final LocalStagingReservationRepository reservations;
    private final LocalStorageService storage;
    private final int activeCapacity;
    private final int boundedReadLimit;

    public LocalStagingReconciliationService(
            LocalStagingReservationRepository reservations,
            LocalStorageService storage,
            LocalStagingPolicyProperties properties) {
        this.reservations = Objects.requireNonNull(
                reservations, "local staging reservations are required");
        this.storage = Objects.requireNonNull(storage, "local storage is required");
        LocalStagingPolicy policy = Objects.requireNonNull(
                properties, "local staging policy properties are required").toPolicy();
        this.activeCapacity = policy.activeCapacity();
        try {
            this.boundedReadLimit = Math.addExact(activeCapacity, 1);
        } catch (ArithmeticException overflow) {
            throw fixed();
        }
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 10)
    public void auditStartup() {
        reconcile(false);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 10)
    public void auditDaily() {
        reconcile(true);
    }

    private void reconcile(boolean rejectStalledChain) {
        try {
            reservations.acquireCapacityLock();
            List<LocalStagingReservation> current =
                    reservations.findAllBounded(boundedReadLimit);
            if (current == null || current.size() > activeCapacity) {
                throw fixed();
            }
            Map<UUID, LocalStagingAuditExpectation> expected =
                    new LinkedHashMap<>();
            for (LocalStagingReservation reservation : current) {
                if (reservation == null) {
                    throw fixed();
                }
                LocalStagingAuditExpectation expectation =
                        new LocalStagingAuditExpectation(
                                reservation.assetId(),
                                reservation.sha256(),
                                reservation.mimeType());
                if (expected.putIfAbsent(reservation.assetId(), expectation) != null) {
                    throw fixed();
                }
            }
            if (rejectStalledChain && reservations.hasStalledReservation()) {
                LOGGER.warn(
                        "Local staging reservation chain stalled activeReservations={}",
                        current.size());
                throw fixed();
            }
            storage.auditReservedStaging(Map.copyOf(expected));
        } catch (RuntimeException failure) {
            throw fixed();
        }
    }

    private static IllegalStateException fixed() {
        return new IllegalStateException(MIGRATION_REQUIRED);
    }
}
