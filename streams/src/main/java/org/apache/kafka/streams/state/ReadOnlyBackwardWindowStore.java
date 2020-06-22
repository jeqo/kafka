package org.apache.kafka.streams.state;

import org.apache.kafka.streams.kstream.Windowed;

import java.time.Instant;

public interface ReadOnlyBackwardWindowStore<K, V> {
    @Deprecated
    WindowStoreIterator<V> backwardFetch(K key, long timeFrom, long timeTo);
    WindowStoreIterator<V> backwardFetch(K key, Instant from, Instant to) throws IllegalArgumentException;
    @Deprecated
    KeyValueIterator<Windowed<K>, V> backwardFetch(K from, K to, long timeFrom, long timeTo);
    KeyValueIterator<Windowed<K>, V> backwardFetch(K from, K to, Instant fromTime, Instant toTime)
            throws IllegalArgumentException;
    KeyValueIterator<Windowed<K>, V> backwardAll();
    @Deprecated
    KeyValueIterator<Windowed<K>, V> backwardFetchAll(long timeFrom, long timeTo);
    KeyValueIterator<Windowed<K>, V> backwardFetchAll(Instant from, Instant to) throws IllegalArgumentException;
}
