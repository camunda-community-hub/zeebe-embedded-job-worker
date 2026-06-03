package org.camunda.community.extension.zeebe.exporter.jobworker;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import java.util.Map;
import java.util.logging.Logger;

final class HelloWorldJobHandler implements JobHandler {
  static final String INPUT_VARIABLE_NAME = "name";
  static final String OUTPUT_VARIABLE_NAME = "greeting";
  static final String MISSING_INPUT_VALUE_ERROR_MESSAGE = "Missing required scoped variable 'name'";

  private static final Logger LOGGER = Logger.getLogger(HelloWorldJobHandler.class.getName());

  @Override
  public void handle(final JobClient jobClient, final ActivatedJob job) {
    final Object inputVariable = job.getVariable(INPUT_VARIABLE_NAME);
    if (inputVariable == null) {
      jobClient
          .newFailCommand(job)
          .retries(0)
          .errorMessage(MISSING_INPUT_VALUE_ERROR_MESSAGE)
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

    final String greeting = "Hello " + inputVariable + "!";
    LOGGER.info(() -> "Greeting built by embedded worker: " + greeting);
    jobClient
        .newCompleteCommand(job)
        .variables(Map.of(OUTPUT_VARIABLE_NAME, greeting))
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
