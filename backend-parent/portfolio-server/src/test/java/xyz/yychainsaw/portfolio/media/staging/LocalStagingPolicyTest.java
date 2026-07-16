package xyz.yychainsaw.portfolio.media.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalStagingPolicyTest {
    @Test
    void validatesStrictCapacityWithOverflowSafeArithmeticAndFixedCardinality() {
        LocalStagingPolicy policy = new LocalStagingPolicy(3, 64, 6, 16);

        assertThat(policy.activeCapacity()).isEqualTo(3);
        assertThat(policy.worstCaseEntriesPerReservation()).isEqualTo(6);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalStagingPolicy(16_666, 100_000, 6, 4))
                .withMessage("local staging policy is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalStagingPolicy(1, 100, 5, 1))
                .withMessage("local staging policy is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalStagingPolicy(
                        Integer.MAX_VALUE, Integer.MAX_VALUE, 6, Integer.MAX_VALUE))
                .withMessage("local staging policy is invalid")
                .withNoCause();
    }

    @Test
    void reservationAndReceiptStringFormsAreAlwaysRedacted() {
        OffsetDateTime now = OffsetDateTime.of(
                2026, 7, 17, 1, 0, 0, 0, ZoneOffset.UTC);
        LocalStagingReservation reservation = new LocalStagingReservation(
                UUID.randomUUID(),
                "a".repeat(64),
                "image/jpeg",
                0,
                UUID.randomUUID(),
                now,
                now.plusHours(24));

        assertThat(reservation).hasToString("LocalStagingReservation[redacted]");
        assertThat(new LocalStagingReservationReceipt(reservation))
                .hasToString("LocalStagingReservationReceipt[redacted]");
    }
}
