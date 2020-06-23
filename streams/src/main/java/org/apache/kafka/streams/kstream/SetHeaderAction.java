package org.apache.kafka.streams.kstream;

import org.apache.kafka.common.header.Header;

public interface SetHeaderAction<K, V> {

    Header apply(final K key, final V value);

}
