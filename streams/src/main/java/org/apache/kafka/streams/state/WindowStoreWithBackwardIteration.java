package org.apache.kafka.streams.state;

public interface WindowStoreWithBackwardIteration<K, V>
        extends WindowStore<K, V>, ReadOnlyWindowStoreWithBackwardIteration<K, V> {
}
