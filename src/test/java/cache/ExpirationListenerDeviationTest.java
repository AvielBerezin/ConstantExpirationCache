package cache;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ExpirationListenerDeviationTest {
    private enum Operation {PUT, EXPIRE}

    private record TimestampedIntEvent(Instant when, Operation operation, int num) {
        private TimestampedIntEvent(int num, Operation operation) {
            this(Instant.now(), operation, num);
        }

        public static TimestampedIntEvent createPut(int num) {
            return new TimestampedIntEvent(num, Operation.PUT);
        }

        public static TimestampedIntEvent createExpire(int num) {
            return new TimestampedIntEvent(num, Operation.EXPIRE);
        }
    }

    private record DurationIntEvent(Duration howLong, int num) {
        public static DurationIntEvent durationIntEvent(TimestampedIntEvent put, TimestampedIntEvent expire) {
            assert put.num == expire.num;
            assert Operation.PUT.equals(put.operation);
            assert Operation.EXPIRE.equals(expire.operation);
            return new DurationIntEvent(Utils.instantDifference(put.when(), expire.when()), put.num());
        }
    }

    @Test
    public void easyOnlyPuts() throws InterruptedException, IOException {
        ConcurrentLinkedQueue<TimestampedIntEvent> puts = new ConcurrentLinkedQueue<>();
        Duration expiration = Duration.ofMillis(8000);
        try (ConstantExpirationCache<Integer, Integer> cache = new ConstantExpirationCache<>(expiration,
                                                                                             Executors.newSingleThreadScheduledExecutor(),
                                                                                             n -> puts.add(TimestampedIntEvent.createExpire(n)))) {
            for (int i = 0; i < 1000; i++) {
                puts.add(TimestampedIntEvent.createPut(i));
                cache.put(i, i);
            }
            Thread.sleep(expiration.multipliedBy(2L).compareTo(Duration.ofMillis(100)) < 0
                         ? Duration.ofMillis(100)
                         : expiration.multipliedBy(2L));
        }
        System.out.println("timestamps:");
        new ByteArrayInputStream(puts.stream()
                                     .map(event -> "%-50s %-6s %d%n".formatted(event.when, event.operation, event.num))
                                     .collect(Collectors.joining())
                                     .getBytes(StandardCharsets.UTF_8))
                .transferTo(System.out);
        Map<Integer, DurationIntEvent> durations = puts.stream().collect(Collectors.groupingBy(event -> event.num, Collectors.collectingAndThen(Collectors.toList(), events -> {
            assert events.size() == 2;
            assert events.get(0).operation.equals(Operation.PUT);
            assert events.get(1).operation.equals(Operation.EXPIRE);
            TimestampedIntEvent put = events.get(0);
            TimestampedIntEvent expire = events.get(1);
            return DurationIntEvent.durationIntEvent(put, expire);
        })));
        System.out.printf("durations: (expiration is set to %s)%n", expiration);
        new ByteArrayInputStream(durations.values()
                                          .stream()
                                          .map(event -> "%-3d %s%n".formatted(event.num, event.howLong))
                                          .collect(Collectors.joining()).getBytes(StandardCharsets.UTF_8))
                .transferTo(System.out);
    }
}