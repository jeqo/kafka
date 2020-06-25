package org.apache.kafka.streams.kstream;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.utils.ByteUtils;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.ValueAndHeaders;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class ValueAndHeadersSerializer<V> implements Serializer<ValueAndHeaders<V>> {

    private final Serializer<V> valueSerializer;

    public ValueAndHeadersSerializer(Serializer<V> valueSerializer) {
        this.valueSerializer = valueSerializer;
    }

    @Override
    public byte[] serialize(String topic, ValueAndHeaders<V> data) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream buffer = new DataOutputStream(baos);

        byte[] valueSerialized = valueSerializer.serialize(topic, data.value());
        Header[] headers = data.headers().toArray();

        try {

            ByteUtils.writeVarint(headers.length, buffer);

            for (Header header : headers) {
                String headerKey = header.key();
                if (headerKey == null)
                    throw new IllegalArgumentException("Invalid null header key found in headers");

                byte[] utf8Bytes = Utils.utf8(headerKey);
                ByteUtils.writeVarint(utf8Bytes.length, buffer);
                buffer.write(utf8Bytes);

                byte[] headerValue = header.value();
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
