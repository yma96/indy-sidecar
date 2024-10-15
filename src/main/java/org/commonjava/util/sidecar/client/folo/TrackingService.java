/**
 * Copyright (C) 2011-2022 Red Hat, Inc. (https://github.com/Commonjava/indy-sidecar)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.util.sidecar.client.folo;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

@Path( "/api/folo/admin" )
@RegisterRestClient( configKey = "tracking-service-api" )
public interface TrackingService
{
    @GET
    @Path( "/{id}/artifactRecord/{path: (.*)}" )
    Response recordArtifact( final @PathParam( "id" ) String id, final @PathParam( "path" ) String path,
                              final @QueryParam( "packageType" ) String packageType,
                              final @QueryParam( "type" ) String type, final @QueryParam( "name" ) String name,
                              final @QueryParam( "originalUrl" ) String originalUrl,
                              final @QueryParam( "size" ) long size, final @QueryParam( "md5" ) String md5,
                              final @QueryParam( "sha1" ) String sha1, final @QueryParam( "sha256" ) String sha256 );
}