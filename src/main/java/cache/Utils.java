package cache;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class Utils {
    public static Duration instantDifference(Instant from, Instant to) {
        return Duration.of(from.getEpochSecond(), ChronoUnit.SECONDS)
                       .plus(from.getNano(), ChronoUnit.NANOS)
                       .minus(to.getEpochSecond(), ChronoUnit.SECONDS)
                       .minus(to.getNano(), ChronoUnit.NANOS);
    }
}
