/*
 * Copyright 2026 Conductor Authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.conductor.core.execution;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.netflix.conductor.common.metadata.tasks.TaskType;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.core.config.ConductorProperties;
import com.netflix.conductor.core.dal.ExecutionDAOFacade;
import com.netflix.conductor.core.exception.TransientException;
import com.netflix.conductor.core.execution.tasks.SubWorkflow;
import com.netflix.conductor.core.execution.tasks.WorkflowSystemTask;
import com.netflix.conductor.core.utils.IDGenerator;
import com.netflix.conductor.core.utils.ParametersUtils;
import com.netflix.conductor.dao.MetadataDAO;
import com.netflix.conductor.dao.QueueDAO;
import com.netflix.conductor.model.TaskModel;
import com.netflix.conductor.model.WorkflowModel;
import com.netflix.conductor.service.ExecutionLockService;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TestAsyncSystemTaskExecutor {

    private static final String PARENT_WORKFLOW_ID = "parent-workflow";
    private static final String PARENT_TASK_ID = "sub-workflow-task";
    private static final String CHILD_WORKFLOW_NAME = "child-workflow";

    private ExecutionDAOFacade executionDAOFacade;
    private QueueDAO queueDAO;
    private WorkflowExecutor workflowExecutor;
    private AsyncSystemTaskExecutor executor;
    private IDGenerator idGenerator;
    private ExecutionLockService executionLockService;

    @Before
    public void setUp() {
        executionDAOFacade = mock(ExecutionDAOFacade.class);
        queueDAO = mock(QueueDAO.class);
        MetadataDAO metadataDAO = mock(MetadataDAO.class);
        ConductorProperties properties = mock(ConductorProperties.class);
        workflowExecutor = mock(WorkflowExecutor.class);
        ParametersUtils parametersUtils = mock(ParametersUtils.class);
        executionLockService = mock(ExecutionLockService.class);
        idGenerator = new IDGenerator();

        when(properties.getSystemTaskWorkerCallbackDuration()).thenReturn(Duration.ofSeconds(30));
        when(properties.getTaskExecutionPostponeDuration()).thenReturn(Duration.ofSeconds(1));
        when(parametersUtils.substituteSecrets(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        executor =
                new AsyncSystemTaskExecutor(
                        executionDAOFacade,
                        queueDAO,
                        metadataDAO,
                        properties,
                        workflowExecutor,
                        parametersUtils,
                        executionLockService);
    }

    @Test
    public void testExpiredNonIdempotentStartTimesOut() {
        TaskModel task = expiredScheduledTask("non-idempotent");
        WorkflowModel workflow = runningParentWorkflow();
        NonIdempotentSystemTask systemTask = new NonIdempotentSystemTask();
        mockExecution(task, workflow, true);

        executor.execute(systemTask, task.getTaskId());

        assertEquals(TaskModel.Status.TIMED_OUT, task.getStatus());
        assertFalse(systemTask.started);
        verify(queueDAO, never()).setUnackTimeout(anyString(), anyString(), anyLong());
        verify(queueDAO).remove(systemTask.getTaskType(), task.getTaskId());
        verify(workflowExecutor).decide(PARENT_WORKFLOW_ID);
    }

    @Test
    public void testExpiredSubWorkflowStartCreatesMissingDeterministicChild() {
        TaskModel task = expiredSubWorkflowTask();
        WorkflowModel parent = runningParentWorkflow();
        WorkflowModel child = childWorkflow(WorkflowModel.Status.RUNNING);
        mockExecution(task, parent, false);
        when(workflowExecutor.startWorkflowIdempotent(any())).thenReturn(child);

        executor.execute(new SubWorkflow(new ObjectMapper(), idGenerator), task.getTaskId());

        String expectedChildId =
                idGenerator.generateSubWorkflowId(
                        PARENT_WORKFLOW_ID, PARENT_TASK_ID, task.getRetryCount());
        assertEquals(TaskModel.Status.IN_PROGRESS, task.getStatus());
        assertEquals(expectedChildId, task.getSubWorkflowId());
        assertNull(task.getReasonForIncompletion());
        assertEquals(0, task.getRetryCount());
        verifyDeterministicStart(expectedChildId);
        verify(queueDAO).remove(TaskType.TASK_TYPE_SUB_WORKFLOW, PARENT_TASK_ID);
        verify(workflowExecutor).decide(PARENT_WORKFLOW_ID);
    }

    @Test
    public void testStartedSubWorkflowRedeliveryUsesRecoveryBeforeResponseTimeout() {
        TaskModel task = startedSubWorkflowTask();
        WorkflowModel parent = runningParentWorkflow();
        WorkflowModel failedChild = childWorkflow(WorkflowModel.Status.FAILED);
        mockExecution(task, parent, false);
        when(workflowExecutor.startWorkflowIdempotent(any())).thenReturn(failedChild);

        executor.execute(new SubWorkflow(new ObjectMapper(), idGenerator), task.getTaskId());

        String expectedChildId =
                idGenerator.generateSubWorkflowId(
                        PARENT_WORKFLOW_ID, PARENT_TASK_ID, task.getRetryCount());
        assertEquals(TaskModel.Status.FAILED, task.getStatus());
        assertEquals(expectedChildId, task.getSubWorkflowId());
        verifyDeterministicStart(expectedChildId);
    }

    @Test
    public void testInterruptedRerunRecoversItsPersistedReplacementChild() {
        TaskModel task = startedSubWorkflowTask();
        task.addOutput(SubWorkflow.SUB_WORKFLOW_LAUNCH_ID, "replacement-child");
        WorkflowModel parent = runningParentWorkflow();
        WorkflowModel replacementChild = childWorkflow(WorkflowModel.Status.RUNNING);
        replacementChild.setWorkflowId("replacement-child");
        mockExecution(task, parent, false);
        when(workflowExecutor.startWorkflowIdempotent(any())).thenReturn(replacementChild);

        executor.execute(new SubWorkflow(new ObjectMapper(), idGenerator), task.getTaskId());

        assertEquals(TaskModel.Status.IN_PROGRESS, task.getStatus());
        assertEquals("replacement-child", task.getSubWorkflowId());
        ArgumentCaptor<StartWorkflowInput> inputCaptor =
                ArgumentCaptor.forClass(StartWorkflowInput.class);
        verify(workflowExecutor).startWorkflowIdempotent(inputCaptor.capture());
        assertEquals("replacement-child", inputCaptor.getValue().getWorkflowId());
    }

    @Test
    public void testTransientRecoveryFailurePreservesStartedAttemptForNextDelivery() {
        TaskModel task = startedSubWorkflowTask();
        long originalUpdateTime = task.getUpdateTime();
        WorkflowModel parent = runningParentWorkflow();
        when(executionDAOFacade.getTaskModel(PARENT_TASK_ID)).thenReturn(task);
        when(executionDAOFacade.getWorkflowModel(PARENT_WORKFLOW_ID, false)).thenReturn(parent);
        when(workflowExecutor.startWorkflowIdempotent(any()))
                .thenThrow(new TransientException("busy"));

        executor.execute(new SubWorkflow(new ObjectMapper(), idGenerator), task.getTaskId());

        assertEquals(TaskModel.Status.SCHEDULED, task.getStatus());
        assertEquals(originalUpdateTime, task.getUpdateTime());
        verify(executionDAOFacade, never()).updateTask(task);
        verifyDeterministicStart(
                idGenerator.generateSubWorkflowId(PARENT_WORKFLOW_ID, PARENT_TASK_ID, 0));
        verify(queueDAO)
                .postpone(
                        TaskType.TASK_TYPE_SUB_WORKFLOW,
                        PARENT_TASK_ID,
                        task.getWorkflowPriority(),
                        1);
        verify(queueDAO, never())
                .postpone(
                        TaskType.TASK_TYPE_SUB_WORKFLOW,
                        PARENT_TASK_ID,
                        task.getWorkflowPriority(),
                        30);
    }

    @Test
    public void testLateOriginalStartCannotRegressRecoveredTerminalTask() {
        TaskModel staleOriginal = expiredSubWorkflowTask();
        TaskModel recovered = expiredSubWorkflowTask();
        recovered.setStatus(TaskModel.Status.COMPLETED);
        WorkflowModel parent = runningParentWorkflow();
        WorkflowModel child = childWorkflow(WorkflowModel.Status.RUNNING);
        when(executionDAOFacade.getTaskModel(PARENT_TASK_ID))
                .thenReturn(staleOriginal)
                .thenReturn(recovered);
        when(executionDAOFacade.getWorkflowModel(PARENT_WORKFLOW_ID, false)).thenReturn(parent);
        when(workflowExecutor.startWorkflowIdempotent(any())).thenReturn(child);

        executor.execute(new SubWorkflow(new ObjectMapper(), idGenerator), PARENT_TASK_ID);

        assertEquals(TaskModel.Status.IN_PROGRESS, staleOriginal.getStatus());
        assertEquals(TaskModel.Status.COMPLETED, recovered.getStatus());
        verify(executionDAOFacade, never()).updateTask(staleOriginal);
        verify(queueDAO).remove(TaskType.TASK_TYPE_SUB_WORKFLOW, PARENT_TASK_ID);
        verify(workflowExecutor).decide(PARENT_WORKFLOW_ID);
    }

    @Test
    public void testInitialStartDoesNotRegressTerminalTaskPersistedAfterLoad() {
        TaskModel staleStart = scheduledSubWorkflowTask();
        TaskModel completed = scheduledSubWorkflowTask();
        completed.setStatus(TaskModel.Status.COMPLETED);
        WorkflowModel parent = runningParentWorkflow();
        when(executionDAOFacade.getTaskModel(PARENT_TASK_ID))
                .thenReturn(staleStart)
                .thenReturn(completed);
        when(executionDAOFacade.getWorkflowModel(PARENT_WORKFLOW_ID, false)).thenReturn(parent);

        executor.execute(new SubWorkflow(new ObjectMapper(), idGenerator), PARENT_TASK_ID);

        assertEquals(TaskModel.Status.COMPLETED, completed.getStatus());
        verify(executionDAOFacade, never()).updateTask(staleStart);
        verify(workflowExecutor, never()).startWorkflowIdempotent(any());
        verify(queueDAO).remove(TaskType.TASK_TYPE_SUB_WORKFLOW, PARENT_TASK_ID);
        verify(workflowExecutor).decide(PARENT_WORKFLOW_ID);
    }

    @Test
    public void testRecoveryPersistenceFailurePreservesExpiredStartForNextDelivery() {
        TaskModel task = expiredSubWorkflowTask();
        long expiredUpdateTime = task.getUpdateTime();
        WorkflowModel parent = runningParentWorkflow();
        WorkflowModel child = childWorkflow(WorkflowModel.Status.COMPLETED);
        when(executionDAOFacade.getTaskModel(PARENT_TASK_ID)).thenReturn(task).thenReturn(null);
        when(executionDAOFacade.getWorkflowModel(PARENT_WORKFLOW_ID, false)).thenReturn(parent);
        when(workflowExecutor.startWorkflowIdempotent(any())).thenReturn(child);

        executor.execute(new SubWorkflow(new ObjectMapper(), idGenerator), PARENT_TASK_ID);

        assertEquals(expiredUpdateTime, task.getUpdateTime());
        verify(executionDAOFacade, never()).updateTask(task);
        verify(queueDAO)
                .postpone(
                        TaskType.TASK_TYPE_SUB_WORKFLOW,
                        PARENT_TASK_ID,
                        task.getWorkflowPriority(),
                        1);
        verify(queueDAO, never()).remove(anyString(), anyString());
    }

    @Test
    public void testRecoveryReservationFailurePreservesExpiredStartForNextDelivery() {
        TaskModel task = expiredSubWorkflowTask();
        long expiredUpdateTime = task.getUpdateTime();
        WorkflowModel parent = runningParentWorkflow();
        when(executionDAOFacade.getTaskModel(PARENT_TASK_ID)).thenReturn(task);
        when(executionDAOFacade.getWorkflowModel(PARENT_WORKFLOW_ID, false)).thenReturn(parent);
        when(queueDAO.setUnackTimeout(TaskType.TASK_TYPE_SUB_WORKFLOW, PARENT_TASK_ID, 1_000))
                .thenThrow(new RuntimeException("queue unavailable"));

        executor.execute(new SubWorkflow(new ObjectMapper(), idGenerator), PARENT_TASK_ID);

        assertEquals(expiredUpdateTime, task.getUpdateTime());
        verify(executionDAOFacade, never()).updateTask(task);
        verify(workflowExecutor, never()).startWorkflowIdempotent(any());
        verify(queueDAO)
                .postpone(
                        TaskType.TASK_TYPE_SUB_WORKFLOW,
                        PARENT_TASK_ID,
                        task.getWorkflowPriority(),
                        1);
    }

    @Test
    public void testExpiredSubWorkflowStartReattachesCompletedDeterministicChild() {
        TaskModel task = expiredSubWorkflowTask();
        WorkflowModel parent = runningParentWorkflow();
        WorkflowModel child = childWorkflow(WorkflowModel.Status.COMPLETED);
        child.setOutput(Map.of("result", "complete"));
        mockExecution(task, parent, false);
        when(workflowExecutor.startWorkflowIdempotent(any())).thenReturn(child);

        executor.execute(new SubWorkflow(new ObjectMapper(), idGenerator), task.getTaskId());

        String expectedChildId =
                idGenerator.generateSubWorkflowId(
                        PARENT_WORKFLOW_ID, PARENT_TASK_ID, task.getRetryCount());
        assertEquals(TaskModel.Status.COMPLETED, task.getStatus());
        assertEquals(expectedChildId, task.getSubWorkflowId());
        assertEquals("complete", task.getOutputData().get("result"));
        assertEquals(0, task.getRetryCount());
        verifyDeterministicStart(expectedChildId);
        verify(queueDAO).remove(TaskType.TASK_TYPE_SUB_WORKFLOW, PARENT_TASK_ID);
        verify(workflowExecutor).decide(PARENT_WORKFLOW_ID);
    }

    private void verifyDeterministicStart(String expectedChildId) {
        ArgumentCaptor<StartWorkflowInput> inputCaptor =
                ArgumentCaptor.forClass(StartWorkflowInput.class);
        verify(workflowExecutor).startWorkflowIdempotent(inputCaptor.capture());
        StartWorkflowInput input = inputCaptor.getValue();
        assertEquals(expectedChildId, input.getWorkflowId());
        assertEquals(PARENT_WORKFLOW_ID, input.getParentWorkflowId());
        assertEquals(PARENT_TASK_ID, input.getParentWorkflowTaskId());
    }

    private void mockExecution(
            TaskModel task, WorkflowModel workflow, boolean taskRetrievalRequired) {
        when(executionDAOFacade.getTaskModel(task.getTaskId()))
                .thenReturn(task)
                .thenReturn(task.copy());
        when(executionDAOFacade.getWorkflowModel(
                        task.getWorkflowInstanceId(), taskRetrievalRequired))
                .thenReturn(workflow);
    }

    private TaskModel expiredSubWorkflowTask() {
        TaskModel task = scheduledSubWorkflowTask();
        long beforeResponseTimeout = System.currentTimeMillis() - 2_000;
        task.setStartTime(beforeResponseTimeout);
        task.setUpdateTime(beforeResponseTimeout);
        task.setResponseTimeoutSeconds(1);
        return task;
    }

    private TaskModel startedSubWorkflowTask() {
        TaskModel task = scheduledSubWorkflowTask();
        long recentStart = System.currentTimeMillis() - 100;
        task.setStartTime(recentStart);
        task.setUpdateTime(recentStart);
        task.setResponseTimeoutSeconds(3_600);
        return task;
    }

    private TaskModel scheduledSubWorkflowTask() {
        TaskModel task = scheduledTask(TaskType.TASK_TYPE_SUB_WORKFLOW);
        task.setTaskDefName(TaskType.TASK_TYPE_SUB_WORKFLOW);
        task.setInputData(
                new HashMap<>(
                        Map.of(
                                "subWorkflowName",
                                CHILD_WORKFLOW_NAME,
                                "subWorkflowVersion",
                                1,
                                "workflowInput",
                                Map.of("input", "value"))));
        return task;
    }

    private TaskModel expiredScheduledTask(String taskType) {
        TaskModel task = scheduledTask(taskType);
        long beforeResponseTimeout = System.currentTimeMillis() - 2_000;
        task.setStartTime(beforeResponseTimeout);
        task.setUpdateTime(beforeResponseTimeout);
        task.setResponseTimeoutSeconds(1);
        return task;
    }

    private TaskModel scheduledTask(String taskType) {
        TaskModel task = new TaskModel();
        task.setTaskId(PARENT_TASK_ID);
        task.setTaskType(taskType);
        task.setTaskDefName(taskType);
        task.setReferenceTaskName(taskType + "_ref");
        task.setWorkflowInstanceId(PARENT_WORKFLOW_ID);
        task.setStatus(TaskModel.Status.SCHEDULED);
        task.setInputData(new HashMap<>());
        task.setScheduledTime(System.currentTimeMillis());
        return task;
    }

    private WorkflowModel runningParentWorkflow() {
        WorkflowModel workflow = new WorkflowModel();
        workflow.setWorkflowId(PARENT_WORKFLOW_ID);
        workflow.setWorkflowDefinition(workflowDefinition("parent-workflow"));
        workflow.setStatus(WorkflowModel.Status.RUNNING);
        return workflow;
    }

    private WorkflowModel childWorkflow(WorkflowModel.Status status) {
        WorkflowModel workflow = new WorkflowModel();
        workflow.setWorkflowId(
                idGenerator.generateSubWorkflowId(PARENT_WORKFLOW_ID, PARENT_TASK_ID, 0));
        workflow.setWorkflowDefinition(workflowDefinition(CHILD_WORKFLOW_NAME));
        workflow.setStatus(status);
        return workflow;
    }

    private WorkflowDef workflowDefinition(String name) {
        WorkflowDef workflowDef = new WorkflowDef();
        workflowDef.setName(name);
        return workflowDef;
    }

    private static final class NonIdempotentSystemTask extends WorkflowSystemTask {

        private boolean started;

        private NonIdempotentSystemTask() {
            super("non-idempotent");
        }

        @Override
        public void start(
                WorkflowModel workflow, TaskModel task, WorkflowExecutor workflowExecutor) {
            started = true;
        }
    }
}
