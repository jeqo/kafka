package org.apache.kafka.streams.internals;

import org.apache.kafka.common.InvalidRecordException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.utils.ByteUtils;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.ValueAndHeaders;

import java.nio.ByteBuffer;

public class ValueAndHeadersSerde<V> implements Serde<ValueAndHeaders<V>> {

    private final Serde<V> valueSerde;

    public ValueAndHeadersSerde(Serde<V> valueSerde) {
        this.valueSerde = valueSerde;
    }

    @Override
    public Serializer<ValueAndHeaders<V>> serializer() {
        return new ValueAndHeadersSerializer<>(valueSerde.serializer());
    }

    @Override
    public Deserializer<ValueAndHeaders<V>> deserializer() {
        return null;
    }

    public static class ValueAndHeadersSerializer<V> implements Serializer<ValueAndHeaders<V>> {

        private final Serializer<V> valueSerializer;

        public ValueAndHeadersSerializer(Serializer<V> valueSerializer) {
            this.valueSerializer = valueSerializer;
        }

        @Override
        public byte[] serialize(String topic, ValueAndHeaders<V> data) {
            ByteBuffer buffer = ByteBuffer.allocate(0);

            Header[] headers = data.headers().toArray();
            ByteUtils.writeVarint(headers.length, buffer);

            for (Header header : headers) {
                String headerKey = header.key();
                if (headerKey == null)
                    throw new IllegalArgumentException("Invalid null header key found in headers");

                byte[] utf8Bytes = Utils.utf8(headerKey);
                ByteUtils.writeVarint(utf8Bytes.length, buffer);
                buffer.put(utf8Bytes);

                byte[] headerValue = header.value();
                if (headerValue == null) {
                    ByteUtils.writeVarint(-1, buffer);
                } else {
                    ByteUtils.writeVarint(headerValue.length, buffer);
                    buffer.put(headerValue);
                }
            }

            buffer.put(valueSerializer.serialize(topic, data.value()));

            return buffer.array();
        }
    }

    public static class ValueAndHeadersDeserializer<V> implements Deserializer<ValueAndHeaders<V>> {

        private final Deserializer<V> valueDeserializer;

        public ValueAndHeadersDeserializer(Deserializer<V> valueDeserializer) {
            this.valueDeserializer = valueDeserializer;
        }

        @Override
        public ValueAndHeaders<V> deserialize(String topic, byte[] data) {
            ByteBuffer buffer = ByteBuffer.wrap(data);

            int numHeaders = ByteUtils.readVarint(buffer);
            if (numHeaders < 0)
                throw new InvalidRecordException("Found invalid number of record headers " + numHeaders);

            final Header[] headers;
            if (numHeaders == 0)
                headers = Record.EMPTY_HEADERS;
            else
                headers = readHeaders(buffer, numHeaders);

            ByteBuffer valueBuffer = ByteBuffer.allocate(data.length - buffer.position())
                    .put(data, buffer.position(), data.length);
            V value = valueDeserializer.deserialize(topic, valueBuffer.array());

            return new ValueAndHeaders<>(value, new RecordHeaders(headers));
        }

        private static Header[] readHeaders(ByteBuffer buffer, int numHeaders) {
            Header[] headers = new Header[numHeaders];
            for (int i = 0; i < numHeaders; i++) {
                int headerKeySize = ByteUtils.readVarint(buffer);
                if (headerKeySize < 0)
                    throw new InvalidRecordException("Invalid negative header key size " + headerKeySize);

                String headerKey = Utils.utf8(buffer, headerKeySize);
                buffer.position(buffer.position() + headerKeySize);

                ByteBuffer headerValue = null;
                int headerValueSize = ByteUtils.readVarint(buffer);
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
}
