package org.mifos.connector.channel.camel.routes;

import static org.mifos.connector.channel.camel.config.CamelProperties.BATCH_ID;
import static org.mifos.connector.common.mojaloop.type.InitiatorType.CONSUMER;
import static org.mifos.connector.common.mojaloop.type.Scenario.TRANSFER;
import static org.mifos.connector.common.mojaloop.type.TransactionRole.PAYER;
import static org.mifos.connector.conductor.ConductorVariables.IS_RTP_REQUEST;
import static org.mifos.connector.conductor.ConductorVariables.TENANT_ID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.component.bean.validator.BeanValidationException;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mifos.connector.channel.camel.config.ClientProperties;
import org.mifos.connector.channel.properties.TenantImplementation;
import org.mifos.connector.channel.properties.TenantImplementationProperties;
import org.mifos.connector.common.camel.AuthProcessor;
import org.mifos.connector.common.camel.AuthProperties;
import org.mifos.connector.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.connector.common.channel.dto.TransactionChannelRequestDTO;
import org.mifos.connector.common.mojaloop.dto.FspMoneyData;
import org.mifos.connector.common.mojaloop.dto.TransactionType;
import org.mifos.connector.conductor.ConductorWorkflowStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class ChannelRouteBuilder extends ErrorHandlerRouteBuilder {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    ConductorWorkflowStarter conductorWorkflowStarter;

    @Autowired
    TenantImplementationProperties tenantImplementationProperties;

    @Autowired
    ObjectMapper objectMapper;

    private String paymentTransferFlow;
    private String specialPaymentTransferFlow;
    private List<String> dfspIds;
    String destinationDfspId;

    public ChannelRouteBuilder(@Value("#{'${dfspids}'.split(',')}") List<String> dfspIds,
            @Value("${bpmn.flows.payment-transfer}") String paymentTransferFlow,
            @Value("${bpmn.flows.special-payment-transfer}") String specialPaymentTransferFlow,
            @Value("${destination.dfspid}") String destinationDfspId, @Autowired(required = false) AuthProcessor authProcessor,
            @Autowired(required = false) AuthProperties authProperties, ObjectMapper objectMapper, ClientProperties clientProperties,
            RestTemplate restTemplate) {
        super(authProcessor, authProperties);
        super.configure();
        this.paymentTransferFlow = paymentTransferFlow;
        this.specialPaymentTransferFlow = specialPaymentTransferFlow;
        this.dfspIds = dfspIds;
        this.destinationDfspId = destinationDfspId;
    }

    @Override
    public void configure() {
        handleExceptions();
        indexRoutes();
        transferRoutes();
    }

    private void handleExceptions() {
        onException(BeanValidationException.class).process(e -> {
            JSONArray violations = new JSONArray();
            e.getProperty(Exchange.EXCEPTION_CAUGHT, BeanValidationException.class).getConstraintViolations().forEach(v -> {
                violations.put(v.getPropertyPath() + " --- " + v.getMessage());
            });

            JSONObject response = new JSONObject();
            response.put("violations", violations);
            e.getIn().setBody(response.toString());
            e.removeProperties("*");
        }).setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400)).stop();

        onException(InvalidFormatException.class).process(e -> {
            JSONArray violations = new JSONArray();
            violations.put(e.getProperty(Exchange.EXCEPTION_CAUGHT, InvalidFormatException.class).getMessage());

            JSONObject response = new JSONObject();
            response.put("violations", violations);
            e.getIn().setBody(response.toString());
            e.removeProperties("*");
        }).setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400)).stop();
    }

    private void indexRoutes() {
        from("direct:get-index").setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200)).setBody(constant(""));
    }

    private void transferRoutes() {
        from("direct:post-transfer").id("inbound-transaction-request")
                .log(LoggingLevel.INFO, "## CHANNEL -> PAYER inbound transfer request: ${body}").unmarshal()
                .json(JsonLibrary.Jackson, TransactionChannelRequestDTO.class).to("bean-validator:request").process(exchange -> {
                    Map<String, Object> extraVariables = new HashMap<>();
                    extraVariables.put(IS_RTP_REQUEST, false);

                    // adding batchId zeebeVariable form header
                    String batchIdHeader = exchange.getIn().getHeader(BATCH_ID, String.class);
                    extraVariables.put(BATCH_ID, batchIdHeader);

                    String tenantId = exchange.getIn().getHeader("Platform-TenantId", String.class);
                    String clientCorrelationId = exchange.getIn().getHeader("X-CorrelationID", String.class);
                    if (tenantId == null || !dfspIds.contains(tenantId)) {
                        throw new RuntimeException("Requested tenant " + tenantId + " not configured in the connector!");
                    }
                    extraVariables.put(TENANT_ID, tenantId);

                    TransactionChannelRequestDTO channelRequest = exchange.getIn().getBody(TransactionChannelRequestDTO.class);
                    TransactionType transactionType = new TransactionType();
                    transactionType.setInitiator(PAYER);
                    transactionType.setInitiatorType(CONSUMER);
                    transactionType.setScenario(TRANSFER);
                    channelRequest.setTransactionType(transactionType);
                    channelRequest.getPayer().getPartyIdInfo().setFspId(destinationDfspId);
                    String customDataString = String.valueOf(channelRequest.getCustomData());
                    String currency = channelRequest.getAmount().getCurrency();

                    extraVariables.put("customData", customDataString);
                    extraVariables.put("currency", currency);
                    extraVariables.put("initiator", transactionType.getInitiator().name());
                    extraVariables.put("initiatorType", transactionType.getInitiatorType().name());
                    extraVariables.put("scenario", transactionType.getScenario().name());
                    extraVariables.put("amount",
                            new FspMoneyData(channelRequest.getAmount().getAmountDecimal(), channelRequest.getAmount().getCurrency()));
                    extraVariables.put("clientCorrelationId", clientCorrelationId);
                    extraVariables.put("initiatorFspId", channelRequest.getPayer().getPartyIdInfo().getFspId());
                    String tenantSpecificBpmn;
                    String bpmn = getWorkflowForTenant(tenantId, "payment-transfer");
                    if (channelRequest.getPayer().getPartyIdInfo().getPartyIdentifier().startsWith("6666")) {
                        tenantSpecificBpmn = bpmn.equals("default") ? specialPaymentTransferFlow.replace("{dfspid}", tenantId)
                                : bpmn.replace("{dfspid}", tenantId);
                        extraVariables.put("specialTermination", true);
                    } else {
                        tenantSpecificBpmn = bpmn.equals("default") ? paymentTransferFlow.replace("{dfspid}", tenantId)
                                : bpmn.replace("{dfspid}", tenantId);
                        extraVariables.put("specialTermination", false);
                    }

                    String transactionId = conductorWorkflowStarter.startWorkflow(tenantSpecificBpmn,
                            objectMapper.writeValueAsString(channelRequest), extraVariables);

                    JSONObject response = new JSONObject();
                    response.put("transactionId", transactionId);
                    exchange.getIn().setBody(response.toString());
                });
    }

    public String getWorkflowForTenant(String tenantId, String useCase) {

        for (TenantImplementation tenant : tenantImplementationProperties.getTenants()) {
            if (tenant.getId().equals(tenantId)) {
                return tenant.getFlows().getOrDefault(useCase, "default");
            }
        }
        return "default";
    }

}
