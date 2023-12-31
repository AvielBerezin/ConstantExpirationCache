package cache;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ConstantExpirationCacheTest {
    @Test
    public void expiredValuesAreNotPresent() throws InterruptedException {
        try (ConstantExpirationCache<Integer, String> cache = new ConstantExpirationCache<>(Duration.ofMillis(500), Executors.newSingleThreadScheduledExecutor(), ignored -> {})) {
            cache.put(1, "hello");
            Thread.sleep(200);
            cache.put(2, "world");
            Thread.sleep(1000);
            Assertions.assertThat(cache.values(Collectors.toList()))
                      .isEmpty();
        }
    }

    @Test
    public void onExpirationIsCalledWhenReached() throws InterruptedException {
        @SuppressWarnings("unchecked")
        Consumer<String> onExpiration = Mockito.mock(Consumer.class);
        try (ConstantExpirationCache<Integer, String> cache = new ConstantExpirationCache<>(Duration.ofMillis(500), Executors.newSingleThreadScheduledExecutor(), onExpiration)) {
            cache.put(1, "hello");
            Thread.sleep(200);
            cache.put(2, "world");
            Thread.sleep(300);
            cache.put(3, "garbage");
            Thread.sleep(300);
            Mockito.verify(onExpiration, Mockito.times(1)).accept("hello");
            Mockito.verify(onExpiration, Mockito.times(1)).accept("world");
            Mockito.verify(onExpiration, Mockito.never()).accept("garbage");
        }
    }

    @Test
    public void onExpirationIsCanceledByRemoval() throws InterruptedException {
        @SuppressWarnings("unchecked")
        Consumer<String> onExpiration = Mockito.mock(Consumer.class);
        try (ConstantExpirationCache<Integer, String> cache = new ConstantExpirationCache<>(Duration.ofMillis(500), Executors.newSingleThreadScheduledExecutor(), onExpiration)) {
            cache.put(1, "hello");
            Thread.sleep(200);
            cache.put(2, "world");
            cache.remove(1);
            Thread.sleep(600);
            Mockito.verify(onExpiration, Mockito.never()).accept("hello");
            Mockito.verify(onExpiration, Mockito.times(1)).accept("world");
        }
    }

    @Test
    public void onExpirationIsRenewedByUpdate() throws InterruptedException {
        @SuppressWarnings("unchecked")
        Consumer<String> onExpiration = Mockito.mock(Consumer.class);
        try (ConstantExpirationCache<Integer, String> cache = new ConstantExpirationCache<>(Duration.ofMillis(500), Executors.newSingleThreadScheduledExecutor(), onExpiration)) {
            cache.put(1, "hello");
            Thread.sleep(200);
            cache.put(2, "world");
            Thread.sleep(200);
            cache.put(1, "hello?");
            Thread.sleep(400);
            Mockito.verify(onExpiration, Mockito.never()).accept("hello");
            Mockito.verify(onExpiration, Mockito.never()).accept("hello?");
            Mockito.verify(onExpiration, Mockito.times(1)).accept("world");
            Thread.sleep(200);
            Mockito.verify(onExpiration, Mockito.times(1)).accept("hello?");
        }
    }

    @Test
    public void remove() throws InterruptedException {
        @SuppressWarnings("unchecked")
        Consumer<String> onExpiration = Mockito.mock(Consumer.class);
        try (ConstantExpirationCache<Integer, String> cache = new ConstantExpirationCache<>(Duration.ofMillis(500), Executors.newSingleThreadScheduledExecutor(), onExpiration)) {
            cache.put(1, "hello");
            Thread.sleep(200);
            cache.put(2, "world");
            Thread.sleep(200);
            cache.remove(1);
            Assertions.assertThat(cache.values(Collectors.toList()))
                      .containsOnly("world");
            Assertions.assertThat(cache.keys(Collectors.toList()))
                      .containsOnly(2);
        }
    }

    @Test
    public void onExpirationStressNoKeyOverlap() throws InterruptedException {
        ConcurrentLinkedQueue<String> expired = new ConcurrentLinkedQueue<>();
        Consumer<String> onExpiration = expired::add;
        Random random = new Random();
        int size = 5_000_000;
        List<Integer> integers = Stream.iterate(0, x -> x + 1).limit(size).collect(Collectors.toList());
        List<String> strings = Stream.iterate(0, x -> x + 1).limit(size)
                                     .map(i -> Integer.toUnsignedString(i, 10 + 26))
                                     .collect(Collectors.toList());
        Utils.shuffle(random, integers);
        Utils.shuffle(random, strings);
        try (ConstantExpirationCache<Integer, String> cache = new ConstantExpirationCache<>(Duration.ofNanos(500_000L), Executors.newSingleThreadScheduledExecutor(), onExpiration)) {
            Instant before = Instant.now();
            for (int i = 0; i < integers.size() && i < strings.size(); i++) {
                cache.put(integers.get(i), strings.get(i));
            }
            System.out.printf("insertion of %d took %s%n",
                              size,
                              Utils.instantDifference(Instant.now(), before));
            Thread.sleep(Duration.ofNanos(5_000_000L));
            ArrayList<String> expiredAsArrayList = new ArrayList<>(expired);
            for (int i = 0; i < expiredAsArrayList.size() && i < strings.size(); i++) {
                Assertions.assertThat(expiredAsArrayList.get(i))
                          .describedAs("index %d", i)
                          .isEqualTo(strings.get(i));
            }
            Assertions.assertThat(cache.values(Collectors.toList())).withRepresentation(Object::toString).isEmpty();
            Assertions.assertThat(expired).withRepresentation(Object::toString).hasSize(size);
        }
    }

    @Test
    public void onExpirationStressAsync() throws InterruptedException {
        ConcurrentLinkedQueue<String> expired = new ConcurrentLinkedQueue<>();
        Consumer<String> onExpiration = expired::add;
        Random random = new Random();
        int size = 1_000_000;
        List<Integer> integers = Stream.iterate(0, x -> x + 1).limit(size).collect(Collectors.toList());
        List<String> strings = Stream.iterate(0, x -> x + 1).limit(size)
                                     .map(i -> Integer.toUnsignedString(i, 10 + 26))
                                     .collect(Collectors.toList());
        HashSet<String> stringsAsSet = new HashSet<>(strings);
        Utils.shuffle(random, integers);
        Utils.shuffle(random, strings);
        try (ConstantExpirationCache<Integer, String> cache = new ConstantExpirationCache<>(Duration.ofNanos(500_000L), Executors.newSingleThreadScheduledExecutor(), onExpiration)) {
            try (ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors())) {
                CountDownLatch count = new CountDownLatch(size);
                Instant before = Instant.now();
                for (int i = 0; i < integers.size() && i < strings.size(); i++) {
                    int finalI = i;
                    scheduler.schedule(() -> {
                        cache.put(integers.get(finalI), strings.get(finalI));
                        count.countDown();
                    }, random.nextLong(5_000_000_000L), TimeUnit.NANOSECONDS);
                }
                count.await();
                System.out.printf("insertion of %d took %s%n",
                                  size,
                                  Utils.instantDifference(Instant.now(), before));
            }
            Thread.sleep(Duration.ofNanos(500_000L));
            ArrayList<String> expiredAsArrayList = new ArrayList<>(expired);
            for (int i = 0; i < expiredAsArrayList.size() && i < strings.size(); i++) {
                String expiredElement = expiredAsArrayList.get(i);
                if (!stringsAsSet.contains(expiredElement)) {
                    throw new AssertionError("expired[%d] = %s is unrecognized".formatted(i, expiredElement));
                }
            }
            Assertions.assertThat(expiredAsArrayList).describedAs("not all are expired").hasSize(size);
            Assertions.assertThat(cache.values(Collectors.toList())).isEmpty();
        }
    }
}