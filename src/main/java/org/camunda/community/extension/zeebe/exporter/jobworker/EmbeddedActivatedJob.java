package org.camunda.community.extension.zeebe.exporter.jobworker;

import io.camunda.client.api.JsonMapper;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.response.DocumentReferenceResponse;
import io.camunda.client.api.response.UserTaskProperties;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.zeebe.protocol.record.value.JobRecordValue;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class EmbeddedActivatedJob implements ActivatedJob {
  private final io.camunda.zeebe.protocol.record.Record<?> jobRecord;
  private final JobRecordValue job;
  private final JsonMapper jsonMapper;
  private final Map<String, String> scopedVariableValues;
  private final Map<String, Object> scopedVariables;

  EmbeddedActivatedJob(
      final io.camunda.zeebe.protocol.record.Record<?> jobRecord,
      final JsonMapper jsonMapper,
      final Map<String, String> scopedVariableValues) {
    this.jobRecord = jobRecord;
    this.job = (JobRecordValue) jobRecord.getValue();
    this.jsonMapper = jsonMapper;
    this.scopedVariableValues = scopedVariableValues;
    scopedVariables = new LazyParsedVariablesMap(scopedVariableValues, jsonMapper);
  }

  @Override
  public long getKey() {
    return jobRecord.getKey();
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
    return job.getCustomHeaders();
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
    final StringBuilder variableBuilder = new StringBuilder("{");
    final Iterator<Map.Entry<String, String>> iterator = scopedVariableValues.entrySet().iterator();
    while (iterator.hasNext()) {
      final Map.Entry<String, String> variable = iterator.next();
      variableBuilder.append(jsonMapper.toJson(variable.getKey())).append(':');
      variableBuilder.append(variable.getValue());
      if (iterator.hasNext()) {
        variableBuilder.append(',');
      }
    }
    variableBuilder.append('}');
    return variableBuilder.toString();
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
    return JobKind.valueOf(job.getJobKind().name());
  }

  @Override
  public ListenerEventType getListenerEventType() {
    return ListenerEventType.valueOf(job.getJobListenerEventType().name());
  }

  @Override
  public String toJson() {
    final Map<String, Object> json = new HashMap<>();
    json.put("key", getKey());
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

  @Override
  public Long getRootProcessInstanceKey() {
    return null;
  }

  private static final class LazyParsedVariablesMap extends AbstractMap<String, Object> {
    private final Map<String, String> valuesByName;
    private final JsonMapper jsonMapper;
    private final ConcurrentMap<String, Object> parsedValues = new ConcurrentHashMap<>();

    private LazyParsedVariablesMap(
        final Map<String, String> valuesByName, final JsonMapper jsonMapper) {
      this.valuesByName = valuesByName;
      this.jsonMapper = jsonMapper;
    }

    @Override
    public Object get(final Object key) {
      if (!(key instanceof String variableName)) {
        return null;
      }
      final String value = valuesByName.get(variableName);
      if (value == null) {
        return null;
      }
      return parsedValues.computeIfAbsent(
          variableName, ignored -> jsonMapper.fromJson(value, Object.class));
    }

    @Override
    public boolean containsKey(final Object key) {
      return valuesByName.containsKey(key);
    }

    @Override
    public int size() {
      return valuesByName.size();
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
      return new AbstractSet<>() {
        @Override
        public Iterator<Map.Entry<String, Object>> iterator() {
          final Iterator<String> keys = valuesByName.keySet().iterator();
          return new Iterator<>() {
            @Override
            public boolean hasNext() {
              return keys.hasNext();
            }

            @Override
            public Map.Entry<String, Object> next() {
              final String key = keys.next();
              return new AbstractMap.SimpleEntry<>(key, LazyParsedVariablesMap.this.get(key));
            }
          };
        }

        @Override
        public int size() {
          return valuesByName.size();
        }
      };
    }
  }
}
