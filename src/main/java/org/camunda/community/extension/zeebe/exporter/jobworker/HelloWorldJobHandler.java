package org.camunda.community.extension.zeebe.exporter.jobworker;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import java.util.Map;
import java.util.logging.Logger;

final class HelloWorldJobHandler implements JobHandler {
  private static final Logger LOGGER = Logger.getLogger(HelloWorldJobHandler.class.getName());

  @Override
  public void handle(final JobClient jobClient, final ActivatedJob job) {
    final Object name = job.getVariable("name");
    if (name == null) {
      jobClient
          .newFailCommand(job)
          .retries(0)
          .errorMessage("Missing required scoped variable 'name'")
          .send()
          .whenComplete(
              (ignored, error) -> {
                if (error != null) {
                  LOGGER.warning(
                      () ->
                          "Fail command for helloWorld job " + job.getKey() + " failed: " + error);
                }
              });
      return;
    }

    final String greeting = "Hello " + name + "!";
    LOGGER.info(() -> "Greeting built by embedded worker: " + greeting);
    jobClient
        .newCompleteCommand(job)
        .variables(Map.of("greeting", greeting))
        .send()
        .whenComplete(
            (ignored, error) -> {
              if (error != null) {
                LOGGER.warning(
                    () ->
                        "Complete command for helloWorld job "
                            + job.getKey()
                            + " failed: "
                            + error);
              }
            });
  }
}
