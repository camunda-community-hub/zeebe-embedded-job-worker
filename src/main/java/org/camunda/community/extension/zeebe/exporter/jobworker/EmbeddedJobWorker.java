package org.camunda.community.extension.zeebe.exporter.jobworker;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.JsonMapper;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobHandler;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

public class EmbeddedJobWorker implements Exporter {
  private static final String DEFAULT_GATEWAY_ADDRESS = "http://localhost:26500";
  private static final long DEFAULT_JOB_COMPLETION_DELAY_MS = 550L;
  private static final String JOB_HANDLER_ERROR_MESSAGE_PREFIX =
      "Embedded JobHandler invocation failed: ";
  private static final Logger LOGGER = Logger.getLogger(EmbeddedJobWorker.class.getName());

  private Controller controller;
  private CamundaClient client;
  private final JsonMapper jsonMapper = new CamundaObjectMapper();
  private final ConcurrentMap<Long, ConcurrentMap<String, Object>> variablesByScope =
      new ConcurrentHashMap<>();
  private final JobHandler helloWorldJobHandler = new HelloWorldJobHandler();
  private JobHandler delayedCompletionJobHandler;
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
    delayedCompletionJobHandler =
        new DelayedCompletionJobHandler(controller, configuration.getJobCompletionDelayMs());
  }

  @Override
  public void close() {
    client.close();
  }

  @Override
  public void export(io.camunda.zeebe.protocol.record.Record<?> record) {
    switch (record.getValueType()) {
      case VARIABLE -> updateVariablesByScopeFromVariableEvent(
          (VariableIntent) record.getIntent(), (VariableRecordValue) record.getValue());
      case PROCESS_INSTANCE -> removeVariablesByScopeFromProcessInstanceEvent(
          (ProcessInstanceIntent) record.getIntent(), record.getKey());
      case JOB -> handleJobEvent((JobIntent) record.getIntent(), record);
      default -> {}
    }

    this.controller.updateLastExportedRecordPosition(record.getPosition());
  }

  private void updateVariablesByScopeFromVariableEvent(
      final VariableIntent intent, final VariableRecordValue variableRecordValue) {
    if (intent != VariableIntent.CREATED && intent != VariableIntent.UPDATED) {
      return;
    }

    final long scopeKey = variableRecordValue.getScopeKey();
    final Object scopedVariableValue =
        jsonMapper.fromJson(variableRecordValue.getValue(), Object.class);
    variablesByScope
        .computeIfAbsent(scopeKey, ignored -> new ConcurrentHashMap<>())
        .put(variableRecordValue.getName(), scopedVariableValue);
  }

  private void removeVariablesByScopeFromProcessInstanceEvent(
      final ProcessInstanceIntent intent, final long scopeKey) {
    if (intent == ProcessInstanceIntent.ELEMENT_COMPLETED
        || intent == ProcessInstanceIntent.ELEMENT_TERMINATED) {
      variablesByScope.remove(scopeKey);
    }
  }

  private void handleJobEvent(
      final JobIntent intent, final io.camunda.zeebe.protocol.record.Record<?> jobRecord) {
    final JobRecordValue job = (JobRecordValue) jobRecord.getValue();
    if (intent == JobIntent.CREATED) {
      handleCreatedJobUsingRegisteredHandlers(jobRecord, job);
      return;
    }

    if (intent == JobIntent.CANCELED || intent == JobIntent.COMPLETED) {
      removeVariablesByScopeForFinishedJob(job);
    }
  }

  private void removeVariablesByScopeForFinishedJob(final JobRecordValue job) {
    variablesByScope.remove(job.getElementInstanceKey());
  }

  private void handleCreatedJobUsingRegisteredHandlers(
      final io.camunda.zeebe.protocol.record.Record<?> jobRecord, final JobRecordValue job) {
    final String jobType = job.getType() == null ? "" : job.getType();
    final JobHandler jobHandler =
        switch (jobType) {
          case "helloWorld" -> helloWorldJobHandler;
          default -> delayedCompletionJobHandler;
        };
    invokeHandlerForCreatedJob(jobRecord, job, jobHandler);
  }

  private void invokeHandlerForCreatedJob(
      final io.camunda.zeebe.protocol.record.Record<?> jobRecord,
      final JobRecordValue job,
      final JobHandler jobHandler) {
    final Map<String, Object> variablesFromScope =
        variablesByScope.get(job.getElementInstanceKey());
    final Map<String, Object> scopedVariables =
        variablesFromScope == null ? Map.of() : variablesFromScope;
    final long jobKey = jobRecord.getKey();
    final ActivatedJob activatedJob =
        new EmbeddedActivatedJob(jobRecord, jsonMapper, scopedVariables);
    try {
      jobHandler.handle(client, activatedJob);
    } catch (Exception e) {
      LOGGER.warning(
          () -> "Job handler invocation failed for job " + jobKey + ": " + e.getMessage());
      failCreatedJob(
          jobKey, job.getElementInstanceKey(), JOB_HANDLER_ERROR_MESSAGE_PREFIX + e.getMessage());
    }
  }

  private void failCreatedJob(
      final long jobKey, final long elementInstanceKey, final String errorMessage) {
    client
        .newFailCommand(jobKey)
        .retries(0)
        .errorMessage(errorMessage)
        .send()
        .whenComplete(
            (ignored, error) -> {
              if (error == null) {
                variablesByScope.remove(elementInstanceKey);
              }
            });
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
    private long jobCompletionDelayMs = DEFAULT_JOB_COMPLETION_DELAY_MS;

    public String getGatewayAddress() {
      return gatewayAddress;
    }

    public void setGatewayAddress(final String gatewayAddress) {
      this.gatewayAddress = gatewayAddress;
    }

    public long getJobCompletionDelayMs() {
      return jobCompletionDelayMs;
    }

    public void setJobCompletionDelayMs(final long jobCompletionDelayMs) {
      this.jobCompletionDelayMs = jobCompletionDelayMs;
    }
  }
}
