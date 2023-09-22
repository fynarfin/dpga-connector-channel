package org.mifos.connector.api.implementation;

import static org.mifos.connector.channel.camel.config.CamelProperties.BATCH_ID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.mifos.connector.api.definition.TransferApi;
import org.mifos.connector.channel.utils.Headers;
import org.mifos.connector.channel.utils.SpringWrapperUtil;
import org.mifos.connector.dto.GsmaP2PResponseDto;
import org.mifos.connector.service.WorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferApiController implements TransferApi {

    @Autowired
    WorkflowService workflowService;

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Override
    public GsmaP2PResponseDto transfer(String tenant, String batchId, String correlationId, Object requestBody)
            throws JsonProcessingException {
        Headers headers = new Headers.HeaderBuilder().addHeader("Platform-TenantId", tenant).addHeader(BATCH_ID, batchId)
                .addHeader(CLIENTCORRELATIONID, correlationId).build();
        Exchange exchange = SpringWrapperUtil.getDefaultWrappedExchange(producerTemplate.getCamelContext(), headers,
                objectMapper.writeValueAsString(requestBody));
        producerTemplate.send("direct:post-transfer", exchange);
        String responseBody = exchange.getIn().getBody(String.class);
        return objectMapper.readValue(responseBody, GsmaP2PResponseDto.class);
    }

    @Override
    public void sample() {
        workflowService.startSampleWorkflow("Hello", "World");
    }
}
