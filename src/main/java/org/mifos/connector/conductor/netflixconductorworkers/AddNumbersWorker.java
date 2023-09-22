package org.mifos.connector.conductor.netflixconductorworkers;

import com.netflix.conductor.client.worker.Worker;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.TaskResult;

public class AddNumbersWorker implements Worker {

    private final String taskDefName;

    public AddNumbersWorker(String taskDefName) {
        this.taskDefName = taskDefName;
    }

    @Override
    public String getTaskDefName() {
        return taskDefName;
    }

    @Override
    public TaskResult execute(Task task) {
        TaskResult result = new TaskResult(task);
        int num1 = (Integer) task.getInputData().get("num1");
        int num2 = (Integer) task.getInputData().get("num2");

        result.addOutputData("sum", num1 + num2);
        result.setStatus(TaskResult.Status.COMPLETED);
        return result;
    }

}
