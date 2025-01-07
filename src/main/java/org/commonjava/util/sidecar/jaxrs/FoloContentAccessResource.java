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
package org.commonjava.util.sidecar.jaxrs;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import org.apache.commons.io.FileUtils;
import org.commonjava.util.sidecar.services.ArchiveRetrieveService;
import org.commonjava.util.sidecar.services.ProxyService;
import org.commonjava.util.sidecar.util.TransferStreamingOutput;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.resteasy.annotations.jaxrs.PathParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import static org.commonjava.util.sidecar.services.PreSeedConstants.FOLO_BUILD;
import static org.commonjava.util.sidecar.services.PreSeedConstants.TRACKING_ID;
import static org.commonjava.util.sidecar.services.PreSeedConstants.TRACKING_PATH;
import static org.eclipse.microprofile.openapi.annotations.enums.ParameterIn.PATH;

@Path( "/api/folo/track/{id}/{packageType: (maven|npm)}/{type: (hosted|group|remote)}/{name}" )
public class FoloContentAccessResource
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    EventBus bus;

    @Inject
    ProxyService proxyService;

    @Inject
    ArchiveRetrieveService archiveService;

    @Operation( description = "Retrieve Maven/NPM artifact content from historical archive or proxy" )
    @APIResponse( responseCode = "200", description = "Content stream" )
    @APIResponse( responseCode = "404", description = "Content is not available" )
    @Path( "/{path: (.*)}" )
    @GET
    public Uni<Response> get( @Parameter( in = PATH, required = true ) @PathParam( "id" ) final String id,
                              @Parameter( in = PATH, schema = @Schema( enumeration = { "maven",
                                              "npm" } ), required = true ) @PathParam( "packageType" ) final String packageType,
                              @Parameter( in = PATH, schema = @Schema( enumeration = { "hosted", "group",
                                              "remote" } ), required = true ) @PathParam( "type" ) final String type,
                              @Parameter( in = PATH, required = true ) @PathParam( "name" ) final String name,
                              @PathParam( "path" ) String path, final @Context HttpServerRequest request )
                    throws Exception
    {
        if ( archiveService.shouldProxy( path ) )
        {
            logger.debug( "Get proxy resource for folo request: {}", path );
            return proxyService.doGet( id, packageType, type, name, path, request );
        }

        Optional<File> download = archiveService.getLocally( path );
        if ( download.isPresent() && download.get().isFile() )
        {
            Uni<Boolean> checksumValidation =
                    proxyService.validateChecksum( id, packageType, type, name, path, request );
            return checksumValidation.onItem().transform( result -> {
                if ( result != null && result )
                {
                    try
                    {
                        InputStream inputStream = FileUtils.openInputStream( download.get() );
                        final Response.ResponseBuilder builder =
                                Response.ok( new TransferStreamingOutput( inputStream ) );
                        logger.debug( "Download path: {} from historical archive.", path );
                        publishTrackingEvent( path, id );
                        return Uni.createFrom().item( builder.build() );
                    }
                    catch ( IOException e )
                    {
                        logger.error( "IO error for local file, path {}.", path, e );
                    }
                }
                else
                {
                    try
                    {
                        logger.debug( "Checksum validation failed, download from proxy: {}.", path );
                        return proxyService.doGet( id, packageType, type, name, path, request );
                    }
                    catch ( Exception e )
                    {
                        logger.error( "Error for proxy download, path {}.", path, e );
                    }
                }
                return null;
            } ).flatMap( response -> response );
        }
        else
        {
            logger.debug( "Download path: {} from proxy.", path );
            return proxyService.doGet( id, packageType, type, name, path, request );
        }
    }

    @Operation( description = "Store artifact content under the given artifact store (type/name) and path." )
    @APIResponse( responseCode = "404", description = "Content is not available" )
    @APIResponse( responseCode = "200", description = "Header metadata for content (or rendered listing when path ends with '/index.html' or '/'" )
    @HEAD
    @Path( "/{path: (.*)}" )
    public Uni<Response> head( @Parameter( in = PATH, required = true ) @PathParam( "id" ) final String id,
                               @Parameter( in = PATH, schema = @Schema( enumeration = { "maven",
                                               "npm" } ), required = true ) @PathParam( "packageType" ) final String packageType,
                               @Parameter( in = PATH, schema = @Schema( enumeration = { "hosted", "group",
                                               "remote" } ), required = true ) @PathParam( "type" ) final String type,
                               @Parameter( in = PATH, required = true ) @PathParam( "name" ) final String name,
                               @PathParam( "path" ) String path, @QueryParam( "cache-only" ) final Boolean cacheOnly,
                               final @Context HttpServerRequest request ) throws Exception
    {
        logger.debug( "Head proxy resource for folo request: {}", path );
        return proxyService.doHead( id, packageType, type, name, path, request );
    }

    @Operation( description = "Store artifact content under the given artifact store (type/name) and path." )
    @APIResponse( responseCode = "201", description = "Content was stored successfully" )
    @APIResponse( responseCode = "400", description = "No appropriate storage location was found in the specified store" )
    @PUT
    @Path( "/{path: (.*)}" )
    public Uni<Response> put( @Parameter( in = PATH, required = true ) @PathParam( "id" ) final String id,
                              @Parameter( in = PATH, schema = @Schema( enumeration = { "maven",
                                              "npm" } ), required = true ) @PathParam( "packageType" ) final String packageType,
                              @Parameter( in = PATH, schema = @Schema( enumeration = { "hosted", "group",
                                              "remote" } ), required = true ) @PathParam( "type" ) final String type,
                              @Parameter( in = PATH, required = true ) @PathParam( "name" ) final String name,
                              @PathParam( "path" ) String path, InputStream is,
                              final @Context HttpServerRequest request ) throws Exception
    {
        logger.debug( "Put proxy resource for folo request: {}", path );
        return proxyService.doPut( id, packageType, type, name, path, is, request );
    }

    @POST
    @Path( "/{path: (.*)}" )
    public Uni<Response> post( @Parameter( in = PATH, required = true ) @PathParam( "id" ) final String id,
                              @Parameter( in = PATH, schema = @Schema( enumeration = { "maven",
                                      "npm" } ), required = true ) @PathParam( "packageType" ) final String packageType,
                              @Parameter( in = PATH, schema = @Schema( enumeration = { "hosted", "group",
                                      "remote" } ), required = true ) @PathParam( "type" ) final String type,
                              @Parameter( in = PATH, required = true ) @PathParam( "name" ) final String name,
                              @PathParam( "path" ) String path, InputStream is,
                              final @Context HttpServerRequest request ) throws Exception
    {
        logger.debug( "POST proxy resource for folo request: {}", path );
        return proxyService.doPost( id, packageType, type, name, path, is, request );
    }

    private void publishTrackingEvent( String trackingPath, String trackingId )
    {
        JsonObject message = new JsonObject();
        message.put( TRACKING_PATH, trackingPath );
        message.put( TRACKING_ID, trackingId );
        bus.publish( FOLO_BUILD, message );
    }
}
