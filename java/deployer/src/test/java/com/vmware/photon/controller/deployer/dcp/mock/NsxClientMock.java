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

package com.vmware.photon.controller.deployer.dcp.mock;

import com.vmware.photon.controller.nsxclient.NsxClient;
import com.vmware.photon.controller.nsxclient.apis.FabricApi;
import com.vmware.photon.controller.nsxclient.models.FabricNode;
import com.vmware.photon.controller.nsxclient.models.FabricNodeCreateSpec;
import com.vmware.photon.controller.nsxclient.models.FabricNodeState;
import com.vmware.photon.controller.nsxclient.models.TransportNode;
import com.vmware.photon.controller.nsxclient.models.TransportNodeCreateSpec;
import com.vmware.photon.controller.nsxclient.models.TransportNodeState;
import com.vmware.photon.controller.nsxclient.models.TransportZone;
import com.vmware.photon.controller.nsxclient.models.TransportZoneCreateSpec;

import com.google.common.util.concurrent.FutureCallback;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * This class implements a mock {@link NsxClientMock} object for use in testing.
 */
public class NsxClientMock extends NsxClient {
  private FabricApi mockFabricApi;

  private NsxClientMock(FabricApi mockFabricApi) {
    super("1.2.3.4", "username", "password");
    this.mockFabricApi = mockFabricApi;
  }

  @Override
  public FabricApi getFabricApi() {
    return mockFabricApi;
  }

  @Override
  public String getHostThumbprint(String ipAddress, int port) {
    return "hostThumbprint";
  }

  /**
   * This class implements a builder for {@link NsxClientMock} objects.
   */
  public static class Builder {
    private FabricApi mockFabricApi;

    public Builder() {
      mockFabricApi = mock(FabricApi.class);
    }

    public Builder registerFabricNode(boolean isSuccess,
                                      String fabricNodeId) throws Throwable {
      if (isSuccess) {
        FabricNode response = new FabricNode();
        response.setId(fabricNodeId);
        doAnswer(invocation -> {
          ((FutureCallback<FabricNode>) invocation.getArguments()[1])
              .onSuccess(response);
          return null;
        }).when(mockFabricApi).registerFabricNode(any(FabricNodeCreateSpec.class), any(FutureCallback.class));

        FabricNodeState stateResponse = new FabricNodeState();
        stateResponse.setState(com.vmware.photon.controller.nsxclient.datatypes.FabricNodeState.SUCCESS);
        doAnswer(invocation -> {
          ((FutureCallback<FabricNodeState>) invocation.getArguments()[1])
              .onSuccess(stateResponse);
          return null;
        }).when(mockFabricApi).getFabricNodeState(any(String.class), any(FutureCallback.class));
      } else {
        RuntimeException error = new RuntimeException("registerFabricNode failed");
        doAnswer(invocation -> {
          ((FutureCallback<FabricNode>) invocation.getArguments()[1])
              .onFailure(error);
          return null;
        }).when(mockFabricApi).registerFabricNode(any(FabricNodeCreateSpec.class), any(FutureCallback.class));
      }

      return this;
    }

    public Builder createTransportNode(boolean isSuccess,
                                       String transportNodeId) throws Throwable {
      if (isSuccess) {
        TransportNode response = new TransportNode();
        response.setId(transportNodeId);
        doAnswer(invocation -> {
          ((FutureCallback<TransportNode>) invocation.getArguments()[1])
              .onSuccess(response);
          return null;
        }).when(mockFabricApi).createTransportNode(any(TransportNodeCreateSpec.class),
            any(FutureCallback.class));

        TransportNodeState stateResponse = new TransportNodeState();
        stateResponse.setState(com.vmware.photon.controller.nsxclient.datatypes.TransportNodeState.SUCCESS);
        doAnswer(invocation -> {
          ((FutureCallback<TransportNodeState>) invocation.getArguments()[1])
              .onSuccess(stateResponse);
          return null;
        }).when(mockFabricApi).getTransportNodeState(any(String.class), any(FutureCallback.class));
      } else {
        RuntimeException error = new RuntimeException("createTransportNode failed");
        doAnswer(invocation -> {
          ((FutureCallback<TransportNode>) invocation.getArguments()[1])
              .onFailure(error);
          return null;
        }).when(mockFabricApi).createTransportNode(any(TransportNodeCreateSpec.class),
            any(FutureCallback.class));
      }

      return this;
    }

    public Builder createTransportZone(boolean isSuccess,
                                       String transportZoneId) throws Throwable {
      if (isSuccess) {
        TransportZone response = new TransportZone();
        response.setId(transportZoneId);
        doAnswer(invocation -> {
          ((FutureCallback<TransportZone>) invocation.getArguments()[1])
              .onSuccess(response);
          return null;
        }).when(mockFabricApi).createTransportZone(any(TransportZoneCreateSpec.class), any(FutureCallback.class));
      } else {
        RuntimeException error = new RuntimeException("createTransportZone failed");
        doAnswer(invocation -> {
          ((FutureCallback<TransportZone>) invocation.getArguments()[1])
              .onFailure(error);
          return null;
        }).when(mockFabricApi).createTransportZone(any(TransportZoneCreateSpec.class),
            any(FutureCallback.class));
      }

      return this;
    }

    public Builder unregisterFabricNode(boolean isSuccess) throws Throwable {
      if (isSuccess) {
        doAnswer(invocation -> {
          ((FutureCallback<Void>) invocation.getArguments()[1])
              .onSuccess(null);
          return null;
        }).when(mockFabricApi).unregisterFabricNode(any(String.class), any(FutureCallback.class));
      } else {
        RuntimeException error = new RuntimeException("unregisterFabricNode failed");
        doAnswer(invocation -> {
          ((FutureCallback<Void>) invocation.getArguments()[1])
              .onFailure(error);
          return null;
        }).when(mockFabricApi).unregisterFabricNode(any(String.class), any(FutureCallback.class));
      }

      return this;
    }

    public Builder deleteTransportNode(boolean isSuccess) throws Throwable {
      if (isSuccess) {
        doAnswer(invocation -> {
          ((FutureCallback<Void>) invocation.getArguments()[1])
              .onSuccess(null);
          return null;
        }).when(mockFabricApi).deleteTransportNode(any(String.class), any(FutureCallback.class));
      } else {
        RuntimeException error = new RuntimeException("deleteTransportNode failed");
        doAnswer(invocation -> {
          ((FutureCallback<Void>) invocation.getArguments()[1])
              .onFailure(error);
          return null;
        }).when(mockFabricApi).deleteTransportNode(any(String.class), any(FutureCallback.class));
      }

      return this;
    }

    public Builder deleteTransportZone(boolean isSuccess) throws Throwable {
      if (isSuccess) {
        doAnswer(invocation -> {
          ((FutureCallback<Void>) invocation.getArguments()[1])
              .onSuccess(null);
          return null;
        }).when(mockFabricApi).deleteTransportZone(any(String.class), any(FutureCallback.class));
      } else {
        RuntimeException error = new RuntimeException("deleteTransportZone failed");
        doAnswer(invocation -> {
          ((FutureCallback<Void>) invocation.getArguments()[1])
              .onFailure(error);
          return null;
        }).when(mockFabricApi).deleteTransportZone(any(String.class), any(FutureCallback.class));
      }

      return this;
    }

    public NsxClientMock build() {
      return new NsxClientMock(mockFabricApi);
    }
  }
}
