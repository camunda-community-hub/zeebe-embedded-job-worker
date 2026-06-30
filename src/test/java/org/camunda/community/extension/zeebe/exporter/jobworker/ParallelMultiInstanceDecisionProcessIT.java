package org.camunda.community.extension.zeebe.exporter.jobworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.JsonMapper;
import io.camunda.client.api.command.ClientStatusException;
import io.camunda.client.api.response.ProcessInstanceResult;
import io.camunda.client.impl.CamundaObjectMapper;
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
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for {@link ParallelMultiInstanceDecisionJobHandler}.
 *
 * <p>The exporter JAR is mounted into a {@code camunda/camunda} container. The BPMN and DMN are
 * deployed via the gRPC API, and the process is started with a {@code scores} list. The BPMN IO
 * mapping renames {@code scores} → {@code items} and {@code decisionResults} → {@code ratings}.
 */
class ParallelMultiInstanceDecisionProcessIT {

  private static final String EXPORTER_JAR_PREFIX = "zeebe-embedded-job-worker-";
  private static final String PROCESS_ID = "parallel-multi-instance-decision-test-process";
  private static final String BPMN_RESOURCE = "parallel-multi-instance-decision-test-process.bpmn";
  private static final String DMN_RESOURCE = "classify-item.dmn";
  private static final String DEFAULT_CAMUNDA_VERSION = "8.9.8";
  private static final Duration GATEWAY_READY_TIMEOUT = Duration.ofSeconds(30);
  private static final long POLLING_INTERVAL_MS = 200;
  private static final JsonMapper JSON_MAPPER = new CamundaObjectMapper();

  // false positive: Eclipse can't prove fluent .withXxx() calls won't throw before
  // try-with-resources assigns zeebe
  @SuppressWarnings("resource")
  @Test
  void shouldEvaluateDecisionsInParallelAndReturnRatings() throws IOException {
    Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable());

    final Path projectRoot = Path.of("").toAbsolutePath();
    final Path builtJar = findBuiltExporterJar(projectRoot.resolve("target"));
    final String containerJarPath = "/usr/local/zeebe/exporters/" + builtJar.getFileName();
    final String camundaVersion = System.getProperty("camunda.version", DEFAULT_CAMUNDA_VERSION);

    try (final GenericContainer<?> zeebe =
        new GenericContainer<>(DockerImageName.parse("camunda/camunda:" + camundaVersion))
            .withExposedPorts(26500)
            .withFileSystemBind(builtJar.toString(), containerJarPath, BindMode.READ_ONLY)
            .withEnv("CAMUNDA_DATA_EXPORTERS_JOBWORKER_JARPATH", containerJarPath)
            .withEnv(
                "CAMUNDA_DATA_EXPORTERS_JOBWORKER_CLASSNAME", EmbeddedJobWorker.class.getName())
            .withEnv("SPRING_PROFILES_ACTIVE", "broker,consolidated-auth,security")
            .withEnv("CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI", "true")
            .withEnv("CAMUNDA_SECURITY_AUTHORIZATIONS_ENABLED", "false")
            .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_TYPE", "rdbms")
            .withEnv("CAMUNDA_DATABASE_TYPE", "rdbms")
            .withEnv("CAMUNDA_DATABASE_URL", "jdbc:h2:mem:it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
            .withEnv("CAMUNDA_DATABASE_USERNAME", "sa")
            .withEnv("CAMUNDA_DATABASE_PASSWORD", "")
            .withEnv(
                "ZEEBE_BROKER_EXPORTERS_RDBMS_CLASSNAME",
                "io.camunda.exporter.rdbms.RdbmsExporter")) {
      zeebe.start();

      final URI gatewayAddress = URI.create("http://localhost:" + zeebe.getMappedPort(26500));
      try (final CamundaClient client =
          CamundaClient.newClientBuilder()
              .grpcAddress(gatewayAddress)
              .preferRestOverGrpc(false)
              .defaultRequestTimeout(Duration.ofMinutes(2))
              .build()) {
        awaitGatewayAvailable(client, GATEWAY_READY_TIMEOUT);

        client
            .newDeployResourceCommand()
            .addResourceFromClasspath(BPMN_RESOURCE)
            .addResourceFromClasspath(DMN_RESOURCE)
            .send()
            .join();

        final List<Map<String, Object>> scores =
            List.of(Map.of("score", 30), Map.of("score", 65), Map.of("score", 90));

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

        @SuppressWarnings("unchecked")
        final List<String> ratings = (List<String>) resultVariables.get("ratings");

        assertNotNull(ratings, "'ratings' variable must be present in process result");
        assertEquals(3, ratings.size(), "Expected one rating per input score");
        assertEquals("low", ratings.get(0), "score=30 should be rated 'low'");
        assertEquals("medium", ratings.get(1), "score=65 should be rated 'medium'");
        assertEquals("high", ratings.get(2), "score=90 should be rated 'high'");
      }
    }
  }

  private void awaitGatewayAvailable(final CamundaClient client, final Duration timeout) {
    final long timeoutAt = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < timeoutAt) {
      try {
        client.newTopologyRequest().send().join();
        return;
      } catch (ClientStatusException | CompletionException e) {
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
