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
import io.camunda.zeebe.protocol.record.value.JobRecordValue;
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

  private Controller controller;
  private CamundaClient client;
  private final JsonMapper jsonMapper = new CamundaObjectMapper();
  private final ConcurrentMap<Long, String> inputVariablesByScopeKey = new ConcurrentHashMap<>();
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
    switch (record.getValueType()) {
      case VARIABLE -> warmUpCacheFromVariableEvent(record);
      case PROCESS_INSTANCE -> cleanUpCacheFromProcessInstanceEvent(record);
      case JOB -> handleJobEvent(record);
      default -> {}
    }

    this.controller.updateLastExportedRecordPosition(record.getPosition());
  }

  private void warmUpCacheFromVariableEvent(
      final io.camunda.zeebe.protocol.record.Record<?> record) {
    if (record.getIntent() != VariableIntent.CREATED && record.getIntent() != VariableIntent.UPDATED) {
      return;
    }

    final VariableRecordValue variableRecordValue = (VariableRecordValue) record.getValue();
    if (!INPUT_VARIABLE_NAME.equals(variableRecordValue.getName())) {
      return;
    }

    final long scopeKey = variableRecordValue.getScopeKey();
    inputVariablesByScopeKey.put(
        scopeKey, jsonMapper.fromJson(variableRecordValue.getValue(), String.class));
  }

  private void cleanUpCacheFromProcessInstanceEvent(
      final io.camunda.zeebe.protocol.record.Record<?> record) {
    if (record.getIntent() == ProcessInstanceIntent.ELEMENT_COMPLETED
        || record.getIntent() == ProcessInstanceIntent.ELEMENT_TERMINATED) {
      inputVariablesByScopeKey.remove(record.getKey());
    }
  }

  private void handleJobEvent(final io.camunda.zeebe.protocol.record.Record<?> record) {
    if (record.getIntent() == JobIntent.CREATED) {
      completeCreatedJobUsingCachedInputVariable(record);
      return;
    }

    if (record.getIntent() == JobIntent.CANCELED || record.getIntent() == JobIntent.COMPLETED) {
      evictCachedInputVariableForFinishedJob(record);
    }
  }

  private void evictCachedInputVariableForFinishedJob(
      final io.camunda.zeebe.protocol.record.Record<?> record) {
    final JobRecordValue value = (JobRecordValue) record.getValue();
    inputVariablesByScopeKey.remove(value.getElementInstanceKey());
  }

  private void completeCreatedJobUsingCachedInputVariable(
      final io.camunda.zeebe.protocol.record.Record<?> record) {
    final JobRecordValue job = (JobRecordValue) record.getValue();
    final String inputVariable =
        inputVariablesByScopeKey.getOrDefault(job.getElementInstanceKey(), "");
    final Map<String, Object> outputVariables =
        Map.of("jobWorkerResult", true, OUTPUT_VARIABLE_NAME, inputVariable + GREETING_SUFFIX);

    controller.scheduleCancellableTask(
        Duration.ofMillis(550),
        () ->
            client
                .newCompleteCommand(record.getKey())
                .variables(outputVariables)
                .send()
                .whenComplete(
                    (ignored, error) -> {
                      if (error == null) {
                        inputVariablesByScopeKey.remove(job.getElementInstanceKey());
                      }
                    }));
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
