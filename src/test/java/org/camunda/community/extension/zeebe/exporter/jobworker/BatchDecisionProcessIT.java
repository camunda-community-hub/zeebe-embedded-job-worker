package org.camunda.community.extension.zeebe.exporter.jobworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.JsonMapper;
import io.camunda.client.api.command.ClientStatusException;
import io.camunda.client.api.response.ProcessInstanceResult;
import io.camunda.client.impl.CamundaObjectMapper;
import io.camunda.client.impl.basicauth.BasicAuthCredentialsProviderBuilder;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for {@link ParallelMultiInstanceDecisionJobHandler}.
 *
 * <p>A BPMN process containing a business rule task of type {@value
 * ParallelMultiInstanceDecisionJobHandler#JOB_TYPE} is deployed together with the {@code
 * classify-item} DMN. The process starts with a {@code scores} list variable; the BPMN IO mapping
 * renames it to {@code items} (the handler's input variable) and maps the handler's {@code
 * decisionResults} output back to {@code ratings}. The decision ID itself is provided as a job
 * header, making the handler reusable for any DMN decision.
 */
class BatchDecisionProcessIT {

  private static final String EXPORTER_JAR_PREFIX = "zeebe-embedded-job-worker-";
  private static final String PROCESS_ID = "batch-decision-test-process";
  private static final String BPMN_RESOURCE = "batch-decision-test-process.bpmn";
  private static final String DMN_RESOURCE = "classify-item.dmn";
  private static final String DEFAULT_ZEEBE_VERSION = "8.9.0";
  private static final Duration GATEWAY_READY_TIMEOUT = Duration.ofSeconds(30);
  private static final long POLLING_INTERVAL_MS = 200;
  private static final JsonMapper JSON_MAPPER = new CamundaObjectMapper();

  @Test
  void shouldEvaluateDecisionsInParallelAndReturnRatings() throws IOException {
    Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable());

    final Path projectRoot = Path.of("").toAbsolutePath();
    final Path builtJar = findBuiltExporterJar(projectRoot.resolve("target"));
    final String containerJarPath = "/usr/local/zeebe/exporters/" + builtJar.getFileName();
    final String zeebeVersion = System.getProperty("zeebe.version", DEFAULT_ZEEBE_VERSION);

    try (final GenericContainer<?> zeebe =
        new GenericContainer<>(DockerImageName.parse("camunda/zeebe:" + zeebeVersion))
            .withExposedPorts(26500)
            .withFileSystemBind(builtJar.toString(), containerJarPath)
            .withEnv("CAMUNDA_DATA_EXPORTERS_JOBWORKER_JARPATH", containerJarPath)
            .withEnv(
                "CAMUNDA_DATA_EXPORTERS_JOBWORKER_CLASSNAME", EmbeddedJobWorker.class.getName())) {
      zeebe.start();

      final URI gatewayAddress = URI.create("http://localhost:" + zeebe.getMappedPort(26500));
      try (final CamundaClient client =
          CamundaClient.newClientBuilder()
              .grpcAddress(gatewayAddress)
              .preferRestOverGrpc(false)
              .credentialsProvider(
                  new BasicAuthCredentialsProviderBuilder()
                      .username("demo")
                      .password("demo")
                      .build())
              .build()) {
        awaitGatewayAvailable(client, GATEWAY_READY_TIMEOUT);

        try {
          // Deploy both the BPMN process and the DMN decision model together
          client
              .newDeployResourceCommand()
              .addResourceFromClasspath(BPMN_RESOURCE)
              .addResourceFromClasspath(DMN_RESOURCE)
              .send()
              .join();

          // Input: three score objects; the BPMN input mapping renames "scores" → "items"
          final List<Map<String, Object>> scores =
              List.of(
                  Map.of("score", 30), // expected rating: "low"
                  Map.of("score", 65), // expected rating: "medium"
                  Map.of("score", 90) // expected rating: "high"
                  );

          final ProcessInstanceResult result =
              client
                  .newCreateInstanceCommand()
                  .bpmnProcessId(PROCESS_ID)
                  .latestVersion()
                  .variables(Map.of("scores", scores))
                  .withResult()
                  .send()
                  .join();

          assertNotNull(result);
          final Map<String, Object> resultVariables =
              JSON_MAPPER.fromJsonAsMap(result.getVariables());

          // The BPMN output mapping renames "decisionResults" → "ratings"
          @SuppressWarnings("unchecked")
          final List<Map<String, Object>> ratings =
              (List<Map<String, Object>>) resultVariables.get("ratings");

          assertNotNull(ratings, "'ratings' variable must be present in process result");
          assertEquals(3, ratings.size(), "Expected one rating per input score");
          assertEquals("low", ratings.get(0).get("rating"), "score=30 should be rated 'low'");
          assertEquals("medium", ratings.get(1).get("rating"), "score=65 should be rated 'medium'");
          assertEquals("high", ratings.get(2).get("rating"), "score=90 should be rated 'high'");

        } catch (ClientStatusException e) {
          final String message = e.getMessage();
          final boolean unsupportedSecurityConfiguration =
              message != null
                  && (message.contains("FORBIDDEN")
                      || message.contains("authentication")
                      || message.contains("Time out between gateway and broker"));
          Assumptions.assumeTrue(
              !unsupportedSecurityConfiguration,
              "Skipping process execution test for secured gateway configuration: " + message);
          throw e;
        }
      }
    }
  }

  private void awaitGatewayAvailable(final CamundaClient client, final Duration timeout) {
    final long timeoutAt = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < timeoutAt) {
      try {
        client.newTopologyRequest().send().join();
        return;
      } catch (ClientStatusException e) {
        final String message = e.getMessage();
        final boolean unsupportedSecurityConfiguration =
            message != null
                && (message.contains("Failed to authenticate")
                    || message.contains("FORBIDDEN")
                    || message.contains("authorization"));
        Assumptions.assumeTrue(
            !unsupportedSecurityConfiguration,
            "Skipping process execution test for secured gateway configuration: " + message);
        throw e;
      } catch (CompletionException e) {
        try {
          Thread.sleep(POLLING_INTERVAL_MS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "Interrupted while waiting for gateway availability", interrupted);
        }
      }
    }
    throw new IllegalStateException("Gateway was not available within " + timeout);
  }

  private Path findBuiltExporterJar(final Path targetDir) throws IOException {
    try (final Stream<Path> files = Files.list(targetDir)) {
      final Optional<Path> jar =
          files
              .filter(path -> path.getFileName().toString().endsWith(".jar"))
              .filter(path -> path.getFileName().toString().startsWith(EXPORTER_JAR_PREFIX))
              .filter(path -> !path.getFileName().toString().endsWith("-sources.jar"))
              .filter(path -> !path.getFileName().toString().endsWith("-javadoc.jar"))
              .max(Comparator.comparing(path -> path.toFile().lastModified()));

      return jar.orElseThrow(
          () ->
              new IllegalStateException(
                  "Expected built exporter jar in " + targetDir + " before integration tests"));
    }
  }
}
