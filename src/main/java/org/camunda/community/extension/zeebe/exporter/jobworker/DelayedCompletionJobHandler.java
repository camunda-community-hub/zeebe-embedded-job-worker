package org.camunda.community.extension.zeebe.exporter.jobworker;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import io.camunda.zeebe.exporter.api.context.Controller;
import java.time.Duration;
import java.util.Map;
import java.util.function.LongConsumer;

final class DelayedCompletionJobHandler implements JobHandler {
  private final Controller controller;
  private final long completionDelayMs;
  private final String outputVariableName;
  private final LongConsumer removeScopeVariables;

  DelayedCompletionJobHandler(
      final Controller controller,
      final long completionDelayMs,
      final String outputVariableName,
      final LongConsumer removeScopeVariables) {
    this.controller = controller;
    this.completionDelayMs = completionDelayMs;
    this.outputVariableName = outputVariableName;
    this.removeScopeVariables = removeScopeVariables;
  }

  @Override
  public void handle(final JobClient jobClient, final ActivatedJob job) {
    final Map<String, Object> outputVariables = Map.of(outputVariableName, true);
    controller.scheduleCancellableTask(
        Duration.ofMillis(completionDelayMs),
        () ->
            jobClient
                .newCompleteCommand(job)
                .variables(outputVariables)
                .send()
                .whenComplete(
                    (ignored, error) -> {
                      if (error == null) {
                        removeScopeVariables.accept(job.getElementInstanceKey());
                      }
                    }));
  }
}
