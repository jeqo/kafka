package org.apache.kafka.streams.state;

public interface ReadOnlySessionStoreWithBackwardIteration<K, AGG>
        extends ReadOnlySessionStore<K, AGG>, ReadOnlyBackwardSessionStore<K, AGG> {
}
