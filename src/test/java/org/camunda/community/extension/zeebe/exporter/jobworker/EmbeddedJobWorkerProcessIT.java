package org.camunda.community.extension.zeebe.exporter.jobworker;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceResult;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class EmbeddedJobWorkerProcessIT {
  private static final String EXPORTED_ARTIFACT_PREFIX = "zeebe-embedded-job-worker-";
  private static final String PROCESS_ID = "embedded-job-worker-test-process";
  private static final String PROCESS_RESOURCE = "embedded-job-worker-test-process.bpmn";
  private static final Duration GATEWAY_READY_TIMEOUT = Duration.ofSeconds(30);
  private static final long POLLING_INTERVAL_MS = 200;

  @Test
  void shouldCompleteServiceTaskAndReturnResultVariables() throws IOException {
    Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable());

    final Path projectRoot = Path.of("").toAbsolutePath();
    final Path builtJar = findBuiltExporterJar(projectRoot.resolve("target"));
    final String containerJarPath = "/usr/local/zeebe/exporters/" + builtJar.getFileName();
    final String zeebeVersion = System.getProperty("zeebe.version", "8.9.0");

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
          CamundaClient.newClientBuilder().grpcAddress(gatewayAddress).usePlaintext().build()) {
        awaitGatewayAvailable(client, GATEWAY_READY_TIMEOUT);

        client.newDeployResourceCommand().addResourceFromClasspath(PROCESS_RESOURCE).send().join();

        final ProcessInstanceResult result =
            client
                .newCreateInstanceCommand()
                .bpmnProcessId(PROCESS_ID)
                .latestVersion()
                .variables(Map.of("inputValue", "hello-worker"))
                .withResult()
                .send()
                .join();

        assertNotNull(result);
        assertTrue(result.getVariables().contains("\"inputValue\":\"hello-worker\""));
        assertTrue(result.getVariables().contains("\"jobWorkerResult\":true"));
      }
    }
  }

  private void awaitGatewayAvailable(final CamundaClient client, final Duration timeout) {
    final long timeoutAt = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < timeoutAt) {
      try {
        client.newTopologyRequest().send().join();
        return;
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
              .filter(path -> path.getFileName().toString().startsWith(EXPORTED_ARTIFACT_PREFIX))
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
