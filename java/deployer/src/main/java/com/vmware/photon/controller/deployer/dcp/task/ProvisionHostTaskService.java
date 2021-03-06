/*
 * Copyright 2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.photon.controller.deployer.dcp.task;

import com.vmware.photon.controller.agent.gen.AgentControl;
import com.vmware.photon.controller.agent.gen.AgentStatusResponse;
import com.vmware.photon.controller.api.HostState;
import com.vmware.photon.controller.api.UsageTag;
import com.vmware.photon.controller.cloudstore.dcp.entity.DeploymentService;
import com.vmware.photon.controller.cloudstore.dcp.entity.HostService;
import com.vmware.photon.controller.common.clients.AgentControlClient;
import com.vmware.photon.controller.common.xenon.CloudStoreHelper;
import com.vmware.photon.controller.common.xenon.ControlFlags;
import com.vmware.photon.controller.common.xenon.InitializationUtils;
import com.vmware.photon.controller.common.xenon.PatchUtils;
import com.vmware.photon.controller.common.xenon.ServiceUtils;
import com.vmware.photon.controller.common.xenon.TaskUtils;
import com.vmware.photon.controller.common.xenon.ValidationUtils;
import com.vmware.photon.controller.common.xenon.upgrade.NoMigrationDuringUpgrade;
import com.vmware.photon.controller.common.xenon.validation.DefaultInteger;
import com.vmware.photon.controller.common.xenon.validation.DefaultTaskState;
import com.vmware.photon.controller.common.xenon.validation.Immutable;
import com.vmware.photon.controller.common.xenon.validation.NotNull;
import com.vmware.photon.controller.common.xenon.validation.Positive;
import com.vmware.photon.controller.deployer.dcp.DeployerContext;
import com.vmware.photon.controller.deployer.dcp.constant.ServicePortConstants;
import com.vmware.photon.controller.deployer.dcp.util.HostUtils;
import com.vmware.photon.controller.deployer.deployengine.ScriptRunner;
import com.vmware.photon.controller.nsxclient.NsxClient;
import com.vmware.photon.controller.nsxclient.models.FabricNode;
import com.vmware.photon.controller.nsxclient.models.FabricNodeCreateSpec;
import com.vmware.photon.controller.nsxclient.models.FabricNodeState;
import com.vmware.photon.controller.nsxclient.models.HostNodeLoginCredential;
import com.vmware.photon.controller.nsxclient.models.HostSwitch;
import com.vmware.photon.controller.nsxclient.models.TransportNode;
import com.vmware.photon.controller.nsxclient.models.TransportNodeCreateSpec;
import com.vmware.photon.controller.nsxclient.models.TransportNodeState;
import com.vmware.photon.controller.nsxclient.models.TransportZoneEndPoint;
import com.vmware.photon.controller.nsxclient.utils.NameUtils;
import com.vmware.photon.controller.stats.plugin.gen.StatsPluginConfig;
import com.vmware.photon.controller.stats.plugin.gen.StatsStoreType;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.OperationJoin;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.Utils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFutureTask;
import org.apache.commons.io.FileUtils;
import org.apache.thrift.async.AsyncMethodCallback;
import static com.google.common.base.Preconditions.checkState;

import javax.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * This class implements a DCP service which provisions a host and updates the corresponding cloud store documents.
 */
public class ProvisionHostTaskService extends StatefulService {

  public static final String SCRIPT_NAME = "esx-install-agent2";
  private static final String COMMA_DELIMITED_REGEX = "\\s*,\\s*";
  private static final String DEFAULT_AGENT_LOG_LEVEL = "debug";

  /**
   * This defines the total number of retries made by the task
   * to check if the Host Service has been updated with configuration
   * data. Given that the interval between each retry is 5secs,
   * the task will timeout after 1-minute approx.
   */
  private static final int HOST_UPDATE_RETRY_COUNT = 12;

  /**
   * This class defines the state of a {@link ProvisionHostTaskService} task.
   */
  public static class TaskState extends com.vmware.xenon.common.TaskState {

    /**
     * This type defines the possible sub-states for this task.
     */
    public enum SubStage {
      PROVISION_NETWORK,
      INSTALL_AGENT,
      WAIT_FOR_INSTALLATION,
      PROVISION_AGENT,
      WAIT_FOR_PROVISION,
      UPDATE_HOST_STATE,
      WAIT_FOR_HOST_UPDATES,
    }

    /**
     * This value defines the state of the current task.
     */
    public SubStage subStage;
  }

  /**
   * This class defines the document state associated with a {@link ProvisionHostTaskService} task.
   */
  @NoMigrationDuringUpgrade
  public static class State extends ServiceDocument {

