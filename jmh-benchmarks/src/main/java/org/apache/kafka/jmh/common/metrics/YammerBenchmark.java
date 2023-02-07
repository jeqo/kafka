package org.apache.kafka.jmh.common.metrics;

import com.yammer.metrics.core.Histogram;
import org.apache.kafka.server.metrics.KafkaYammerMetrics;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 5)
@Measurement(iterations = 15)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class YammerBenchmark {

    Histogram histogram = KafkaYammerMetrics.defaultRegistry().newHistogram(YammerBenchmark.class, "test-histogram");

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void recordHistogram() {
        histogram.update(Math.round(Math.random() * 100));
    }
}
