package org.camunda.community.extension.zeebe.exporter.jobworker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class EmbeddedJobWorkerClasspathIT {

  @Test
  void shouldExposeThatZeebeClientIsNotOnRuntimeClasspath() throws IOException {
    Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable());

    final Path projectRoot = Path.of("").toAbsolutePath();
    final Path builtJar = findBuiltExporterJar(projectRoot.resolve("target"));
    final String containerJarPath = "/usr/local/zeebe/exporters/" + builtJar.getFileName();

    try (final GenericContainer<?> zeebe =
        new GenericContainer<>(DockerImageName.parse("camunda/zeebe:8.9.0"))
            .withExposedPorts(26500)
            .withFileSystemBind(builtJar.toString(), containerJarPath)
            .withEnv(
                "ZEEBE_BROKER_EXPORTERS_JOBWORKER_JARPATH", "exporters/" + builtJar.getFileName())
            .withEnv(
                "ZEEBE_BROKER_EXPORTERS_JOBWORKER_CLASSNAME", EmbeddedJobWorker.class.getName())) {
      zeebe.start();

      final String logs = zeebe.getLogs();
      assertNotNull(logs);
      assertFalse(zeebe.isRunning(), logs);
      assertTrue(logs.contains("NoClassDefFoundError"), logs);
      assertTrue(logs.contains("ClassNotFoundException"), logs);
      assertTrue(logs.contains("io.camunda.zeebe.client.ZeebeClient"), logs);
    }
  }

  private Path findBuiltExporterJar(final Path targetDir) throws IOException {
    try (final Stream<Path> files = Files.list(targetDir)) {
      final Optional<Path> jar =
          files
              .filter(path -> path.getFileName().toString().endsWith(".jar"))
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
