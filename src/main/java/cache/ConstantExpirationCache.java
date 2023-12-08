package cache;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Collector;

import static cache.DoubleLinkedNode.newestNode;
import static cache.DoubleLinkedNode.onlyNode;

public class ConstantExpirationCache<Key, Value> {
    private final Map<Key, DoubleLinkedNode<TimestampedValue<Value>>> map;
    private DoubleLinkedNode<TimestampedValue<Value>> newest;
    private DoubleLinkedNode<TimestampedValue<Value>> oldest;
    private final Duration expiration;
    private final ScheduledExecutorService scheduler;
    private final Consumer<Value> onExpiration;
    private final ReadWriteLock lock;

    public ConstantExpirationCache(Duration expiration, Consumer<Value> onExpiration) {
        this.expiration = expiration;
        this.onExpiration = onExpiration;
        map = new HashMap<>();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        lock = new ReentrantReadWriteLock();
    }

    /**
     * O(1)
     */
    public Optional<Value> get(Key key) {
        try {
            lock.readLock().lock();
            return Optional.ofNullable(map.get(key))
                           .map(DoubleLinkedNode::getValue)
                           .map(TimestampedValue::value);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * O(1)
     */
    public void put(Key key, Value value) {
        try {
            lock.writeLock().lock();
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            boolean wasEmpty = isEmpty();
            DoubleLinkedNode<TimestampedValue<Value>> link = map.get(key);
            unlink(link);
            if (newest == null) {
                oldest = newest = onlyNode(new TimestampedValue<>(value));
            } else {
                newest = newestNode(newest, new TimestampedValue<>(value));
                newest.getOlder().setNewer(newest);
            }
            map.put(key, newest);
            if (wasEmpty) {
                scheduleRecursiveCleaning();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void scheduleRecursiveCleaning() {
        Duration tillNextExpiration = instantDifference(oldest.getValue().timestamp().plus(expiration),
                                                        Instant.now());
        scheduler.schedule(() -> {
            try {
                lock.writeLock().lock();
                while (oldest.getValue().timestamp()
                             .plus(expiration)
                             .isBefore(Instant.now())) {
                    Value expiredValue = oldest.getValue().value();
                    unlink(oldest);
                    scheduler.execute(() -> onExpiration.accept(expiredValue));
                }
                if (oldest != null) {
                    scheduleRecursiveCleaning();
                }
            } finally {
                lock.writeLock().unlock();
            }
        }, tillNextExpiration.toNanos(), TimeUnit.NANOSECONDS);
    }

    public Duration instantDifference(Instant from, Instant to) {
        return Duration.of(from.getEpochSecond(), ChronoUnit.SECONDS)
                       .plus(from.getNano(), ChronoUnit.NANOS)
                       .minus(to.getEpochSecond(), ChronoUnit.SECONDS)
                       .minus(to.getNano(), ChronoUnit.NANOS);
    }

    /**
     * O(1)
     */
    public boolean isEmpty() {
        return newest == null;
    }

    /**
     * O(1)
     */
    public void remove(Key key) {
        try {
            lock.writeLock().lock();
            DoubleLinkedNode<TimestampedValue<Value>> link = map.get(key);
            unlink(link);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void unlink(DoubleLinkedNode<TimestampedValue<Value>> link) {
        if (link == null) {
            return;
        }
        if (link == oldest && oldest == newest) {
            oldest = newest = null;
        } else if (link == oldest) {
            oldest = oldest.getNewer();
            oldest.setOlder(null);
        } else if (link == newest) {
            newest = newest.getOlder();
            newest.setNewer(null);
        } else {
            link.getOlder().setNewer(link.getNewer());
            link.getNewer().setOlder(link.getOlder());
        }
    }

    /**
     * o(size())
     */
    public <Values> Values values(Collector<Value, ?, Values> valuesCollector) {
        try {
            lock.readLock().lock();
            return map.values()
                      .stream()
                      .map(DoubleLinkedNode::getValue)
                      .map(TimestampedValue::value)
                      .collect(valuesCollector);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * o(size())
     */
    public <Keys> Keys keys(Collector<Key, ?, Keys> keysCollector) {
        try {
            lock.readLock().lock();
            return map.keySet().stream().collect(keysCollector);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * O(1)
     */
    public int size() {
        return map.size();
    }
}