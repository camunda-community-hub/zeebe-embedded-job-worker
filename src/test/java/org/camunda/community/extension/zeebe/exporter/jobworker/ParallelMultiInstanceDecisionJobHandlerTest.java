package org.camunda.community.extension.zeebe.exporter.jobworker;

import static io.camunda.process.test.api.CamundaAssert.assertThat;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.worker.JobWorker;
import io.camunda.client.impl.CamundaObjectMapper;
import io.camunda.process.test.api.CamundaProcessTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@CamundaProcessTest
class ParallelMultiInstanceDecisionJobHandlerTest {

  private CamundaClient client;

  @Test
  void shouldEvaluateDecisionsInParallelAndReturnRatings() {
    client
        .newDeployResourceCommand()
        .addResourceFromClasspath("batch-decision-test-process.bpmn")
        .addResourceFromClasspath("classify-item.dmn")
        .send()
        .join();

    try (final JobWorker worker =
        client
            .newWorker()
            .jobType(ParallelMultiInstanceDecisionJobHandler.JOB_TYPE)
            .handler(new ParallelMultiInstanceDecisionJobHandler(client, new CamundaObjectMapper()))
            .open()) {

      final ProcessInstanceEvent processInstance =
          client
              .newCreateInstanceCommand()
              .bpmnProcessId("batch-decision-test-process")
              .latestVersion()
              .variables(
                  Map.of(
                      "scores",
                      List.of(Map.of("score", 30), Map.of("score", 65), Map.of("score", 90))))
              .send()
              .join();

      assertThat(processInstance)
          .isCompleted()
          .hasVariable("ratings", List.of("low", "medium", "high"));
    }
  }
}
