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

        ByteBuffer valueBuffer = ByteBuffer
                .allocate(data.length - buffer.position())
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
