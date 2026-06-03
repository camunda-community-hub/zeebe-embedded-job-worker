package org.camunda.community.extension.zeebe.exporter.jobworker;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import io.camunda.zeebe.exporter.api.context.Controller;
import java.time.Duration;

final class DelayedCompletionJobHandler implements JobHandler {
  private final Controller controller;
  private final long completionDelayMs;

  DelayedCompletionJobHandler(final Controller controller, final long completionDelayMs) {
    this.controller = controller;
    this.completionDelayMs = completionDelayMs;
  }

  @Override
  public void handle(final JobClient jobClient, final ActivatedJob job) {
    controller.scheduleCancellableTask(
        Duration.ofMillis(completionDelayMs), () -> jobClient.newCompleteCommand(job).send());
  }
}
