package org.camunda.community.extension.zeebe.exporter.jobworker;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.JsonMapper;
import io.camunda.client.impl.CamundaObjectMapper;
import io.camunda.zeebe.exporter.api.Exporter;
import io.camunda.zeebe.exporter.api.context.Context;
import io.camunda.zeebe.exporter.api.context.Context.RecordFilter;
import io.camunda.zeebe.exporter.api.context.Controller;
import io.camunda.zeebe.protocol.record.RecordType;
import io.camunda.zeebe.protocol.record.ValueType;
import io.camunda.zeebe.protocol.record.intent.JobIntent;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.camunda.zeebe.protocol.record.intent.VariableIntent;
import io.camunda.zeebe.protocol.record.value.BpmnElementType;
import io.camunda.zeebe.protocol.record.value.JobRecordValue;
import io.camunda.zeebe.protocol.record.value.ProcessInstanceRecordValue;
import io.camunda.zeebe.protocol.record.value.VariableRecordValue;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class EmbeddedJobWorker implements Exporter {
  private static final String INPUT_VARIABLE_NAME = "inputValue";
  private static final String OUTPUT_VARIABLE_NAME = "greeting";
  private static final String GREETING_SUFFIX = " world!";
  private static final String DEFAULT_GATEWAY_ADDRESS = "http://localhost:26500";
  private static final Set<BpmnElementType> JOB_CAPABLE_TASK_TYPES =
      Set.of(
          BpmnElementType.SERVICE_TASK,
          BpmnElementType.BUSINESS_RULE_TASK,
          BpmnElementType.SEND_TASK);

  private Controller controller;
  private CamundaClient client;
  private final JsonMapper jsonMapper = new CamundaObjectMapper();
  private final Set<Long> eligibleTaskScopeKeys = ConcurrentHashMap.newKeySet();
  private final ConcurrentMap<Long, String> inputValuesByScopeKey = new ConcurrentHashMap<>();
  private JobWorkerExporterConfiguration configuration = new JobWorkerExporterConfiguration();

  @Override
  public void configure(final Context context) throws Exception {
    configuration = context.getConfiguration().instantiate(JobWorkerExporterConfiguration.class);
    context.setFilter(
        new RecordFilter() {
          private static final Set<ValueType> ACCEPTED_VALUE_TYPES =
              Set.of(ValueType.JOB, ValueType.VARIABLE, ValueType.PROCESS_INSTANCE);

          @Override
          public boolean acceptType(final RecordType recordType) {
            return recordType == RecordType.EVENT;
          }

          @Override
          public boolean acceptValue(final ValueType valueType) {
            return ACCEPTED_VALUE_TYPES.contains(valueType);
          }
        });
  }

  @Override
  public void open(Controller controller) {
    this.controller = controller;
    this.client =
        CamundaClient.newClientBuilder()
            .grpcAddress(URI.create(resolveGatewayAddress()))
            .preferRestOverGrpc(false)
            .build();
  }

  @Override
  public void close() {
    client.close();
  }

  @Override
  public void export(io.camunda.zeebe.protocol.record.Record<?> record) {
    if (record.getValueType() == ValueType.PROCESS_INSTANCE) {
      handleProcessInstanceEvent(record);
    }

    if (record.getValueType() == ValueType.VARIABLE
        && (record.getIntent() == VariableIntent.CREATED
            || record.getIntent() == VariableIntent.UPDATED)) {
      final VariableRecordValue variableRecordValue = (VariableRecordValue) record.getValue();
      handleVariableEvent(variableRecordValue);
    }

    if (record.getIntent() == JobIntent.CREATED) {
      final JobRecordValue value = (JobRecordValue) record.getValue();
      final String inputValue =
          inputValuesByScopeKey.getOrDefault(value.getElementInstanceKey(), "");
      final Map<String, Object> outputVariables =
          Map.of("jobWorkerResult", true, OUTPUT_VARIABLE_NAME, inputValue + GREETING_SUFFIX);
      controller.scheduleCancellableTask(
          Duration.ofMillis(550),
          () -> client.newCompleteCommand(record.getKey()).variables(outputVariables).send());
    }

    this.controller.updateLastExportedRecordPosition(record.getPosition());
  }

  private void handleProcessInstanceEvent(final io.camunda.zeebe.protocol.record.Record<?> record) {
    final ProcessInstanceRecordValue processInstanceRecordValue =
        (ProcessInstanceRecordValue) record.getValue();
    final long elementInstanceKey = record.getKey();

    if (record.getIntent() == ProcessInstanceIntent.ELEMENT_ACTIVATED
        && JOB_CAPABLE_TASK_TYPES.contains(processInstanceRecordValue.getBpmnElementType())) {
      eligibleTaskScopeKeys.add(elementInstanceKey);
      return;
    }

    if (record.getIntent() == ProcessInstanceIntent.ELEMENT_COMPLETED
        || record.getIntent() == ProcessInstanceIntent.ELEMENT_TERMINATED) {
      eligibleTaskScopeKeys.remove(elementInstanceKey);
      inputValuesByScopeKey.remove(elementInstanceKey);
    }
  }

  private void handleVariableEvent(final VariableRecordValue variableRecordValue) {
    if (!INPUT_VARIABLE_NAME.equals(variableRecordValue.getName())) {
      return;
    }

    final long scopeKey = variableRecordValue.getScopeKey();
    if (!eligibleTaskScopeKeys.contains(scopeKey)) {
      return;
    }

    inputValuesByScopeKey.put(
        scopeKey, jsonMapper.fromJson(variableRecordValue.getValue(), String.class));
  }

  private String resolveGatewayAddress() {
    final String gatewayAddressFromConfiguration = configuration.getGatewayAddress();
    if (gatewayAddressFromConfiguration != null && !gatewayAddressFromConfiguration.isBlank()) {
      return gatewayAddressFromConfiguration;
    }

    return DEFAULT_GATEWAY_ADDRESS;
  }

  public static final class JobWorkerExporterConfiguration {
    private String gatewayAddress = DEFAULT_GATEWAY_ADDRESS;

    public String getGatewayAddress() {
      return gatewayAddress;
    }

    public void setGatewayAddress(final String gatewayAddress) {
      this.gatewayAddress = gatewayAddress;
    }
  }
}
