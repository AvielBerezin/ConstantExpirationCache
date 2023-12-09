package cache;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;

public class Utils {
    public static Duration instantDifference(Instant from, Instant to) {
        return Duration.of(from.getEpochSecond(), ChronoUnit.SECONDS)
                       .plus(from.getNano(), ChronoUnit.NANOS)
                       .minus(to.getEpochSecond(), ChronoUnit.SECONDS)
                       .minus(to.getNano(), ChronoUnit.NANOS);
    }

    public static <Element> void shuffle(Random random, List<Element> elements) {
        for (int i = 0; i < elements.size(); i++) {
            int swapIndex = random.nextInt(i, elements.size());
            Element head = elements.get(i);
            elements.set(i, elements.get(swapIndex));
            elements.set(swapIndex, head);
        }
    }
}
