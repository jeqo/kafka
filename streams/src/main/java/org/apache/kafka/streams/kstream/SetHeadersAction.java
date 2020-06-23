package org.apache.kafka.streams.kstream;

import org.apache.kafka.common.header.Header;

public interface SetHeadersAction<K, V> {

    Iterable<Header> apply(final K key, final V value);

}
