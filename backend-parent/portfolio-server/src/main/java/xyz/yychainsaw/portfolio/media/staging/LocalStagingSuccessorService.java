package xyz.yychainsaw.portfolio.media.staging;

public interface LocalStagingSuccessorService {
    boolean scheduleFromHandler(LocalStagingReservation expected);

    boolean scheduleFromDeadLetter(LocalStagingReservation expected);
}
