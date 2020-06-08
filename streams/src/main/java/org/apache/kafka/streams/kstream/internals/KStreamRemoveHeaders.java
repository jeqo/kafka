package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.ProcessorSupplier;

public class KStreamRemoveHeaders<K, V> implements ProcessorSupplier<K, V> {

    final Iterable<String> headerKeys;

    public KStreamRemoveHeaders(final Iterable<String> headerKeys) {
        this.headerKeys = headerKeys;
    }

    @Override
    public Processor<K, V> get() {
        return new KStreamWithHeadersProcessor<>(headerKeys);
    }

    private static class KStreamWithHeadersProcessor<K, V> implements Processor<K, V> {
        private final Iterable<String> headerKeys;

        private ProcessorContext context;

        public KStreamWithHeadersProcessor(final Iterable<String> headerKeys) {
            this.headerKeys = headerKeys;
        }

        @Override
        public void init(final ProcessorContext context) {
            this.context = context;
        }

        @Override
        public void process(K key, V value) {
            headerKeys.forEach(header -> context.headers().remove(header));
            context.forward(key, value);
        }

        @Override
        public void close() {
        }
    }
}
