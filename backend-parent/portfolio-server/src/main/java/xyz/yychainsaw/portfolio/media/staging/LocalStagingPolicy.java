package xyz.yychainsaw.portfolio.media.staging;

public record LocalStagingPolicy(
        int activeCapacity,
        int scanEntryCeiling,
        int worstCaseEntriesPerReservation,
        int reservedHeadroom) {
    public static final int FIXED_WORST_CASE_ENTRIES_PER_RESERVATION = 6;
    private static final int MAXIMUM_VALUE = 1_000_000;

    public LocalStagingPolicy {
        if (!withinBounds(activeCapacity)
                || !withinBounds(scanEntryCeiling)
                || worstCaseEntriesPerReservation
                        != FIXED_WORST_CASE_ENTRIES_PER_RESERVATION
                || !withinBounds(reservedHeadroom)
                || !hasStrictHeadroom(
                        activeCapacity,
                        worstCaseEntriesPerReservation,
                        reservedHeadroom,
                        scanEntryCeiling)) {
            throw invalid();
        }
    }

    private static boolean withinBounds(int value) {
        return value > 0 && value <= MAXIMUM_VALUE;
    }

    private static boolean hasStrictHeadroom(
            int capacity, int entriesPerReservation, int headroom, int ceiling) {
        try {
            long ownedEntries = Math.multiplyExact(
                    (long) capacity, (long) entriesPerReservation);
            return Math.addExact(ownedEntries, (long) headroom) < (long) ceiling;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("local staging policy is invalid");
    }
}
