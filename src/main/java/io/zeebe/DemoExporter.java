package io.zeebe;

import io.camunda.zeebe.exporter.api.Exporter;
import io.camunda.zeebe.exporter.api.context.Context;
import io.camunda.zeebe.exporter.api.context.Controller;

//see for batch treatment https://camunda.com/blog/2019/06/exporter-part-2/
public class DemoExporter implements Exporter {
    Controller controller;
    @Override
    public void configure(Context context) {

    }
    @Override
    public void open(Controller controller) {
        this.controller = controller;
    }
    @Override
    public void close() {

    }
    @Override
    public void export(io.camunda.zeebe.protocol.record.Record<?> record) {
        System.out.println(record.toJson());
        this.controller.updateLastExportedRecordPosition(record.getPosition());
    }

}
