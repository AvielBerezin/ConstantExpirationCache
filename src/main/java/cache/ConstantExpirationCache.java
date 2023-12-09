package cache;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Collector;

import static cache.DoubleLinkedNode.newestNode;
import static cache.DoubleLinkedNode.onlyNode;

public class ConstantExpirationCache<Key, Value> implements AutoCloseable {
    private final Map<Key, DoubleLinkedNode<TimestampedValue<Key, Value>>> map;
    private DoubleLinkedNode<TimestampedValue<Key, Value>> newest;
    private DoubleLinkedNode<TimestampedValue<Key, Value>> oldest;
    private final Duration expiration;
    private final ScheduledExecutorService cleanerScheduler;
    private final Consumer<Value> onExpiration;
    private final ReadWriteLock lock;
    private final ScheduledExecutorService onExpirationTaskScheduler;
    private boolean closed;

    public ConstantExpirationCache(Duration expiration,
                                   ScheduledExecutorService onExpirationTaskScheduler,
                                   Consumer<Value> onExpiration) {
        this.expiration = expiration;
        this.onExpiration = onExpiration;
        map = new HashMap<>();
        ThreadFactory threadFactory = Executors.defaultThreadFactory();
        cleanerScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = threadFactory.newThread(r);
            thread.setDaemon(true);
            return thread;
        });
        lock = new ReentrantReadWriteLock();
        this.onExpirationTaskScheduler = onExpirationTaskScheduler;
        closed = false;
    }

    /**
     * O(1)
     */
    public Optional<Value> get(Key key) {
        try {
            lock.readLock().lock();
            if (closed) {
                throw new IllegalStateException("cache has been closed");
            }
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
            if (closed) {
                throw new IllegalStateException("cache has been closed");
            }
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            boolean wasEmpty = isEmpty();
            DoubleLinkedNode<TimestampedValue<Key, Value>> link = map.get(key);
            unlink(link);
            if (newest == null) {
                oldest = newest = onlyNode(new TimestampedValue<>(key, value));
            } else {
                newest = newestNode(newest, new TimestampedValue<>(key, value));
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
        Duration tillNextExpiration = Utils.instantDifference(oldest.getValue().timestamp().plus(expiration),
                                                              Instant.now());
        cleanerScheduler.schedule(() -> {
            try {
                lock.writeLock().lock();
                while (oldest.getValue().timestamp()
                             .plus(expiration)
                             .isBefore(Instant.now())) {
                    Key key = oldest.getValue().key;
                    Value expiredValue = oldest.getValue().value();
                    unlink(oldest);
                    map.remove(key);
                    onExpirationTaskScheduler.execute(() -> onExpiration.accept(expiredValue));
                }
                if (oldest != null) {
                    scheduleRecursiveCleaning();
                }
            } finally {
                lock.writeLock().unlock();
            }
        }, tillNextExpiration.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * O(1)
     */
    public boolean isEmpty() {
        try {
            lock.readLock().lock();
            if (closed) {
                throw new IllegalStateException("cache has been closed");
            }
            return newest == null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * O(1)
     */
    public void remove(Key key) {
        try {
            lock.writeLock().lock();
            if (closed) {
                throw new IllegalStateException("cache has been closed");
            }
            Objects.requireNonNull(key, "key");
            DoubleLinkedNode<TimestampedValue<Key, Value>> link = map.get(key);
            unlink(link);
            map.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void unlink(DoubleLinkedNode<TimestampedValue<Key, Value>> link) {
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
            if (closed) {
                throw new IllegalStateException("cache has been closed");
            }
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
            if (closed) {
                throw new IllegalStateException("cache has been closed");
            }
            return map.keySet().stream().collect(keysCollector);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * O(1)
     */
    public int size() {
        try {
            lock.readLock().lock();
            return map.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        try {
            lock.writeLock().lock();
            if (closed) {
                return;
            }
            cleanerScheduler.shutdownNow();
            onExpirationTaskScheduler.shutdownNow();
            map.clear();
            newest = oldest = null;
            closed = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private record TimestampedValue<Key, Value>(Instant timestamp, Key key, Value value) {
        public TimestampedValue(Key key, Value value) {
            this(Instant.now(), key, value);
        }
    }
}
