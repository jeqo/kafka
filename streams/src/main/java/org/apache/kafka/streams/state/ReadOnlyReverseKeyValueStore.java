package org.apache.kafka.streams.state;

public interface ReadOnlyReverseKeyValueStore<K, V> {
    KeyValueIterator<K, V> reverseRange(K from, K to);
    KeyValueIterator<K, V> reverseAll();
}
