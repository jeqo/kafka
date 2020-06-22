package org.apache.kafka.streams.state;

import org.apache.kafka.streams.kstream.Windowed;

public interface ReadOnlyBackwardSessionStore<K, AGG> {
    KeyValueIterator<Windowed<K>, AGG> backwardFetch(final K from, final K to);
    KeyValueIterator<Windowed<K>, AGG> backwardFindSessions(final K key, final long earliestSessionEndTime, final long latestSessionStartTime);
    KeyValueIterator<Windowed<K>, AGG> backwardFindSessions(final K keyFrom, final K keyTo, final long earliestSessionEndTime, final long latestSessionStartTime);
}
