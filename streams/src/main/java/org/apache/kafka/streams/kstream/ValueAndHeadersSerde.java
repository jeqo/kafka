package org.apache.kafka.streams.kstream;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.ValueAndHeaders;

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
        return new ValueAndHeadersDeserializer<>(valueSerde.deserializer());
    }

}
