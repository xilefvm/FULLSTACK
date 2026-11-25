package dev.dataclean.run;

import dev.dataclean.engine.DatasetValidator;
import dev.dataclean.engine.DatasetValidator.Issue;
import dev.dataclean.engine.DatasetValidator.ValidatedRecord;
import dev.dataclean.schema.SchemaStore;

import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "dataclean.worker.enabled", havingValue = "true", matchIfMissing = true)
class ValidationBatch {
    private static final int WRITE_BATCH_SIZE = 500;

    @Bean
    Job validateCsvJob(JobRepository repository, Step validateCsvStep) {
        return new JobBuilder("validateCsv", repository).start(validateCsvStep).build();
    }

    @Bean
    Step validateCsvStep(JobRepository repository, PlatformTransactionManager transactions,
                         SchemaStore schemas, RunStore runs, DatasetValidator validator) {
        return new StepBuilder("validateRecords", repository)
                .tasklet((contribution, context) -> {
                    UUID id = UUID.fromString((String) context.getStepContext().getJobParameters().get("runId"));
                    var input = runs.lockInput(id).orElse(null);
                    if (input == null) return RepeatStatus.FINISHED;
                    var schema = schemas.find(input.schemaName(), input.schemaVersion()).orElseThrow();
                    var buffer = new ArrayList<Issue>(WRITE_BATCH_SIZE);
                    var records = new ArrayList<ValidatedRecord>(WRITE_BATCH_SIZE);
                    Consumer<ValidatedRecord> retain = input.retainRecords() ? record -> {
                        records.add(record);
                        if (records.size() == WRITE_BATCH_SIZE) {
                            runs.insertRecords(id, records);
                            records.clear();
                        }
                    } : null;
                    var summary = validator.validate(input.payload(), input.format(), input.sheet(), schema, issue -> {
                        buffer.add(issue);
                        if (buffer.size() == WRITE_BATCH_SIZE) {
                            runs.insertIssues(id, buffer);
                            buffer.clear();
                        }
                    }, retain);
                    if (!buffer.isEmpty()) runs.insertIssues(id, buffer);
                    if (!records.isEmpty()) runs.insertRecords(id, records);
                    runs.complete(id, summary);
                    return RepeatStatus.FINISHED;
                }, transactions).build();
    }
}
