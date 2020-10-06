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
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class ReadOnlyKeyValueStoreCompatibilityTest {
    @Test
    public void testReadOnlyKeyValueStoreWithoutBackwardOps() {
        final ReadOnlyKeyValueStore<Bytes, Bytes> store = new ReadOnlyKeyValueStore<Bytes, Bytes>() {

            @Override
            public Bytes get(final Bytes key) {
                return null;
            }

            @Override
            public KeyValueIterator<Bytes, Bytes> range(final Bytes from, final Bytes to) {
                return null;
            }

            @Override
            public KeyValueIterator<Bytes, Bytes> all() {
                return null;
            }

            @Override
            public long approximateNumEntries() {
                return 0;
            }
        };
        assertNotNull(store);
    }
}
