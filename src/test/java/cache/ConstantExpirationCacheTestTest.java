package cache;

import org.assertj.core.api.Assertions;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;

public class ConstantExpirationCacheTestTest {
    @Test
    public void onExpiration() throws InterruptedException {
        ConstantExpirationCache<Integer, String> cache = new ConstantExpirationCache<>(Duration.ofMillis(500), x -> {
            System.out.printf("%s %s%n", Instant.now(), x);
        });
        cache.put(1, "hello");
        Thread.sleep(200);
        cache.put(2, "world");
        Thread.sleep(1000);
        Assertions.assertThat(cache.values(Collectors.reducing((ignored1, ignored2) -> ignored1)))
                  .isEmpty();
    }
}