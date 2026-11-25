package dev.dataclean.run;

import dev.dataclean.engine.DatasetValidator.InvalidDataset;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "dataclean.worker.enabled", havingValue = "true", matchIfMissing = true)
class RunDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(RunDispatcher.class);
    private final DataSource dataSource;
    private final RunStore runs;
    private final JobOperator operator;
    private final Job validateCsvJob;

    RunDispatcher(DataSource dataSource, RunStore runs, JobOperator operator, Job validateCsvJob) {
        this.dataSource = dataSource;
        this.runs = runs;
        this.operator = operator;
        this.validateCsvJob = validateCsvJob;
    }

    @Scheduled(fixedDelayString = "${dataclean.poll-ms}")
    void dispatch() throws SQLException {
        for (UUID id : runs.pending()) {
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    // This independent connection keeps the claim until batch processing has finished.
                    if (claim(connection, id) && runs.markRunning(id)) {
                        execute(id);
                        return;
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    private boolean claim(Connection connection, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))")) {
            statement.setString(1, id.toString());
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void execute(UUID id) {
        try {
            var parameters = new JobParametersBuilder()
                    .addString("runId", id.toString())
                    .addString("attempt", UUID.randomUUID().toString())
                    .toJobParameters();
            var execution = operator.start(validateCsvJob, parameters);
            if (execution.getStatus() == BatchStatus.COMPLETED) return;
            String error = execution.getAllFailureExceptions().stream()
                    .filter(InvalidDataset.class::isInstance).map(Throwable::getMessage).findFirst()
                    .orElse("Validation could not be completed");
            runs.fail(id, error);
            LOG.warn("Validation run {} finished with batch status {}", id, execution.getStatus());
        } catch (Exception e) {
            LOG.error("Unable to launch validation run {}", id, e);
        }
    }
}
