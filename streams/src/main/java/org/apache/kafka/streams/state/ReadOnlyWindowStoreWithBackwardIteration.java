package org.apache.kafka.streams.state;

public interface ReadOnlyWindowStoreWithBackwardIteration<K, V>
        extends ReadOnlyWindowStore<K, V>, ReadOnlyBackwardWindowStore<K, V> {
}
