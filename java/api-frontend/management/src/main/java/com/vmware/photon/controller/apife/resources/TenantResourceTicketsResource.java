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

package com.vmware.photon.controller.apife.resources;

import com.vmware.photon.controller.api.ResourceList;
import com.vmware.photon.controller.api.ResourceTicket;
import com.vmware.photon.controller.api.ResourceTicketCreateSpec;
import com.vmware.photon.controller.api.Task;
import com.vmware.photon.controller.api.common.exceptions.external.ExternalException;
import com.vmware.photon.controller.apife.clients.ResourceTicketFeClient;
import com.vmware.photon.controller.apife.resources.routes.ResourceTicketResourceRoutes;
import com.vmware.photon.controller.apife.resources.routes.TaskResourceRoutes;
import com.vmware.photon.controller.apife.resources.routes.TenantResourceRoutes;
import static com.vmware.photon.controller.api.common.Responses.generateCustomResponse;
import static com.vmware.photon.controller.api.common.Responses.generateResourceListResponse;

import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import com.wordnik.swagger.annotations.ApiResponse;
import com.wordnik.swagger.annotations.ApiResponses;
import io.dropwizard.validation.Validated;
import org.glassfish.jersey.server.ContainerRequest;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;

/**
 * This resource is for resource ticket related API under a tenant.
 */
@Path(TenantResourceRoutes.TENANT_RESOURCE_TICKETS_PATH)
@Api(value = TenantResourceRoutes.API)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TenantResourceTicketsResource {

  private final ResourceTicketFeClient resourceTicketFeClient;

  @Inject
  public TenantResourceTicketsResource(ResourceTicketFeClient resourceTicketFeClient) {
    this.resourceTicketFeClient = resourceTicketFeClient;
  }

  @POST
  @ApiOperation(value = "Create a resource ticket within the tenant", response = Task.class)
  @ApiResponses(value = {
      @ApiResponse(code = 201, message = "Resource ticket is being created, progress communicated via the task")
  })
  public Response create(@Context Request request,
                         @PathParam("id") String tenantId,
                         @Validated ResourceTicketCreateSpec spec) throws ExternalException {
    return generateCustomResponse(
        Response.Status.CREATED,
        resourceTicketFeClient.create(tenantId, spec),
        (ContainerRequest) request,
        TaskResourceRoutes.TASK_PATH);
  }

  @GET
  @ApiOperation(value = "Find resource tickets by name",
      response = ResourceTicket.class, responseContainer = ResourceList.CLASS_NAME)
  @ApiResponses(value = {@ApiResponse(code = 200, message = "List of resource tickets for the tenant")})
  public Response find(@Context Request request,
                       @PathParam("id") String tenantId,
                       @QueryParam("name") Optional<String> name)
      throws ExternalException {
    return generateResourceListResponse(
        Response.Status.OK,
        resourceTicketFeClient.find(tenantId, name),
        (ContainerRequest) request,
        ResourceTicketResourceRoutes.RESOURCE_TICKET_PATH);
  }
}