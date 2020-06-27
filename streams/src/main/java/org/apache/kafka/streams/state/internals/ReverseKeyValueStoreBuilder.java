/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.state.internals;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.streams.state.KeyValueStoreWithReverseIteration;
import org.apache.kafka.streams.state.ReverseKeyValueBytesStoreSupplier;

import java.util.Objects;

public class ReverseKeyValueStoreBuilder<K, V> extends AbstractStoreBuilder<K, V, KeyValueStoreWithReverseIteration<K, V>> {

    private final ReverseKeyValueBytesStoreSupplier storeSupplier;

    public ReverseKeyValueStoreBuilder(final ReverseKeyValueBytesStoreSupplier storeSupplier,
                                       final Serde<K> keySerde,
                                       final Serde<V> valueSerde,
                                       final Time time) {
        super(storeSupplier.name(), keySerde, valueSerde, time);
        Objects.requireNonNull(storeSupplier, "bytesStoreSupplier can't be null");
        this.storeSupplier = storeSupplier;
    }

    @Override
    public KeyValueStoreWithReverseIteration<K, V> build() {
        return new MeteredReverseKeyValueStore<>(
            maybeWrapCaching(maybeWrapLogging(storeSupplier.get())),
            storeSupplier.metricsScope(),
            time,
            keySerde,
            valueSerde);
    }

    private KeyValueStoreWithReverseIteration<Bytes, byte[]> maybeWrapCaching(final KeyValueStoreWithReverseIteration<Bytes, byte[]> inner) {
        if (!enableCaching) {
            return inner;
        }
        return new CachingReverseKeyValueStore(inner);
    }

    private KeyValueStoreWithReverseIteration<Bytes, byte[]> maybeWrapLogging(final KeyValueStoreWithReverseIteration<Bytes, byte[]> inner) {
        if (!enableLogging) {
            return inner;
        }
        return new ChangeLoggingReverseKeyValueBytesStore(inner);
    }
}
