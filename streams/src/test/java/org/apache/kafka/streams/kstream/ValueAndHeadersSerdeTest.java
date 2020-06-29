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
package org.apache.kafka.streams.kstream;

import static org.junit.Assert.assertEquals;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.ValueAndHeaders;
import org.junit.Test;

public class ValueAndHeadersSerdeTest {

    @Test
    public void shouldSerDe_whenHeadersEmpty() {
        final ValueAndHeaders<String> valueAndHeaders = new ValueAndHeaders<>("test", new RecordHeaders());

        final ValueAndHeadersSerde<String> serde = new ValueAndHeadersSerde<>(Serdes.String());

        final byte[] bytes = serde.serializer().serialize(null, valueAndHeaders);
        final ValueAndHeaders<String> value = serde.deserializer().deserialize(null, bytes);

        assertEquals("test", value.value());
    }

    @Test
    public void shouldSerDe_whenHeadersAvailable() {
        final Headers headers = new RecordHeaders();
        headers.add("k1", "v0".getBytes());
        headers.add("k1", "v1".getBytes());
        final ValueAndHeaders<String> valueAndHeaders = new ValueAndHeaders<>("test", headers);

        final ValueAndHeadersSerde<String> serde = new ValueAndHeadersSerde<>(Serdes.String());

        final byte[] bytes = serde.serializer().serialize(null, valueAndHeaders);
        final ValueAndHeaders<String> value = serde.deserializer().deserialize(null, bytes);

        assertEquals("test", value.value());
        assertEquals(headers, value.headers());
    }
}
