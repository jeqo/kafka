package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.streams.ValueAndHeaders;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.ProcessorSupplier;

public class KStreamSetHeaders<K, V> implements ProcessorSupplier<K, V> {

    final Iterable<Header> headers;

    public KStreamSetHeaders(final Iterable<Header> headers) {
        this.headers = headers;
    }

    @Override
    public Processor<K, V> get() {
        return new KStreamWithHeadersProcessor<>(headers);
    }

    private static class KStreamWithHeadersProcessor<K, V> implements Processor<K, V> {
        private final Iterable<Header> headers;

        private ProcessorContext context;

        public KStreamWithHeadersProcessor(final Iterable<Header> headers) {
            this.headers = headers;
        }

        @Override
        public void init(final ProcessorContext context) {
            this.context = context;
        }

        @Override
        public void process(K key, V value) {
            headers.forEach(header -> context.headers().add(header));
            context.forward(key, value);
        }

        @Override
        public void close() {
        }
    }
}
