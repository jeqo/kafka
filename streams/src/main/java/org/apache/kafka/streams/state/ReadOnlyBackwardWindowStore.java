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
