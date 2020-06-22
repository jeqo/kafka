package org.apache.kafka.streams.state;

public interface ReadOnlyKeyValueStoreWithReverseIteration<K, V>
        extends ReadOnlyKeyValueStore<K, V>, ReadOnlyReverseKeyValueStore<K, V> {
}
