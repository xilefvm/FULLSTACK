package dev.dataclean.run;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dataclean.worker.enabled", havingValue = "true", matchIfMissing = true)
class RecordRetention {
    private final RunStore runs;

    RecordRetention(RunStore runs) {
        this.runs = runs;
    }

    @Scheduled(fixedDelayString = "${dataclean.retention-poll-ms:3600000}")
    void purge() {
        runs.purgeExpiredRecords();
    }
}
