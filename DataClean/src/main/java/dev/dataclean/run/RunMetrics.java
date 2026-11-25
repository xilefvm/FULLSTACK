package dev.dataclean.run;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class RunMetrics {
    RunMetrics(MeterRegistry registry, RunStore runs) {
        for (var state : new RunStore.State[]{RunStore.State.QUEUED, RunStore.State.RUNNING}) {
            Gauge.builder("dataclean.queue.runs", runs, source -> source.countByState(state))
                    .tag("state", state.name())
                    .register(registry);
        }
        Gauge.builder("dataclean.queue.oldest.seconds", runs, RunStore::oldestQueuedSeconds)
                .tag("state", "QUEUED").register(registry);
        Gauge.builder("dataclean.queue.oldest.seconds", runs, RunStore::oldestRunningSeconds)
                .tag("state", "RUNNING").register(registry);
    }
}
