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
  private static final String EXPORTED_ARTIFACT_PREFIX = "zeebe-embedded-job-worker-";

  @Test
  void shouldStartWhenCamundaClientIsNotOnRuntimeClasspath() throws IOException {
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

      final String logs = zeebe.getLogs();
      assertNotNull(logs);
      assertTrue(zeebe.isRunning(), logs);
      assertFalse(logs.contains("NoClassDefFoundError: io/camunda/client/CamundaClient"), logs);
      assertFalse(logs.contains("ClassNotFoundException: io.camunda.client.CamundaClient"), logs);
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
