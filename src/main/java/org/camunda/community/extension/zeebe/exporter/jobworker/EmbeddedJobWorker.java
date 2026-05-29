package org.camunda.community.extension.zeebe.exporter.jobworker;

import io.camunda.client.CamundaClient;
import io.camunda.zeebe.exporter.api.Exporter;
import io.camunda.zeebe.exporter.api.context.Context;
import io.camunda.zeebe.exporter.api.context.Context.RecordFilter;
import io.camunda.zeebe.exporter.api.context.Controller;
import io.camunda.zeebe.protocol.record.RecordType;
import io.camunda.zeebe.protocol.record.ValueType;
import io.camunda.zeebe.protocol.record.intent.JobIntent;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.camunda.zeebe.protocol.record.intent.VariableIntent;
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

  private Controller controller;
  private CamundaClient client;
  private final ConcurrentMap<Long, String> inputValuesByProcessInstanceKey =
      new ConcurrentHashMap<>();

  @Override
  public void configure(final Context context) throws Exception {
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
        CamundaClient.newClientBuilder().grpcAddress(URI.create("http://localhost:26500")).build();
  }

  @Override
  public void close() {
    client.close();
  }

  @Override
  public void export(io.camunda.zeebe.protocol.record.Record<?> record) {
    if (record.getValueType() == ValueType.VARIABLE
        && (record.getIntent() == VariableIntent.CREATED
            || record.getIntent() == VariableIntent.UPDATED)) {
      final VariableRecordValue variableRecordValue = (VariableRecordValue) record.getValue();
      if (INPUT_VARIABLE_NAME.equals(variableRecordValue.getName())) {
        inputValuesByProcessInstanceKey.put(
            variableRecordValue.getProcessInstanceKey(),
            stripStringQuotes(variableRecordValue.getValue()));
      }
    }

    if (record.getIntent() == JobIntent.CREATED) {
      final JobRecordValue value = (JobRecordValue) record.getValue();
      final String inputValue =
          inputValuesByProcessInstanceKey.getOrDefault(value.getProcessInstanceKey(), "");
      final Map<String, Object> outputVariables =
          Map.of("jobWorkerResult", true, OUTPUT_VARIABLE_NAME, inputValue + GREETING_SUFFIX);
      controller.scheduleCancellableTask(
          Duration.ofMillis(550),
          () -> client.newCompleteCommand(record.getKey()).variables(outputVariables).send());
    }

    if (record.getValueType() == ValueType.PROCESS_INSTANCE
        && record.getIntent() == ProcessInstanceIntent.ELEMENT_COMPLETED) {
      final ProcessInstanceRecordValue processInstanceRecordValue =
          (ProcessInstanceRecordValue) record.getValue();
      if (processInstanceRecordValue
          .getBpmnProcessId()
          .equals(processInstanceRecordValue.getElementId())) {
        inputValuesByProcessInstanceKey.remove(processInstanceRecordValue.getProcessInstanceKey());
      }
    }
    this.controller.updateLastExportedRecordPosition(record.getPosition());
  }

  private String stripStringQuotes(final String jsonValue) {
    if (jsonValue != null && jsonValue.length() >= 2) {
      if (jsonValue.charAt(0) == '"' && jsonValue.charAt(jsonValue.length() - 1) == '"') {
        return jsonValue.substring(1, jsonValue.length() - 1);
      }
    }
    return jsonValue;
  }
}
