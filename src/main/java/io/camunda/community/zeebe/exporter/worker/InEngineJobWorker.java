package io.camunda.community.zeebe.exporter.worker;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.Topology;
import io.camunda.zeebe.exporter.api.Exporter;
import io.camunda.zeebe.exporter.api.context.Context;
import io.camunda.zeebe.exporter.api.context.Context.RecordFilter;
import io.camunda.zeebe.exporter.api.context.Controller;
import io.camunda.zeebe.protocol.record.RecordType;
import io.camunda.zeebe.protocol.record.ValueType;
import io.camunda.zeebe.protocol.record.intent.JobIntent;

public class InEngineJobWorker implements Exporter {

    private Controller controller;
    private ZeebeClient client;
    private Logger log = LoggerFactory.getLogger(getClass().getPackageName());

    @Override
    public void configure(final Context context) throws Exception {
      log = context.getLogger();
      context.setFilter(
          new RecordFilter() {
            private static final Set<ValueType> ACCEPTED_VALUE_TYPES =
                Set.of(ValueType.JOB);
  
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
        this.client = ZeebeClient.newClientBuilder()
          .usePlaintext()
          .gatewayAddress("camunda-zeebe-gateway")
          .build();
        log.info("Zeebe client initialized.");
        Topology topology = client.newTopologyRequest()
          .send()
          .join();
        log.debug("Exporter sees a topology with " + topology.getClusterSize() + " clusters.");
    }

    @Override
    public void close() {
      client.close();
    }

    @Override
    public void export(io.camunda.zeebe.protocol.record.Record<?> record) {
        log.debug(record.toJson());
        if (record.getIntent() == JobIntent.CREATED) {
          long jobKey = record.getKey();
          client.newCompleteCommand(jobKey).send();
        }
        this.controller.updateLastExportedRecordPosition(record.getPosition());
    }

}
