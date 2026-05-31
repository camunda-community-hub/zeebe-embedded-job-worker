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
import java.util.logging.Logger;

public class EmbeddedJobWorker implements Exporter {
  private static final String INPUT_VARIABLE_NAME = "name";
  private static final String OUTPUT_VARIABLE_NAME = "greeting";
  private static final String DEFAULT_OUTPUT_VARIABLE_NAME = "jobWorkerResult";
  private static final String GREETING_PREFIX = "Hello ";
  private static final String GREETING_SUFFIX = "!";
  private static final Set<String> HELLO_JOB_TYPES =
      Set.of("say-hello", "hello-world", "say hello", "hello world");
  private static final String DEFAULT_GATEWAY_ADDRESS = "http://localhost:26500";
  private static final long DEFAULT_JOB_COMPLETION_DELAY_MS = 550L;
  private static final String MISSING_INPUT_VALUE_ERROR_MESSAGE =
      "Missing required scoped variable 'name'";
  private static final Logger LOGGER = Logger.getLogger(EmbeddedJobWorker.class.getName());

  private Controller controller;
  private CamundaClient client;
  private final JsonMapper jsonMapper = new CamundaObjectMapper();
  private final ConcurrentMap<Long, String> variablesByScope = new ConcurrentHashMap<>();
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
      case VARIABLE -> updateVariablesByScopeFromVariableEvent(
          (VariableIntent) record.getIntent(), (VariableRecordValue) record.getValue());
      case PROCESS_INSTANCE -> removeVariablesByScopeFromProcessInstanceEvent(
          (ProcessInstanceIntent) record.getIntent(), record.getKey());
      case JOB -> handleJobEvent(
          (JobIntent) record.getIntent(), (JobRecordValue) record.getValue(), record.getKey());
      default -> {}
    }

    this.controller.updateLastExportedRecordPosition(record.getPosition());
  }

  private void updateVariablesByScopeFromVariableEvent(
      final VariableIntent intent, final VariableRecordValue variableRecordValue) {
    if (intent != VariableIntent.CREATED && intent != VariableIntent.UPDATED) {
      return;
    }

    if (!INPUT_VARIABLE_NAME.equals(variableRecordValue.getName())) {
      return;
    }

    final long scopeKey = variableRecordValue.getScopeKey();
    variablesByScope.put(
        scopeKey, jsonMapper.fromJson(variableRecordValue.getValue(), String.class));
  }

  private void removeVariablesByScopeFromProcessInstanceEvent(
      final ProcessInstanceIntent intent, final long scopeKey) {
    if (intent == ProcessInstanceIntent.ELEMENT_COMPLETED
        || intent == ProcessInstanceIntent.ELEMENT_TERMINATED) {
      variablesByScope.remove(scopeKey);
    }
  }

  private void handleJobEvent(final JobIntent intent, final JobRecordValue job, final long jobKey) {
    if (intent == JobIntent.CREATED) {
      completeCreatedJobUsingVariablesByScope(job, jobKey);
      return;
    }

    if (intent == JobIntent.CANCELED || intent == JobIntent.COMPLETED) {
      removeVariablesByScopeForFinishedJob(job);
    }
  }

  private void removeVariablesByScopeForFinishedJob(final JobRecordValue job) {
    variablesByScope.remove(job.getElementInstanceKey());
  }

  private void completeCreatedJobUsingVariablesByScope(
      final JobRecordValue job, final long jobKey) {
    if (!isHelloJobType(job.getType())) {
      completeDefaultCreatedJobWithDelay(job, jobKey);
      return;
    }

    final String inputVariable = variablesByScope.get(job.getElementInstanceKey());
    if (inputVariable == null) {
      failCreatedJobForMissingInputValue(jobKey, job.getElementInstanceKey());
      return;
    }

    final String greeting = GREETING_PREFIX + inputVariable + GREETING_SUFFIX;
    LOGGER.info(() -> "Greeting built by embedded worker: " + greeting);
    final Map<String, Object> outputVariables = Map.of(OUTPUT_VARIABLE_NAME, greeting);
    completeCreatedJob(job, jobKey, outputVariables, Duration.ZERO);
  }

  private boolean isHelloJobType(final String jobType) {
    if (jobType == null) {
      return false;
    }

    return HELLO_JOB_TYPES.contains(jobType.trim().toLowerCase());
  }

  private void completeDefaultCreatedJobWithDelay(final JobRecordValue job, final long jobKey) {
    final Map<String, Object> outputVariables = Map.of(DEFAULT_OUTPUT_VARIABLE_NAME, true);
    completeCreatedJob(
        job, jobKey, outputVariables, Duration.ofMillis(configuration.getJobCompletionDelayMs()));
  }

  private void completeCreatedJob(
      final JobRecordValue job,
      final long jobKey,
      final Map<String, Object> outputVariables,
      final Duration delay) {
    controller.scheduleCancellableTask(
        delay,
        () ->
            client
                .newCompleteCommand(jobKey)
                .variables(outputVariables)
                .send()
                .whenComplete(
                    (ignored, error) -> {
                      if (error == null) {
                        variablesByScope.remove(job.getElementInstanceKey());
                      }
                    }));
  }

  private void failCreatedJobForMissingInputValue(
      final long jobKey, final long elementInstanceKey) {
    client
        .newFailCommand(jobKey)
        .retries(0)
        .errorMessage(MISSING_INPUT_VALUE_ERROR_MESSAGE)
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
