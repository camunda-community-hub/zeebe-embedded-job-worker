package org.camunda.community.extension.zeebe.exporter.jobworker;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.exporter.api.Exporter;
import io.camunda.zeebe.exporter.api.context.Context;
import io.camunda.zeebe.exporter.api.context.Context.RecordFilter;
import io.camunda.zeebe.exporter.api.context.Controller;
import io.camunda.zeebe.protocol.record.RecordType;
import io.camunda.zeebe.protocol.record.ValueType;
import io.camunda.zeebe.protocol.record.intent.JobIntent;
import java.util.Set;

public class EmbeddedJobWorker implements Exporter {

  private Controller controller;
  private ZeebeClient client;

  @Override
  public void configure(final Context context) throws Exception {
    context.setFilter(
        new RecordFilter() {
          private static final Set<ValueType> ACCEPTED_VALUE_TYPES = Set.of(ValueType.JOB);

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
        ZeebeClient.newClientBuilder()
            .usePlaintext()
            .gatewayAddress("camunda-zeebe-gateway:26500")
            .build();
  }

  @Override
  public void close() {
    client.close();
  }

  @Override
  public void export(io.camunda.zeebe.protocol.record.Record<?> record) {
    if (record.getIntent() == JobIntent.CREATED) {
      client.newCompleteCommand(record.getKey()).send();
    }
    this.controller.updateLastExportedRecordPosition(record.getPosition());
  }
}
