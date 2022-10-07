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
			if (record.getValueType() == ValueType.JOB) {
				Map<String, Object> processOutputVars = new HashMap<String, Object>();
				try {
					JobRecordValue value = (JobRecordValue) record.getValue();

					// get the input variables
					Map<String, Object> processInputVars = value.getVariables();
					String payload = processInputVars.get("BusMsg").toString();
					JsonObject msg = new Gson().fromJson(payload, JsonObject.class); // new
																						// JsonParser().parse(payload).getAsJsonObject();
					//extract values from process variable

					JsonObject appHdr = getObject("AppHdr", msg);
					String frFinInstnId = getValue("Fr.FIId.FinInstnId.BICFI", appHdr);
					String toFinInstnId = getValue("To.FIId.FinInstnId.BICFI", appHdr);
					JsonObject document = getObject("Document", msg);
					JsonObject fitoFICstmrCdtTrf = getObject("FIToFICstmrCdtTrf", document);
					JsonObject GrpHdr = getObject("GrpHdr", fitoFICstmrCdtTrf);
					String msgId = getValue("MsgId", GrpHdr);
					String creDtTm = getValue("CreDtTm", GrpHdr);
					String sttlmMtd = getValue("SttlmInf.SttlmMtd", GrpHdr);
					String bICFI = getValue("InstgAgt.FinInstnId.BICFI", GrpHdr);
					JsonObject cdtTrfTxInf = getObject("CdtTrfTxInf", fitoFICstmrCdtTrf);
					String strtNm = getValue("Dbtr.PstlAdr.StrtNm", cdtTrfTxInf);
					String instrId = getValue("PmtId.InstrId", cdtTrfTxInf);
					String otherId = getValue("DbtrAcct.Id.Othr.Id", cdtTrfTxInf);
					String dbtrAgtBICFI = getValue("DbtrAgt.FinInstnId.BICFI", cdtTrfTxInf);
					String ctstrtNm = getValue("Cdtr.PstlAdr.StrtNm", cdtTrfTxInf);
					String cd = getValue("RmtInf.Strd.RfrdDocInf.Tp.CdOrPrtry.Cd", cdtTrfTxInf);

					JsonObject newAddressFields = new JsonObject();
					newAddressFields.addProperty("cd", cd);
					newAddressFields.addProperty("ctstrtNm", ctstrtNm);
					newAddressFields.addProperty("otherId", otherId);

					

					// compute output message

					processOutputVars.put("JobType", value.getType());
					processOutputVars.put("newAddress", newAddressFields);
					// complete job with variables
				} catch (Exception e) {

				}
        //complete the job
				client.newCompleteCommand(record.getKey()).variables(processOutputVars).send();
			}
		}
		this.controller.updateLastExportedRecordPosition(record.getPosition());
	}
//reusable method to get the field's value in a given json based on the jsonpath
	public String getValue(String path, JsonObject msg) {
		String returnVal = "";

		try {
			String[] fields = path.split("\\.");

			for (int i = 0; i < fields.length; i++) {
				if (fields.length - 1 > i) {
					msg = (JsonObject) msg.get(fields[i]);
				} else {
					returnVal = msg.get(fields[i]).getAsString(); // (String) msg.get(fields[i]);
				}
			}
		} catch (Exception e) {
			System.out.println(e.getMessage());
			return returnVal;
		}
		return returnVal;
	}
//reusable method to get the field as object in a given json based on the jsonpath
	public JsonObject getObject(String path, JsonObject msg) {
		JsonObject returnVal = new JsonObject();

		try {
			String[] fields = path.split("\\.");

			for (int i = 0; i < fields.length; i++) {
				msg = (JsonObject) msg.get(fields[i]);
			}
		} catch (Exception e) {
			System.out.println(e.getMessage());
			return msg;
		}
		return msg;
	}

}
