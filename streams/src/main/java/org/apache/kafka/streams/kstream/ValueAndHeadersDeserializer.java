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

import org.apache.kafka.common.InvalidRecordException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.utils.ByteUtils;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.ValueAndHeaders;

import java.nio.ByteBuffer;

public class ValueAndHeadersDeserializer<V> implements Deserializer<ValueAndHeaders<V>> {

    private final Deserializer<V> valueDeserializer;

    public ValueAndHeadersDeserializer(final Deserializer<V> valueDeserializer) {
        this.valueDeserializer = valueDeserializer;
    }

    @Override
    public ValueAndHeaders<V> deserialize(final String topic, final byte[] data) {
        final ByteBuffer buffer = ByteBuffer.wrap(data);

        final int numHeaders = ByteUtils.readVarint(buffer);
        if (numHeaders < 0)
            throw new InvalidRecordException("Found invalid number of record headers " + numHeaders);

        final Header[] headers;
        if (numHeaders == 0)
            headers = Record.EMPTY_HEADERS;
        else
            headers = readHeaders(buffer, numHeaders);

        final ByteBuffer valueBuffer = ByteBuffer
                .allocate(data.length - buffer.position())
                .put(data, buffer.position(), data.length - buffer.position());
        final V value = valueDeserializer.deserialize(topic, valueBuffer.array());

        return new ValueAndHeaders<>(value, new RecordHeaders(headers));
    }

    private static Header[] readHeaders(final ByteBuffer buffer, final int numHeaders) {
        final Header[] headers = new Header[numHeaders];
        for (int i = 0; i < numHeaders; i++) {
            final int headerKeySize = ByteUtils.readVarint(buffer);
            if (headerKeySize < 0)
                throw new InvalidRecordException("Invalid negative header key size " + headerKeySize);

            final String headerKey = Utils.utf8(buffer, headerKeySize);
            buffer.position(buffer.position() + headerKeySize);

            ByteBuffer headerValue = null;
            final int headerValueSize = ByteUtils.readVarint(buffer);
            if (headerValueSize >= 0) {
                headerValue = buffer.slice();
                headerValue.limit(headerValueSize);
                buffer.position(buffer.position() + headerValueSize);
            }

            headers[i] = new RecordHeader(headerKey, headerValue);
        }

        return headers;
    }
}
