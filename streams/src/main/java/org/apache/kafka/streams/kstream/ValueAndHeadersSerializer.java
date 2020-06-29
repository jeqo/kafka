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

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.utils.ByteUtils;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.ValueAndHeaders;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class ValueAndHeadersSerializer<V> implements Serializer<ValueAndHeaders<V>> {

    private final Serializer<V> valueSerializer;

    public ValueAndHeadersSerializer(final Serializer<V> valueSerializer) {
        this.valueSerializer = valueSerializer;
    }

    @Override
    public byte[] serialize(final String topic, final ValueAndHeaders<V> data) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final DataOutputStream buffer = new DataOutputStream(baos);

        final byte[] valueSerialized = valueSerializer.serialize(topic, data.value());
        final Header[] headers = data.headers().toArray();

        try {
            ByteUtils.writeVarint(headers.length, buffer);

            for (final Header header : headers) {
                final String headerKey = header.key();
                if (headerKey == null)
                    throw new IllegalArgumentException("Invalid null header key found in headers");

                final byte[] utf8Bytes = Utils.utf8(headerKey);
                ByteUtils.writeVarint(utf8Bytes.length, buffer);
                buffer.write(utf8Bytes);

                final byte[] headerValue = header.value();
                if (headerValue == null) {
                    ByteUtils.writeVarint(-1, buffer);
                } else {
                    ByteUtils.writeVarint(headerValue.length, buffer);
                    buffer.write(headerValue);
                }
            }

            buffer.write(valueSerialized);
            buffer.flush();
            return baos.toByteArray();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }
}
