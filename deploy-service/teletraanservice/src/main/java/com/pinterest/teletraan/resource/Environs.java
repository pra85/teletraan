/**
 * Copyright 2016 Pinterest, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *     http://www.apache.org/licenses/LICENSE-2.0
 *    
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pinterest.teletraan.resource;

import com.google.common.base.Optional;
import com.pinterest.deployservice.bean.EnvironBean;
import com.pinterest.deployservice.bean.Resource;
import com.pinterest.deployservice.bean.Role;
import com.pinterest.deployservice.bean.UserRolesBean;
import com.pinterest.deployservice.dao.EnvironDAO;
import com.pinterest.deployservice.dao.UserRolesDAO;
import com.pinterest.deployservice.handler.EnvironHandler;
import com.pinterest.teletraan.TeletraanServiceContext;
import com.pinterest.teletraan.exception.TeletaanInternalException;
import com.pinterest.teletraan.security.Authorizer;
import com.pinterest.teletraan.security.OpenAuthorizer;
import io.swagger.annotations.*;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.Valid;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

@Path("/v1/envs")
@Api(tags = "Environments")
@SwaggerDefinition(
        tags = {
                @Tag(name = "Environments", description = "Environment info APIs"),
        }
)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class Environs {
    private static final Logger LOG = LoggerFactory.getLogger(Environs.class);
    private final static int DEFAULT_INDEX = 1;
    private final static int DEFAULT_SIZE = 30;
    private EnvironDAO environDAO;
    private EnvironHandler environHandler;
    private UserRolesDAO userRolesDAO;
    private final Authorizer authorizer;

    @Context
    UriInfo uriInfo;

    public Environs(TeletraanServiceContext context) throws Exception {
        environDAO = context.getEnvironDAO();
        environHandler = new EnvironHandler(context);
        userRolesDAO = context.getUserRolesDAO();
        authorizer = context.getAuthorizer();
    }

    @GET
    @Path("/{id : [a-zA-Z0-9\\-_]+}")
    @ApiOperation(
            value = "Get environment object",
            notes = "Returns an environment object given an environment id",
            response = EnvironBean.class)
    public EnvironBean get(
            @ApiParam(value = "Environment id", required = true)@PathParam("id") String id) throws Exception {
        EnvironBean environBean = environDAO.getById(id);
        if (environBean == null) {
            throw new TeletaanInternalException(Response.Status.NOT_FOUND,
                String.format("Environment %s does not exist.", id));
        }
        return environBean;
    }

    @GET
    @Path("/names")
    public List<String> get(@QueryParam("nameFilter") Optional<String> nameFilter,
        @QueryParam("pageIndex") Optional<Integer> pageIndex,
        @QueryParam("pageSize") Optional<Integer> pageSize) throws Exception {
        return environDAO.getAllEnvNames(nameFilter.or(""), pageIndex.or(DEFAULT_INDEX),
            pageSize.or(DEFAULT_SIZE));
    }

    @GET
    @ApiOperation(
            value = "Get all environment objects",
            notes = "Returns a list of environment objects related to the given environment name",
            response = EnvironBean.class, responseContainer = "List")
    public List<EnvironBean> getAll(
            @ApiParam(value = "Environment name", required = true)@QueryParam("envName") String envName,
        @QueryParam("groupName") String groupName) throws Exception {
        if (!StringUtils.isEmpty(envName)) {
            return environDAO.getByName(envName);
        }

        if (!StringUtils.isEmpty(groupName)) {
            return environDAO.getEnvsByGroups(Arrays.asList(groupName));
        }

        throw new TeletaanInternalException(Response.Status.BAD_REQUEST, "Require either environment name or group name in the request.");
    }

    @POST
    @ApiOperation(
            value = "Create environment",
            notes = "Creates a new environment given an environment object",
            response = Response.class)
    public Response create(
            @Context SecurityContext sc,
            @ApiParam(value = "Environemnt object to create in database", required = true)@Valid EnvironBean environBean) throws Exception {
        String operator = sc.getUserPrincipal().getName();
        String envName = environBean.getEnv_name();
        List<EnvironBean> environBeans = environDAO.getByName(envName);
        if (!CollectionUtils.isEmpty(environBeans)) {
            authorizer.authorize(sc, new Resource(envName, Resource.Type.ENV), Role.OPERATOR);
        }

        String id = environHandler.createEnvStage(environBean, operator);
        if (!(authorizer instanceof OpenAuthorizer) && CollectionUtils.isEmpty(environBeans)) {
            // This is the first stage for this env, let's make operator ADMIN of this env
            UserRolesBean rolesBean = new UserRolesBean();
            rolesBean.setResource_id(environBean.getEnv_name());
            rolesBean.setResource_type(Resource.Type.ENV);
            rolesBean.setRole(Role.ADMIN);
            rolesBean.setUser_name(operator);
            userRolesDAO.insert(rolesBean);
            LOG.info("Make {} admin for the new env {}", operator, envName);
        }
        LOG.info("Successfully created env stage {} by {}.", environBean, operator);
        UriBuilder ub = uriInfo.getAbsolutePathBuilder();
        URI buildUri = ub.path(environBean.getEnv_name()).path(environBean.getStage_name()).build();
        environBean = environDAO.getById(id);
        return Response.created(buildUri).entity(environBean).build();
    }
}