    /**
     * This value represents the state of the current task.
     */
    @DefaultTaskState(value = TaskState.TaskStage.CREATED)
    public TaskState taskState;

    /**
     * This value represents the control flags for the current task.
     */
    @DefaultInteger(value = 0)
    public Integer controlFlags;

    /**
     * This value represents the document link of the {@link HostService} object which represents
     * the host to be provisioned.
     */
    @NotNull
    @Immutable
    public String hostServiceLink;

    /**
     * This value represents the document link of the {@link DeploymentService} object which
     * represents the deployment in whose context the task is being performed.
     */
    @NotNull
    @Immutable
    public String deploymentServiceLink;

    /**
     * This value represents the absolute path to the uploaded VIB image on the host.
     */
    @NotNull
    @Immutable
    public String vibPath;

    /**
     * This value represents the maximum number of agent status polling iterations which should be attempted before
     * declaring failure.
     */
    @DefaultInteger(value = 60)
    @Positive
    @Immutable
    public Integer maximumPollCount;

    /**
     * This value represents the interval, in milliseconds, between agent status polling iterations.
     */
    @DefaultInteger(value = 5000)
    @Positive
    @Immutable
    public Integer pollInterval;

    /**
     * This value is used for counting polling iterations for two stages:
     * 1) retrieving agent provisioning status
     * 2) waiting for host configuration to get updated.
     */
    @DefaultInteger(value = 0)
    public Integer pollCount;
  }

  /**
   * This method provides a default constructor for {@link ProvisionHostTaskService} objects.
   */
  public ProvisionHostTaskService() {
    super(State.class);
    this.toggleOption(ServiceOption.OWNER_SELECTION, true);
    this.toggleOption(ServiceOption.PERSISTENCE, true);
    this.toggleOption(ServiceOption.REPLICATION, true);
  }

