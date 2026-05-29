package org.camunda.community.extension.zeebe.exporter.jobworker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class EmbeddedJobWorkerClasspathIT {
  private static final String EXPORTED_ARTIFACT_PREFIX = "zeebe-embedded-job-worker-";
  private static final Duration CONTAINER_STOP_TIMEOUT = Duration.ofSeconds(10);
  private static final long POLLING_INTERVAL_MS = 100;

  @Test
  void shouldFailWhenZeebeClientIsNotOnRuntimeClasspath() throws IOException {
    Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable());

    final Path projectRoot = Path.of("").toAbsolutePath();
    final Path builtJar = findBuiltExporterJar(projectRoot.resolve("target"));
    final String containerJarPath = "/usr/local/zeebe/exporters/" + builtJar.getFileName();
    final String zeebeVersion = System.getProperty("zeebe.version", "8.9.0");

    try (final GenericContainer<?> zeebe =
        new GenericContainer<>(DockerImageName.parse("camunda/zeebe:" + zeebeVersion))
            .withExposedPorts(26500)
            .withFileSystemBind(builtJar.toString(), containerJarPath)
            .withEnv(
                "ZEEBE_BROKER_EXPORTERS_JOBWORKER_JARPATH", "exporters/" + builtJar.getFileName())
            .withEnv(
                "ZEEBE_BROKER_EXPORTERS_JOBWORKER_CLASSNAME", EmbeddedJobWorker.class.getName())) {
      zeebe.start();
      waitUntilContainerStops(zeebe, CONTAINER_STOP_TIMEOUT);

      final String logs = zeebe.getLogs();
      assertNotNull(logs);
      assertFalse(zeebe.isRunning(), logs);
      assertTrue(logs.contains("NoClassDefFoundError: io/camunda/zeebe/client/ZeebeClient"), logs);
      assertTrue(
          logs.contains("ClassNotFoundException: io.camunda.zeebe.client.ZeebeClient"), logs);
    }
  }

  private void waitUntilContainerStops(
      final GenericContainer<?> container, final Duration timeout) {
    final long timeoutAt = System.nanoTime() + timeout.toNanos();
    while (container.isRunning() && System.nanoTime() < timeoutAt) {
      try {
        Thread.sleep(POLLING_INTERVAL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for container to stop", e);
      }
    }
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
