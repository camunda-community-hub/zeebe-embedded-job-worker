package org.camunda.community.extension.zeebe.exporter.jobworker;

import io.camunda.client.api.JsonMapper;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.response.DocumentReferenceResponse;
import io.camunda.client.api.response.UserTaskProperties;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.zeebe.protocol.record.value.JobRecordValue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class EmbeddedActivatedJob implements ActivatedJob {
  private final long key;
  private final JobRecordValue job;
  private final JsonMapper jsonMapper;
  private final Map<String, Object> scopedVariables;

  EmbeddedActivatedJob(
      final long key,
      final JobRecordValue job,
      final JsonMapper jsonMapper,
      final Map<String, Object> scopedVariables) {
    this.key = key;
    this.job = job;
    this.jsonMapper = jsonMapper;
    this.scopedVariables = Map.copyOf(scopedVariables);
  }

  @Override
  public long getKey() {
    return key;
  }

  @Override
  public String getType() {
    return job.getType();
  }

  @Override
  public long getProcessInstanceKey() {
    return job.getProcessInstanceKey();
  }

  @Override
  public String getBpmnProcessId() {
    return job.getBpmnProcessId();
  }

  @Override
  public int getProcessDefinitionVersion() {
    return job.getProcessDefinitionVersion();
  }

  @Override
  public long getProcessDefinitionKey() {
    return job.getProcessDefinitionKey();
  }

  @Override
  public String getElementId() {
    return job.getElementId();
  }

  @Override
  public long getElementInstanceKey() {
    return job.getElementInstanceKey();
  }

  @Override
  public Map<String, String> getCustomHeaders() {
    final Map<String, String> customHeaders = job.getCustomHeaders();
    return customHeaders == null ? Map.of() : customHeaders;
  }

  @Override
  public String getWorker() {
    return job.getWorker();
  }

  @Override
  public int getRetries() {
    return job.getRetries();
  }

  @Override
  public long getDeadline() {
    return job.getDeadline();
  }

  @Override
  public String getVariables() {
    return jsonMapper.toJson(scopedVariables);
  }

  @Override
  public Map<String, Object> getVariablesAsMap() {
    return scopedVariables;
  }

  @Override
  public <T> T getVariablesAsType(final Class<T> variableTypeClass) {
    return jsonMapper.fromJson(getVariables(), variableTypeClass);
  }

  @Override
  public Object getVariable(final String variableName) {
    return scopedVariables.get(variableName);
  }

  @Override
  public UserTaskProperties getUserTask() {
    return null;
  }

  @Override
  public JobKind getKind() {
    final io.camunda.zeebe.protocol.record.value.JobKind jobKind = job.getJobKind();
    if (jobKind == null) {
      return JobKind.UNKNOWN_ENUM_VALUE;
    }

    try {
      return JobKind.valueOf(jobKind.name());
    } catch (IllegalArgumentException ignored) {
      return JobKind.UNKNOWN_ENUM_VALUE;
    }
  }

  @Override
  public ListenerEventType getListenerEventType() {
    final io.camunda.zeebe.protocol.record.value.JobListenerEventType listenerEventType =
        job.getJobListenerEventType();
    if (listenerEventType == null) {
      return ListenerEventType.UNKNOWN_ENUM_VALUE;
    }

    try {
      return ListenerEventType.valueOf(listenerEventType.name());
    } catch (IllegalArgumentException ignored) {
      return ListenerEventType.UNKNOWN_ENUM_VALUE;
    }
  }

  @Override
  public String toJson() {
    final Map<String, Object> json = new HashMap<>();
    json.put("key", key);
    json.put("type", getType());
    json.put("processInstanceKey", getProcessInstanceKey());
    json.put("elementInstanceKey", getElementInstanceKey());
    json.put("variables", scopedVariables);
    return jsonMapper.toJson(json);
  }

  @Override
  public String getTenantId() {
    return job.getTenantId();
  }

  @Override
  public List<DocumentReferenceResponse> getDocumentReferences(final String variableName) {
    return List.of();
  }

  @Override
  public Set<String> getTags() {
    return job.getTags();
  }
}
