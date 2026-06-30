package org.camunda.community.extension.zeebe.exporter.jobworker;

import static io.camunda.process.test.api.CamundaAssert.assertThat;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.worker.JobWorker;
import io.camunda.client.impl.CamundaObjectMapper;
import io.camunda.process.test.api.CamundaProcessTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@CamundaProcessTest
class ParallelMultiInstanceDecisionJobHandlerTest {

  private CamundaClient client;

  @BeforeEach
  void deployResources() {
    client
        .newDeployResourceCommand()
        .addResourceFromClasspath("parallel-multi-instance-decision-test-process.bpmn")
        .addResourceFromClasspath("parallel-multi-instance-decision-nested-test-process.bpmn")
        .addResourceFromClasspath("classify-item.dmn")
        .send()
        .join();
  }

  @Test
  void shouldEvaluateFlatItemsAndReturnRatings() {
    try (final JobWorker worker = openWorker()) {
      final ProcessInstanceEvent processInstance =
          client
              .newCreateInstanceCommand()
              .bpmnProcessId("parallel-multi-instance-decision-test-process")
              .latestVersion()
              .variables(
                  Map.of(
                      "scores",
                      List.of(Map.of("score", 30), Map.of("score", 65), Map.of("score", 90))))
              .send()
              .join();

      assertThat(processInstance)
          .hasNoActiveIncidents()
          .hasCompletedElements("Activity_classifyItems")
          .hasLocalVariable(
              "Activity_classifyItems", "decisionResults", List.of("low", "medium", "high"))
          .isCompleted()
          .hasVariable("ratings", List.of("low", "medium", "high"));
    }
  }

  @Test
  void shouldEvaluateNestedGroupsAndReturnGroupedRatings() {
    try (final JobWorker worker = openWorker()) {
      final ProcessInstanceEvent processInstance =
          client
              .newCreateInstanceCommand()
              .bpmnProcessId("parallel-multi-instance-decision-nested-test-process")
              .latestVersion()
              .variables(
                  Map.of(
                      "groups",
                      List.of(
                          List.of(Map.of("score", 30), Map.of("score", 65)),
                          List.of(Map.of("score", 90)))))
              .send()
              .join();

      assertThat(processInstance)
          .hasNoActiveIncidents()
          .hasCompletedElements("Activity_classifyGroups")
          .hasLocalVariable(
              "Activity_classifyGroups",
              "decisionResults",
              List.of(List.of("low", "medium"), List.of("high")))
          .isCompleted()
          .hasVariable("ratings", List.of(List.of("low", "medium"), List.of("high")));
    }
  }

  private JobWorker openWorker() {
    return client
        .newWorker()
        .jobType(ParallelMultiInstanceDecisionJobHandler.JOB_TYPE)
        .handler(new ParallelMultiInstanceDecisionJobHandler(client, new CamundaObjectMapper()))
        .open();
  }
}
