package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.streams.ValueAndHeaders;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.ProcessorSupplier;

public class KStreamWithHeaders<K, V> implements ProcessorSupplier<K, V> {

    @Override
    public Processor<K, V> get() {
        return new KStreamWithHeadersProcessor<>();
    }

    private static class KStreamWithHeadersProcessor<K, V> implements Processor<K, V> {
        private ProcessorContext context;

        @Override
        public void init(final ProcessorContext context) {
            this.context = context;
        }

        @Override
        public void process(K key, V value) {
            context.forward(key, new ValueAndHeaders<>(value, context.headers()));
        }

        @Override
        public void close() {
        }
    }
}
