package org.apache.kafka.streams;

import org.apache.kafka.common.header.Headers;

public class ValueAndHeaders<V> {
    final V value;
    final Headers headers;

    public ValueAndHeaders(V value, Headers headers) {
        this.value = value;
        this.headers = headers;
    }

    public Headers headers() {
        return headers;
    }

    public V value() {
        return value;
    }
}
