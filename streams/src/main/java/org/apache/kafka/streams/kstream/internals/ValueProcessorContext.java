package org.apache.kafka.streams.kstream.internals;

import java.io.File;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.StreamsMetrics;
import org.apache.kafka.streams.processor.Cancellable;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.Punctuator;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.api.RecordMetadata;

public class ValueProcessorContext<KForward, VForward> implements ProcessorContext<KForward, VForward> {

    final ProcessorContext<KForward, VForward> delegate;

    private KForward key;

    public void setRecordKey(KForward initialKey) {
        this.key = initialKey;
    }

    public void clearRecordKey() {
        this.key = null;
    }

    ValueProcessorContext(final ProcessorContext<KForward, VForward> delegate) {
        this.delegate = delegate;
    }

    @Override
    public String applicationId() {
        return delegate.applicationId();
    }

    @Override
    public TaskId taskId() {
        return delegate.taskId();
    }

    @Override
    public Optional<RecordMetadata> recordMetadata() {
        return delegate.recordMetadata();
    }

    @Override
    public Serde<?> keySerde() {
        return delegate.keySerde();
    }

    @Override
    public Serde<?> valueSerde() {
        return delegate.valueSerde();
    }

    @Override
    public File stateDir() {
        return delegate.stateDir();
    }

    @Override
    public StreamsMetrics metrics() {
        return delegate.metrics();
    }

    @Override
    public <S extends StateStore> S getStateStore(String name) {
        return delegate.getStateStore(name);
    }

    @Override
    public Cancellable schedule(Duration interval, PunctuationType type, Punctuator callback) {
        return schedule(interval, type, callback);
    }

    @Override
    public <K extends KForward, V extends VForward> void forward(Record<K, V> record) {
        if (key != null) {
            if (!record.key().equals(key)) {
                throw new IllegalArgumentException("Key has changed while processing and requires processing.");
            }
        }
        delegate.forward(record);
    }

    @Override
    public <K extends KForward, V extends VForward> void forward(Record<K, V> record, String childName) {
        if (key != null) {
            if (!record.key().equals(key)) {
                throw new IllegalArgumentException("Key has changed while processing and requires processing.");
            }
        }
        delegate.forward(record, childName);
    }

    @Override
    public void commit() {
        delegate.commit();
    }

    @Override
    public Map<String, Object> appConfigs() {
        return delegate.appConfigs();
    }

    @Override
    public Map<String, Object> appConfigsWithPrefix(String prefix) {
        return delegate.appConfigsWithPrefix(prefix);
    }
}
