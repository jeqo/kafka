package org.apache.kafka.streams.state;

public interface KeyValueStoreWithReverseIteration<K, V>
    extends KeyValueStore<K, V>, ReadOnlyKeyValueStoreWithReverseIteration<K, V> {
}
