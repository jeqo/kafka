package org.apache.kafka.streams.state;

public interface SessionStoreWithBackwardIteration<K, AGG>
        extends SessionStore<K, AGG>, ReadOnlySessionStoreWithBackwardIteration<K, AGG> {
}
