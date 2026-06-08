package org.camunda.community.extension.zeebe.exporter.jobworker;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.JsonMapper;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.response.EvaluateDecisionResponse;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * A {@link JobHandler} that evaluates a DMN decision for each element of an input list in parallel,
 * then completes the job with the aggregated results.
 *
 * <p>The decision ID is read from a custom job header named {@value #HEADER_DECISION_ID}, making
 * the handler reusable across different BPMN processes simply by changing that header value.
 *
 * <p>Expected job variables (mapped via BPMN IO mapping):
 *
 * <ul>
 *   <li>{@value #INPUT_VARIABLE} – a {@code List<Map<String, Object>>} where each entry is passed
 *       as the variable context for one decision evaluation
 * </ul>
 *
 * <p>Variables written on job completion:
 *
 * <ul>
 *   <li>{@value #OUTPUT_VARIABLE} – a {@code List<Object>} containing the parsed decision output
 *       for each successfully evaluated input (in the same order as the input list)
 * </ul>
 *
 * <p>Each decision evaluation is retried up to {@value #MAX_RETRY_ATTEMPTS} times on transient
 * failures. Permanently failing evaluations are silently dropped from the results so that the rest
 * of the batch can still complete.
 */
final class ParallelMultiInstanceDecisionJobHandler implements JobHandler {

  static final String JOB_TYPE = "evaluateBatchDecisions";
  static final String HEADER_DECISION_ID = "decisionId";
  static final String INPUT_VARIABLE = "items";
  static final String OUTPUT_VARIABLE = "decisionResults";

  private static final Logger LOGGER =
      Logger.getLogger(ParallelMultiInstanceDecisionJobHandler.class.getName());
  private static final int MAX_RETRY_ATTEMPTS = 3;

  private final CamundaClient camundaClient;
  private final JsonMapper jsonMapper;

  ParallelMultiInstanceDecisionJobHandler(
      final CamundaClient camundaClient, final JsonMapper jsonMapper) {
    this.camundaClient = camundaClient;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public void handle(final JobClient jobClient, final ActivatedJob job) {
    final String decisionId = job.getCustomHeaders().get(HEADER_DECISION_ID);
    if (decisionId == null || decisionId.isBlank()) {
      throw new IllegalStateException(
          "Missing required job header '" + HEADER_DECISION_ID + "' on job " + job.getKey());
    }

    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> items =
        (List<Map<String, Object>>) job.getVariable(INPUT_VARIABLE);

    if (items == null || items.isEmpty()) {
      LOGGER.warning(
          () ->
              "Job "
                  + job.getKey()
                  + " received with empty or null '"
                  + INPUT_VARIABLE
                  + "'. Completing with empty results.");
      jobClient.newCompleteCommand(job).variables(Map.of(OUTPUT_VARIABLE, List.of())).send();
      return;
    }

    LOGGER.info(
        () ->
            "Starting parallel evaluation of "
                + items.size()
                + " item(s) for job "
                + job.getKey()
                + " using decision '"
                + decisionId
                + "'");

    final List<CompletableFuture<EvaluateDecisionResponse>> futures =
        items.stream()
            .map(item -> sendWithIsolatedRetry(decisionId, item, MAX_RETRY_ATTEMPTS))
            .toList();

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .thenAccept(
            ignored -> {
              final List<EvaluateDecisionResponse> responses =
                  futures.stream().map(CompletableFuture::join).toList();

              final long failedCount = responses.stream().filter(Objects::isNull).count();
              if (failedCount > 0) {
                LOGGER.warning(
                    () ->
                        failedCount
                            + " of "
                            + items.size()
                            + " decision evaluations permanently failed and were excluded from"
                            + " results for job "
                            + job.getKey());
              }

              final List<Object> results =
                  responses.stream()
                      .filter(Objects::nonNull)
                      .map(
                          response ->
                              jsonMapper.fromJson(response.getDecisionOutput(), Object.class))
                      .toList();

              LOGGER.info(
                  () ->
                      "Completed "
                          + results.size()
                          + "/"
                          + items.size()
                          + " decision evaluations for job "
                          + job.getKey());

              jobClient
                  .newCompleteCommand(job)
                  .variables(Map.of(OUTPUT_VARIABLE, results))
                  .send()
                  .whenComplete(
                      (v, ex) -> {
                        if (ex != null) {
                          LOGGER.warning(
                              () ->
                                  "Failed to complete job "
                                      + job.getKey()
                                      + ": "
                                      + ex.getMessage());
                        }
                      });
            })
        .exceptionally(
            ex -> {
              LOGGER.warning(
                  () ->
                      "Fatal error during batch evaluation for job "
                          + job.getKey()
                          + ": "
                          + ex.getMessage());
              jobClient
                  .newFailCommand(job)
                  .retries(job.getRetries() - 1)
                  .errorMessage(ex.getMessage() != null ? ex.getMessage() : ex.toString())
                  .send();
              return null;
            });
  }

  /**
   * Asynchronously evaluates a single decision with isolated, recursive retry logic. Transient
   * failures are retried without blocking any thread; permanently failing calls resolve to {@code
   * null} so the surrounding batch can still complete.
   */
  private CompletableFuture<EvaluateDecisionResponse> sendWithIsolatedRetry(
      final String decisionId, final Map<String, Object> variables, final int attemptsLeft) {
    final CompletableFuture<EvaluateDecisionResponse> future =
        camundaClient
            .newEvaluateDecisionCommand()
            .decisionId(decisionId)
            .variables(variables)
            .send()
            .toCompletableFuture();

    return future
        .handle(
            (result, exception) -> {
              if (exception == null) {
                return CompletableFuture.completedFuture(result);
              }

              if (attemptsLeft <= 1) {
                LOGGER.warning(
                    () ->
                        "Decision evaluation permanently failed for '"
                            + decisionId
                            + "' with variables "
                            + variables
                            + ": "
                            + exception.getMessage());
                return CompletableFuture.<EvaluateDecisionResponse>completedFuture(null);
              }

              LOGGER.warning(
                  () ->
                      "Decision evaluation failed for '"
                          + decisionId
                          + "', retrying ("
                          + (attemptsLeft - 1)
                          + " attempt(s) left): "
                          + exception.getMessage());

              return sendWithIsolatedRetry(decisionId, variables, attemptsLeft - 1);
            })
        .thenCompose(Function.identity());
  }
}