  /**
   * This method is called when a service instance is started.
   *
   * @param operation Supplies the {@link Operation} which triggered the service start.
   */
  @Override
  public void handleStart(Operation operation) {
    ServiceUtils.logTrace(this, "Handling start operation");
    State startState = operation.getBody(State.class);
    InitializationUtils.initialize(startState);

    validateState(startState);

    if (startState.taskState.stage == TaskState.TaskStage.CREATED) {
      startState.taskState.stage = TaskState.TaskStage.STARTED;
      startState.taskState.subStage = TaskState.SubStage.PROVISION_NETWORK;
    }

    if (startState.documentExpirationTimeMicros <= 0) {
      startState.documentExpirationTimeMicros =
          ServiceUtils.computeExpirationTime(ServiceUtils.DEFAULT_DOC_EXPIRATION_TIME_MICROS);
    }

    operation.setBody(startState).complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(startState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping start operation processing (disabled)");
      } else if (startState.taskState.stage == TaskState.TaskStage.STARTED) {
        TaskUtils.sendSelfPatch(this, buildPatch(startState.taskState.stage, startState.taskState.subStage, null));
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  /**
   * This method is called when a service instance receives a patch.
   *
   * @param operation Supplies the {@link Operation} which triggered the patch.
   */
  @Override
  public void handlePatch(Operation operation) {
    ServiceUtils.logTrace(this, "Handling patch operation");
    State currentState = getState(operation);
    State patchState = operation.getBody(State.class);
    validatePatchState(currentState, patchState);
    PatchUtils.patchState(currentState, patchState);
    validateState(currentState);
    operation.complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(currentState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping patch operation processing (disabled)");
      } else if (currentState.taskState.stage == TaskState.TaskStage.STARTED) {
        processStartedStage(currentState);
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  private void validateState(State state) {
    ValidationUtils.validateState(state);
    validateTaskState(state.taskState);
  }

  private void validatePatchState(State currentState, State patchState) {
    ValidationUtils.validatePatch(currentState, patchState);
    validateTaskState(patchState.taskState);
    validateTaskStageProgression(currentState.taskState, patchState.taskState);
  }

  private void validateTaskState(TaskState taskState) {
    ValidationUtils.validateTaskStage(taskState);
    switch (taskState.stage) {
      case CREATED:
      case FINISHED:
      case FAILED:
      case CANCELLED:
        checkState(taskState.subStage == null);
        break;
      case STARTED:
        checkState(taskState.subStage != null);
        switch (taskState.subStage) {
          case PROVISION_NETWORK:
          case INSTALL_AGENT:
          case WAIT_FOR_INSTALLATION:
          case PROVISION_AGENT:
          case WAIT_FOR_PROVISION:
          case UPDATE_HOST_STATE:
          case WAIT_FOR_HOST_UPDATES:
            break;
          default:
            throw new IllegalStateException("Unknown task sub-stage: " + taskState.subStage);
        }
    }
  }

  private void validateTaskStageProgression(TaskState currentState, TaskState patchState) {
    ValidationUtils.validateTaskStageProgression(currentState, patchState);
    if (currentState.subStage != null && patchState.subStage != null) {
      checkState(patchState.subStage.ordinal() >= currentState.subStage.ordinal());
    }
  }

  private void processStartedStage(State currentState) {
    switch (currentState.taskState.subStage) {
      case PROVISION_NETWORK:
        processProvisionNetworkSubStage(currentState);
        break;
      case INSTALL_AGENT:
        processInstallAgentSubStage(currentState);
        break;
      case WAIT_FOR_INSTALLATION:
        processWaitForInstallationSubStage(currentState);
        break;
      case PROVISION_AGENT:
        processProvisionAgentSubStage(currentState);
        break;
      case WAIT_FOR_PROVISION:
        processWaitForProvisionSubStage(currentState);
        break;
      case UPDATE_HOST_STATE:
        processUpdateHostState(currentState);
        break;
      case WAIT_FOR_HOST_UPDATES:
        processWaitForHostUpdates(currentState);
        break;
    }
  }

  //
  // PROVISION_NETWORK sub-stage routines
  //

  private void processProvisionNetworkSubStage(State currentState) {

    HostUtils.getCloudStoreHelper(this)
        .createGet(currentState.deploymentServiceLink)
        .setCompletion((op, ex) -> {
          if (ex != null) {
            failTask(ex);
            return;
          }

          try {
            processProvisionNetworkSubStage(currentState, op.getBody(DeploymentService.State.class));
          } catch (Throwable t) {
            failTask(t);
          }
        })
        .sendWith(this);
  }

  private void processProvisionNetworkSubStage(State currentState, DeploymentService.State deploymentState) {

    if (!deploymentState.virtualNetworkEnabled) {
      ServiceUtils.logInfo(this, "Skip setting up virtual network (disabled)");
      sendStageProgressPatch(currentState, TaskState.TaskStage.STARTED, TaskState.SubStage.INSTALL_AGENT);
      return;
    }

    HostUtils.getCloudStoreHelper(this)
        .createGet(currentState.hostServiceLink)
        .setCompletion((op, ex) -> {
          if (ex != null) {
            failTask(ex);
            return;
          }

          try {
            registerFabricNode(currentState, deploymentState, op.getBody(HostService.State.class));
          } catch (Throwable t) {
            failTask(t);
          }
        })
        .sendWith(this);
  }

  private void registerFabricNode(State currentState,
                                  DeploymentService.State deploymentState,
                                  HostService.State hostState) {

    if (hostState.nsxFabricNodeId != null) {
      ServiceUtils.logInfo(this, "Skip registering fabric node");
      createTransportNode(currentState, deploymentState, hostState, hostState.nsxFabricNodeId);
      return;
    }

    try {
      NsxClient nsxClient = HostUtils.getNsxClientFactory(this).create(
          deploymentState.networkManagerAddress,
          deploymentState.networkManagerUsername,
          deploymentState.networkManagerPassword);

      FabricNodeCreateSpec request = new FabricNodeCreateSpec();
      request.setDisplayName(NameUtils.getFabricNodeName(hostState.hostAddress));
      request.setDescription(NameUtils.getFabricNodeDescription(hostState.hostAddress));
      request.setIpAddresses(Arrays.asList(hostState.hostAddress));
      request.setOsType("ESXI");
      request.setResourceType("HostNode");
      HostNodeLoginCredential hostNodeLoginCredential = new HostNodeLoginCredential();
      hostNodeLoginCredential.setUsername(hostState.userName);
      hostNodeLoginCredential.setPassword(hostState.password);
      hostNodeLoginCredential.setThumbprint(nsxClient.getHostThumbprint(
          hostState.hostAddress,
          ServicePortConstants.ESXI_PORT));
      request.setHostCredential(hostNodeLoginCredential);

      ObjectMapper om = new ObjectMapper();
      om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
      String payload = om.writeValueAsString(request);
      ServiceUtils.logInfo(this, "FC request: " + payload);

      nsxClient.getFabricApi().registerFabricNode(request,
          new FutureCallback<FabricNode>() {
            @Override
            public void onSuccess(@Nullable FabricNode response) {
              waitForRegisterFabricNode(currentState, deploymentState, hostState,
                  response.getId());
            }

            @Override
            public void onFailure(Throwable throwable) {
              failTask(throwable);
            }
          });
    } catch (Throwable t) {
      failTask(t);
    }
  }

  private void waitForRegisterFabricNode(State currentState,
                                         DeploymentService.State deploymentState,
                                         HostService.State hostState,
                                         String fabricNodeId) {

    getHost().schedule(() -> {
          try {
            NsxClient nsxClient = HostUtils.getNsxClientFactory(this).create(
                deploymentState.networkManagerAddress,
                deploymentState.networkManagerUsername,
                deploymentState.networkManagerPassword);

            nsxClient.getFabricApi().getFabricNodeState(fabricNodeId,
                new FutureCallback<FabricNodeState>() {
                  @Override
                  public void onSuccess(@Nullable FabricNodeState response) {
                    switch (response.getState()) {
                      case SUCCESS:
                        createTransportNode(currentState, deploymentState, hostState, fabricNodeId);
                        break;
                      case PENDING:
                      case IN_PROGRESS:
                        waitForRegisterFabricNode(currentState, deploymentState, hostState, fabricNodeId);
                        break;
                      case FAILED:
                      case PARTIAL_SUCCESS:
                      case ORPHANED:
                        failTask(new IllegalStateException(
                            String.format("Failed to register host as fabric node: %s", response.toString())));
                        break;
                    }
                  }

                  @Override
                  public void onFailure(Throwable throwable) {
                    failTask(throwable);
                  }
                });
          } catch (Throwable t) {
            failTask(t);
          }
        },
        currentState.pollInterval,
        TimeUnit.MILLISECONDS);
  }

  private void createTransportNode(State currentState,
                                   DeploymentService.State deploymentState,
                                   HostService.State hostState,
                                   String fabricNodeId) {

    if (hostState.nsxTransportNodeId != null) {
      ServiceUtils.logInfo(this, "Skip creating transport node");
      sendStageProgressPatch(currentState, TaskState.TaskStage.STARTED, TaskState.SubStage.INSTALL_AGENT);
      return;
    }

    try {
      NsxClient nsxClient = HostUtils.getNsxClientFactory(this).create(
          deploymentState.networkManagerAddress,
          deploymentState.networkManagerUsername,
          deploymentState.networkManagerPassword);

      TransportNodeCreateSpec request = new TransportNodeCreateSpec();
      request.setDisplayName(NameUtils.getTransportNodeName(hostState.hostAddress));
      request.setDescription(NameUtils.getTransportNodeDescription(hostState.hostAddress));
      request.setNodeId(fabricNodeId);
      HostSwitch hostSwitch = new HostSwitch();
      hostSwitch.setName(NameUtils.HOST_SWITCH_NAME);
      request.setHostSwitches(Arrays.asList(hostSwitch));
      if (deploymentState.networkZoneId != null) {
        TransportZoneEndPoint transportZoneEndPoint = new TransportZoneEndPoint();
        transportZoneEndPoint.setTransportZoneId(deploymentState.networkZoneId);
        request.setTransportZoneEndPoints(Arrays.asList(transportZoneEndPoint));
      }

      nsxClient.getFabricApi().createTransportNode(request,
          new FutureCallback<TransportNode>() {
            @Override
            public void onSuccess(@Nullable TransportNode response) {
              waitForCreateTransportNode(currentState, deploymentState, fabricNodeId, response.getId());
            }

            @Override
            public void onFailure(Throwable throwable) {
              failTask(throwable);
            }
          });
    } catch (Throwable t) {
      failTask(t);
    }
  }

  private void waitForCreateTransportNode(State currentState,
                                          DeploymentService.State deploymentState,
                                          String fabricNodeId,
                                          String transportNodeId) {

    getHost().schedule(() -> {
          try {
            NsxClient nsxClient = HostUtils.getNsxClientFactory(this).create(
                deploymentState.networkManagerAddress,
                deploymentState.networkManagerUsername,
                deploymentState.networkManagerPassword);

            nsxClient.getFabricApi().getTransportNodeState(transportNodeId,
                new FutureCallback<TransportNodeState>() {
                  @Override
                  public void onSuccess(@Nullable TransportNodeState response) {
                    switch (response.getState()) {
                      case SUCCESS:
                        HostService.State hostPatch = new HostService.State();
                        hostPatch.nsxFabricNodeId = fabricNodeId;
                        hostPatch.nsxTransportNodeId = transportNodeId;

                        patchHost(currentState, hostPatch, TaskState.TaskStage.STARTED,
                            TaskState.SubStage.INSTALL_AGENT);
                        break;
                      case PENDING:
                      case IN_PROGRESS:
                        waitForCreateTransportNode(currentState, deploymentState, fabricNodeId, transportNodeId);
                        break;
                      case PARTIAL_SUCCESS:
                      case FAILED:
                      case ORPHANED:
                        failTask(new IllegalStateException(
                            String.format("Failed to create transport node for host: %s", response.toString())));
                        break;
                    }
                  }

                  @Override
                  public void onFailure(Throwable throwable) {
                    failTask(throwable);
                  }
                });
          } catch (Throwable t) {
            failTask(t);
          }
        },
        currentState.pollInterval,
        TimeUnit.MILLISECONDS);
  }

  //
  // INSTALL_AGENT sub-stage routines
  //

  private void processInstallAgentSubStage(State currentState) {

    CloudStoreHelper cloudStoreHelper = HostUtils.getCloudStoreHelper(this);
    Operation deploymentOp = cloudStoreHelper.createGet(currentState.deploymentServiceLink);
    Operation hostOp = cloudStoreHelper.createGet(currentState.hostServiceLink);

    OperationJoin
        .create(hostOp, deploymentOp)
        .setCompletion((ops, exs) -> {
          if (exs != null && !exs.isEmpty()) {
            failTask(exs.values());
            return;
          }

          try {
            processInstallAgentSubStage(currentState,
                ops.get(deploymentOp.getId()).getBody(DeploymentService.State.class),
                ops.get(hostOp.getId()).getBody(HostService.State.class));
          } catch (Throwable t) {
            failTask(t);
          }
        })
        .sendWith(this);

  }

  private void processInstallAgentSubStage(State currentState,
                                           DeploymentService.State deploymentState,
                                           HostService.State hostState) {

    List<String> command = new ArrayList<>();
    command.add("./" + SCRIPT_NAME);
    command.add(hostState.hostAddress);
    command.add(hostState.userName);
    command.add(hostState.password);
    command.add(currentState.vibPath);

    if (deploymentState.syslogEndpoint != null) {
      command.add("-l");
      command.add(deploymentState.syslogEndpoint);
    }

    DeployerContext deployerContext = HostUtils.getDeployerContext(this);

    File scriptLogFile = new File(deployerContext.getScriptLogDirectory(), SCRIPT_NAME + "-" +
        hostState.hostAddress + "-" + ServiceUtils.getIDFromDocumentSelfLink(currentState.documentSelfLink) + ".log");

    ScriptRunner scriptRunner = new ScriptRunner.Builder(command, deployerContext.getScriptTimeoutSec())
        .directory(deployerContext.getScriptDirectory())
        .redirectOutput(ProcessBuilder.Redirect.to(scriptLogFile))
        .build();

    ListenableFutureTask<Integer> futureTask = ListenableFutureTask.create(scriptRunner);
    HostUtils.getListeningExecutorService(this).submit(futureTask);
    Futures.addCallback(futureTask, new FutureCallback<Integer>() {
      @Override
      public void onSuccess(@Nullable Integer result) {
        if (result == null) {
          failTask(new NullPointerException(SCRIPT_NAME + " returned null"));
        } else if (result != 0) {
          logScriptErrorAndFail(hostState, result, scriptLogFile);
        } else {
          sendStageProgressPatch(currentState, TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_INSTALLATION);
        }
      }

      @Override
      public void onFailure(Throwable throwable) {
        failTask(throwable);
      }
    });
  }

  private void logScriptErrorAndFail(HostService.State hostState, Integer result, File scriptLogFile) {

    try {
      ServiceUtils.logSevere(this, SCRIPT_NAME + " returned " + result.toString());
      ServiceUtils.logSevere(this, "Script output: " + FileUtils.readFileToString(scriptLogFile));
    } catch (Throwable t) {
      ServiceUtils.logSevere(this, t);
    }

    failTask(new IllegalStateException("Deploying the agent to host " + hostState.hostAddress +
        " failed with exit code " + result.toString()));
  }

  //
  // WAIT_FOR_INSTALLATION sub-stage routines
  //

  private void processWaitForInstallationSubStage(State currentState) {

    HostUtils.getCloudStoreHelper(this)
        .createGet(currentState.hostServiceLink)
        .setCompletion((op, ex) -> {
          if (ex != null) {
            failTask(ex);
            return;
          }

          try {
            processWaitForInstallationSubStage(currentState, op.getBody(HostService.State.class));
          } catch (Throwable t) {
            failTask(t);
          }
        })
        .sendWith(this);
  }

  private void processWaitForInstallationSubStage(State currentState, HostService.State hostState) {
    try {
      AgentControlClient agentControlClient = HostUtils.getAgentControlClient(this);
      agentControlClient.setIpAndPort(hostState.hostAddress, hostState.agentPort);
      agentControlClient.getAgentStatus(new AsyncMethodCallback<AgentControl.AsyncClient.get_agent_status_call>() {
        @Override
        public void onComplete(AgentControl.AsyncClient.get_agent_status_call getAgentStatusCall) {
          try {
            AgentStatusResponse agentStatusResponse = getAgentStatusCall.getResult();
            AgentControlClient.ResponseValidator.checkAgentStatusResponse(agentStatusResponse, hostState.hostAddress);
            sendStageProgressPatch(currentState, TaskState.TaskStage.STARTED, TaskState.SubStage.PROVISION_AGENT);
          } catch (Throwable t) {
            retryGetInstallationStatusOrFail(currentState, hostState, t);
          }
        }

        @Override
        public void onError(Exception e) {
          retryGetInstallationStatusOrFail(currentState, hostState, e);
        }
      });
    } catch (Throwable t) {
      retryGetInstallationStatusOrFail(currentState, hostState, t);
    }
  }

  private void retryGetInstallationStatusOrFail(State currentState, HostService.State hostState, Throwable t) {
    if (currentState.pollCount + 1 >= currentState.maximumPollCount) {
      ServiceUtils.logSevere(this, t);
      State patchState = buildPatch(TaskState.TaskStage.FAILED, null, new IllegalStateException(
          "The agent on host " + hostState.hostAddress + " failed to become ready after installation after " +
              Integer.toString(currentState.maximumPollCount) + " retries"));
      patchState.pollCount = currentState.pollCount + 1;
      TaskUtils.sendSelfPatch(this, patchState);
    } else {
      ServiceUtils.logTrace(this, t);
      State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_INSTALLATION, null);
      patchState.pollCount = currentState.pollCount + 1;
      getHost().schedule(() -> TaskUtils.sendSelfPatch(this, patchState), currentState.pollInterval,
          TimeUnit.MILLISECONDS);
    }
  }

  //
  // PROVISION_AGENT sub-stage routines
  //

  private void processProvisionAgentSubStage(State currentState) {

    CloudStoreHelper cloudStoreHelper = HostUtils.getCloudStoreHelper(this);
    Operation deploymentOp = cloudStoreHelper.createGet(currentState.deploymentServiceLink);
    Operation hostOp = cloudStoreHelper.createGet(currentState.hostServiceLink);

    OperationJoin
        .create(deploymentOp, hostOp)
        .setCompletion(
            (ops, exs) -> {
              if (exs != null && !exs.isEmpty()) {
                failTask(exs.values());
                return;
              }

              try {
                processProvisionAgentSubStage(currentState,
                    ops.get(deploymentOp.getId()).getBody(DeploymentService.State.class),
                    ops.get(hostOp.getId()).getBody(HostService.State.class));
              } catch (Throwable t) {
                failTask(t);
              }
            })
        .sendWith(this);
  }

  private void processProvisionAgentSubStage(State currentState,
                                             DeploymentService.State deploymentState,
                                             HostService.State hostState) {

    List<String> datastores = null;
    if (hostState.metadata != null
        && hostState.metadata.containsKey(HostService.State.METADATA_KEY_NAME_ALLOWED_DATASTORES)) {
      String[] allowedDatastores = hostState.metadata.get(HostService.State.METADATA_KEY_NAME_ALLOWED_DATASTORES)
          .trim().split(COMMA_DELIMITED_REGEX);
      datastores = new ArrayList<>(allowedDatastores.length);
      Collections.addAll(datastores, allowedDatastores);
    }

    List<String> networks = null;
    if (hostState.metadata != null
        && hostState.metadata.containsKey(HostService.State.METADATA_KEY_NAME_ALLOWED_NETWORKS)) {
      String[] allowedNetworks = hostState.metadata.get(HostService.State.METADATA_KEY_NAME_ALLOWED_NETWORKS)
          .trim().split(COMMA_DELIMITED_REGEX);
      networks = new ArrayList<>(allowedNetworks.length);
      Collections.addAll(networks, allowedNetworks);
    }

    StatsPluginConfig statsPluginConfig = new StatsPluginConfig(deploymentState.statsEnabled);

    if (deploymentState.statsStoreEndpoint != null) {
      statsPluginConfig.setStats_store_endpoint(deploymentState.statsStoreEndpoint);
    }

    if (deploymentState.statsStorePort != null) {
      statsPluginConfig.setStats_store_port(deploymentState.statsStorePort);
    }

    if (deploymentState.statsStoreType != null) {
      statsPluginConfig.setStats_store_type(StatsStoreType.findByValue(deploymentState.statsStoreType.ordinal()));
    }

    if (hostState.usageTags != null) {
      // Agent accepts stats' tags as comma separated string.
      // Concatenate usageTags as one tag for stats so that they could be
      // queried easily. For example, user can query all metrics
      // having tag equal to 'MGMT-CLOUD' or '*MGMT*'.
      List<String> usageTagList = new ArrayList<>(hostState.usageTags);
      Collections.sort(usageTagList);
      statsPluginConfig.setStats_host_tags(Joiner.on("-").skipNulls().join(usageTagList));
    }

    try {
      AgentControlClient agentControlClient = HostUtils.getAgentControlClient(this);
      agentControlClient.setIpAndPort(hostState.hostAddress, hostState.agentPort);
      agentControlClient.provision(
          datastores,
          deploymentState.imageDataStoreNames,
          deploymentState.imageDataStoreUsedForVMs,
          networks,
          hostState.hostAddress,
          hostState.agentPort,
          0, // Overcommit ratio is not implemented,
          deploymentState.syslogEndpoint,
          DEFAULT_AGENT_LOG_LEVEL,
          statsPluginConfig,
          (hostState.usageTags != null
              && hostState.usageTags.contains(UsageTag.MGMT.name())
              && !hostState.usageTags.contains(UsageTag.CLOUD.name())),
          ServiceUtils.getIDFromDocumentSelfLink(currentState.hostServiceLink),
          ServiceUtils.getIDFromDocumentSelfLink(currentState.deploymentServiceLink),
          deploymentState.ntpEndpoint,
          new AsyncMethodCallback<AgentControl.AsyncClient.provision_call>() {
            @Override
            public void onComplete(AgentControl.AsyncClient.provision_call provisionCall) {
              try {
                AgentControlClient.ResponseValidator.checkProvisionResponse(provisionCall.getResult());
                sendStageProgressPatch(currentState, TaskState.TaskStage.STARTED,
                    TaskState.SubStage.WAIT_FOR_PROVISION);
              } catch (Throwable t) {
                logProvisioningErrorAndFail(hostState, t);
              }
            }

            @Override
            public void onError(Exception e) {
              logProvisioningErrorAndFail(hostState, e);
            }
          });

    } catch (Throwable t) {
      logProvisioningErrorAndFail(hostState, t);
    }
  }

  //
  // WAIT_FOR_PROVISION sub-stage routines
  //

  private void processWaitForProvisionSubStage(State currentState) {

    HostUtils.getCloudStoreHelper(this)
        .createGet(currentState.hostServiceLink)
        .setCompletion((o, e) -> {
          if (e != null) {
            failTask(e);
            return;
          }

          try {
            processWaitForProvisionSubStage(currentState, o.getBody(HostService.State.class));
          } catch (Throwable t) {
            failTask(t);
          }
        })
        .sendWith(this);
  }

  private void processWaitForProvisionSubStage(State currentState, HostService.State hostState) {
    try {
      AgentControlClient agentControlClient = HostUtils.getAgentControlClient(this);
      agentControlClient.setIpAndPort(hostState.hostAddress, hostState.agentPort);
      agentControlClient.getAgentStatus(new AsyncMethodCallback<AgentControl.AsyncClient.get_agent_status_call>() {
        @Override
        public void onComplete(AgentControl.AsyncClient.get_agent_status_call getAgentStatusCall) {
          try {
            AgentStatusResponse agentStatusResponse = getAgentStatusCall.getResult();
            AgentControlClient.ResponseValidator.checkAgentStatusResponse(agentStatusResponse, hostState.hostAddress);
            sendStageProgressPatch(currentState, TaskState.TaskStage.STARTED, TaskState.SubStage.UPDATE_HOST_STATE);
          } catch (Throwable t) {
            retryGetProvisionStatusOrFail(currentState, hostState, t);
          }
        }

        @Override
        public void onError(Exception e) {
          retryGetProvisionStatusOrFail(currentState, hostState, e);
        }
      });
    } catch (Throwable t) {
      retryGetProvisionStatusOrFail(currentState, hostState, t);
    }
  }

  private void retryGetProvisionStatusOrFail(State currentState, HostService.State hostState, Throwable failure) {
    if (currentState.pollCount + 1 >= currentState.maximumPollCount) {
      ServiceUtils.logSevere(this, failure);
      State patchState = buildPatch(TaskState.TaskStage.FAILED, null, new IllegalStateException(
          "The agent on host " + hostState.hostAddress + " failed to become ready after provisioning after " +
              Integer.toString(currentState.maximumPollCount) + " retries"));
      patchState.pollCount = currentState.pollCount + 1;
      TaskUtils.sendSelfPatch(this, patchState);
    } else {
      ServiceUtils.logTrace(this, failure);
      State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_PROVISION, null);
      patchState.pollCount = currentState.pollCount + 1;
      getHost().schedule(() -> TaskUtils.sendSelfPatch(this, patchState), currentState.pollInterval,
          TimeUnit.MILLISECONDS);
    }
  }

  //
  // UPDATE_HOST_STATE sub-stage routines
  //

  private void processUpdateHostState(State currentState) {
    HostService.State hostPatchState = new HostService.State();
    hostPatchState.state = HostState.READY;

    State patchState = buildPatch(
        TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_HOST_UPDATES, null);
    patchState.pollCount = 0;

    patchHost(currentState, hostPatchState, patchState);
  }

  //
  // WAIT_FOR_HOST_Updates sub-stage routines
  //

  private void processWaitForHostUpdates(State currentState) {
    try {
      HostUtils.getCloudStoreHelper(this)
          .createGet(currentState.hostServiceLink)
          .setCompletion((o, e) -> {
            if (e != null) {
              retryWaitForHostUpdatesOrFail(currentState, e);
              return;
            }
            try {
              HostService.State hostState = o.getBody(HostService.State.class);
              if (hostState.esxVersion == null) {
                retryWaitForHostUpdatesOrFail(currentState, null);
                return;
              }
              TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FINISHED, null, null));
            } catch (Throwable t) {
              retryWaitForHostUpdatesOrFail(currentState, t);
            }
          })
          .sendWith(this);
    } catch (Throwable t) {
      retryWaitForHostUpdatesOrFail(currentState, t);
    }
  }

