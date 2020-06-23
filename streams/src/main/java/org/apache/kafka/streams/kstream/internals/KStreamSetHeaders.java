package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.streams.kstream.SetHeadersAction;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.ProcessorSupplier;

public class KStreamSetHeaders<K, V> implements ProcessorSupplier<K, V> {
    private final SetHeadersAction<K, V> action;

    public KStreamSetHeaders(SetHeadersAction<K, V> action) {
        this.action = action;
    }

    @Override
    public Processor<K, V> get() {
        return new KStreamWithHeadersProcessor<>(action);
    }

    private static class KStreamWithHeadersProcessor<K, V> implements Processor<K, V> {
        private final SetHeadersAction<K, V> action;

        private ProcessorContext context;

        public KStreamWithHeadersProcessor(final SetHeadersAction<K, V> action) {
            this.action = action;
        }

        @Override
        public void init(final ProcessorContext context) {
            this.context = context;
        }

        @Override
        public void process(K key, V value) {
            action.apply(key, value).forEach(header -> context.headers().add(header));
            context.forward(key, value);
        }

        @Override
        public void close() {
        }
    }
}
