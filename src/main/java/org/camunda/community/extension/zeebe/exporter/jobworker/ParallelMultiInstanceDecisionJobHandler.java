package org.camunda.community.extension.zeebe.exporter.jobworker;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.JsonMapper;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.response.EvaluateDecisionResponse;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import java.util.ArrayList;
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
 * <p>Two modes are supported, selected via the {@value #HEADER_NESTED} job header:
 *
 * <ul>
 *   <li><b>Flat mode</b> (default, {@value #HEADER_NESTED}{@code =false}): {@value #INPUT_VARIABLE}
 *       is a {@code List<Map<String, Object>>}; result is a {@code List<Object>}.
 *   <li><b>Nested mode</b> ({@value #HEADER_NESTED}{@code =true}): {@value #INPUT_VARIABLE} is a
 *       {@code List<List<Map<String, Object>>>}; each inner list is evaluated independently in
 *       parallel and the result is a {@code List<List<Object>>} preserving the original grouping.
 * </ul>
 *
 * <p>Each decision evaluation is retried up to {@value #MAX_RETRY_ATTEMPTS} times on transient
 * failures. Permanently failing evaluations are silently dropped from the results so that the rest
 * of the batch can still complete.
 */
final class ParallelMultiInstanceDecisionJobHandler implements JobHandler {

  static final String JOB_TYPE = "evaluateParallelMultiInstanceDecisions";
  static final String HEADER_DECISION_ID = "decisionId";
  static final String HEADER_NESTED = "nested";
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

    final boolean nested =
        Boolean.parseBoolean(job.getCustomHeaders().getOrDefault(HEADER_NESTED, "false"));

    if (nested) {
      handleNested(jobClient, job, decisionId);
    } else {
      handleFlat(jobClient, job, decisionId);
    }
  }

  private void handleFlat(
      final JobClient jobClient, final ActivatedJob job, final String decisionId) {
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

    evaluateAllInParallel(decisionId, items, job.getKey())
        .thenAccept(
            results -> {
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
        .exceptionally(ex -> failJob(jobClient, job, "flat", ex));
  }

  private void handleNested(
      final JobClient jobClient, final ActivatedJob job, final String decisionId) {
    @SuppressWarnings("unchecked")
    final List<List<Map<String, Object>>> groups =
        (List<List<Map<String, Object>>>) job.getVariable(INPUT_VARIABLE);

    if (groups == null || groups.isEmpty()) {
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

    // Record the size of each group so results can be re-sliced after the single allOf.
    final List<Integer> groupSizes = groups.stream().map(List::size).toList();
    final int totalItems = groupSizes.stream().mapToInt(Integer::intValue).sum();

    LOGGER.info(
        () ->
            "Starting nested parallel evaluation of "
                + groups.size()
                + " group(s) / "
                + totalItems
                + " item(s) for job "
                + job.getKey()
                + " using decision '"
                + decisionId
                + "'");

    // Flatten all items from all groups into one parallel batch so every evaluation
    // is in-flight simultaneously across group boundaries.
    final List<CompletableFuture<EvaluateDecisionResponse>> allFutures =
        groups.stream()
            .flatMap(List::stream)
            .map(item -> sendWithIsolatedRetry(decisionId, item, MAX_RETRY_ATTEMPTS))
            .toList();

    CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0]))
        .thenAccept(
            ignored -> {
              // Re-slice the flat result list back into per-group sublists.
              final List<List<Object>> results = new ArrayList<>(groupSizes.size());
              int offset = 0;
              for (final int size : groupSizes) {
                final List<Object> groupResults =
                    allFutures.subList(offset, offset + size).stream()
                        .map(CompletableFuture::join)
                        .map(
                            r ->
                                r != null
                                    ? jsonMapper.fromJson(r.getDecisionOutput(), Object.class)
                                    : null)
                        .toList();
                results.add(groupResults);
                offset += size;
              }
              LOGGER.info(
                  () ->
                      "Completed nested evaluation of "
                          + groups.size()
                          + " group(s) for job "
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
        .exceptionally(ex -> failJob(jobClient, job, "nested", ex));
  }

  /**
   * Evaluates all items in parallel and returns a future that resolves to the ordered list of
   * decision outputs. Items whose evaluations permanently fail are dropped silently.
   */
  private CompletableFuture<List<Object>> evaluateAllInParallel(
      final String decisionId, final List<Map<String, Object>> items, final long jobKey) {
    final List<CompletableFuture<EvaluateDecisionResponse>> futures =
        items.stream()
            .map(item -> sendWithIsolatedRetry(decisionId, item, MAX_RETRY_ATTEMPTS))
            .toList();

    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .thenApply(
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
                            + jobKey);
              }
              return responses.stream()
                  .map(
                      r ->
                          r != null
                              ? jsonMapper.fromJson(r.getDecisionOutput(), Object.class)
                              : null)
                  .toList();
            });
  }

  private Void failJob(
      final JobClient jobClient, final ActivatedJob job, final String mode, final Throwable ex) {
    LOGGER.warning(
        () ->
            "Fatal error during "
                + mode
                + " evaluation for job "
                + job.getKey()
                + ": "
                + ex.getMessage());
    jobClient
        .newFailCommand(job)
        .retries(job.getRetries() - 1)
        .errorMessage(ex.getMessage() != null ? ex.getMessage() : ex.toString())
        .send();
    return null;
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
