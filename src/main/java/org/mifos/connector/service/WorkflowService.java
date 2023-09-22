package org.mifos.connector.service;

import com.netflix.conductor.client.http.WorkflowClient;
import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WorkflowService {

    @Autowired
    private WorkflowClient workflowClient;

    public String startSampleWorkflow(String param1, String param2) {
        StartWorkflowRequest request = new StartWorkflowRequest();
        request.setName("mine");
        request.setVersion(1);
        request.setInput(Map.of("num1", 10, "num2", 15));

        workflowClient.setRootURI("http://conductor-server.sandbox.fynarfin.io/api/");
        return workflowClient.startWorkflow(request);
    }

    public String startSampleWorkflowFromTransfer(Map<String, Object> input) {
        StartWorkflowRequest request = new StartWorkflowRequest();
        request.setName("mine");
        request.setVersion(1);
        request.setInput(input);

        workflowClient.setRootURI("http://conductor-server.sandbox.fynarfin.io/api/");
        return workflowClient.startWorkflow(request);
    }
}
