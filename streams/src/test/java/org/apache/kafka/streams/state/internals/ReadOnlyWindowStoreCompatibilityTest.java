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

import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertNotNull;

public class ReadOnlyWindowStoreCompatibilityTest {
    @Test
    public void testReadOnlyWindowStoreWithoutBackwardOps() {
        final ReadOnlyWindowStore<Bytes, Bytes> store = new ReadOnlyWindowStore<Bytes, Bytes>() {
            @Override
            public Bytes fetch(final Bytes key, final long time) {
                return null;
            }

            @Override
            @Deprecated
            public WindowStoreIterator<Bytes> fetch(final Bytes key, final long timeFrom, final long timeTo) {
                return null;
            }

            @Override
            public WindowStoreIterator<Bytes> fetch(final Bytes key, final Instant timeFrom, final Instant timeTo) throws IllegalArgumentException {
                return null;
            }

            @Override
            @Deprecated
            public KeyValueIterator<Windowed<Bytes>, Bytes> fetch(final Bytes keyFrom, final Bytes keyTo, final long timeFrom, final long timeTo) {
                return null;
            }

            @Override
            public KeyValueIterator<Windowed<Bytes>, Bytes> fetch(final Bytes keyFrom, final Bytes keyTo, final Instant timeFrom, final Instant timeTo) throws IllegalArgumentException {
                return null;
            }

            @Override
            public KeyValueIterator<Windowed<Bytes>, Bytes> all() {
                return null;
            }

            @Override
            @Deprecated
            public KeyValueIterator<Windowed<Bytes>, Bytes> fetchAll(final long timeFrom, final long timeTo) {
                return null;
            }

            @Override
            public KeyValueIterator<Windowed<Bytes>, Bytes> fetchAll(final Instant timeFrom, final Instant timeTo) throws IllegalArgumentException {
                return null;
            }
        };
        assertNotNull(store);
    }
}
