package cache;

import java.time.Instant;

public record TimestampedValue<Value>(Instant timestamp, Value value) {
    public TimestampedValue(Value value) {
        this(Instant.now(), value);
    }
}