  private void retryWaitForHostUpdatesOrFail(State currentState, Throwable failure) {
    if (failure != null) {
      ServiceUtils.logSevere(this, failure);
    }
    if (currentState.pollCount + 1 >= HOST_UPDATE_RETRY_COUNT) {
      State patchState = buildPatch(TaskState.TaskStage.FAILED, null, new IllegalStateException(
          "The hostService " + currentState.hostServiceLink + " failed to get updated with configuration details " +
              "after " + Integer.toString(currentState.maximumPollCount) + " retries"));
      patchState.pollCount = currentState.pollCount + 1;
      TaskUtils.sendSelfPatch(this, patchState);
    } else {
      State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_HOST_UPDATES, null);
      patchState.pollCount = currentState.pollCount + 1;
      getHost().schedule(() -> TaskUtils.sendSelfPatch(this, patchState), currentState.pollInterval,
          TimeUnit.MILLISECONDS);
    }
  }

  //
  // Utility routines
  //

  private void patchHost(State currentState,
                         HostService.State patchState,
                         TaskState.TaskStage nextStage,
                         TaskState.SubStage nextSubStage) {
    patchHost(currentState, patchState, buildPatch(nextStage, nextSubStage, null));
  }

  private void patchHost(State currentState,
                         HostService.State hostPatchState,
                         State patchState) {
    HostUtils.getCloudStoreHelper(this)
        .createPatch(currentState.hostServiceLink)
        .setBody(hostPatchState)
        .setCompletion((o, e) -> {
          if (e != null) {
            failTask(e);
          } else {
            sendStageProgressPatch(currentState, patchState);
          }
        })
        .sendWith(this);
  }

  private void sendStageProgressPatch(State currentState,
                                      TaskState.TaskStage taskStage,
                                      TaskState.SubStage subStage) {
    sendStageProgressPatch(currentState, buildPatch(taskStage, subStage, null));
  }

  private void sendStageProgressPatch(State currentState, State patchState) {
    ServiceUtils.logInfo(this, "Sending self-patch to stage %s : %s",
        patchState.taskState.stage, patchState.taskState.subStage);
    if (ControlFlags.disableOperationProcessingOnStageTransition(currentState.controlFlags)) {
      patchState.controlFlags = ControlFlags.CONTROL_FLAG_OPERATION_PROCESSING_DISABLED;
    }
    TaskUtils.sendSelfPatch(this, patchState);
  }

  private void logProvisioningErrorAndFail(HostService.State hostState, Throwable failure) {
    ServiceUtils.logSevere(this, failure);
    TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FAILED, null, new IllegalStateException(
        "Provisioning the agent on host " + hostState.hostAddress + " failed with error: " + failure)));
  }

  private void failTask(Throwable failure) {
    ServiceUtils.logSevere(this, failure);
    TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FAILED, null, failure));
  }

  private void failTask(Collection<Throwable> failures) {
    ServiceUtils.logSevere(this, failures);
    TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FAILED, null, failures.iterator().next()));
  }

  @VisibleForTesting
  protected static State buildPatch(TaskState.TaskStage taskStage,
                                    TaskState.SubStage subStage,
                                    @Nullable Throwable t) {
    State patchState = new State();
    patchState.taskState = new TaskState();
    patchState.taskState.stage = taskStage;
    patchState.taskState.subStage = subStage;

    if (t != null) {
      patchState.taskState.failure = Utils.toServiceErrorResponse(t);
    }

    return patchState;
  }
}
