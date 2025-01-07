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
package org.commonjava.util.sidecar.services;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import kotlin.Pair;
import org.commonjava.util.sidecar.config.ProxyConfiguration;
import org.commonjava.util.sidecar.interceptor.ExceptionHandler;
import org.commonjava.util.sidecar.model.dto.HistoricalEntryDTO;
import org.commonjava.util.sidecar.util.OtelAdapter;
import org.commonjava.util.sidecar.util.ProxyStreamingOutput;
import org.commonjava.util.sidecar.util.UrlUtils;
import org.commonjava.util.sidecar.util.WebClientAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.vertx.core.http.HttpMethod.HEAD;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static org.commonjava.util.sidecar.services.PreSeedConstants.FOLO_TRACK_REST_BASE_PATH;
import static org.commonjava.util.sidecar.services.PreSeedConstants.FORBIDDEN_HEADERS;
import static org.commonjava.util.sidecar.util.SidecarUtils.normalizePathAnd;

@ApplicationScoped
@ExceptionHandler
public class ProxyService
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    ProxyConfiguration proxyConfiguration;

    @Inject
    Classifier classifier;

    @Inject
    OtelAdapter otel;

    @Inject
    ReportService reportService;

    public Uni<Response> doHead( String trackingId, String packageType, String type, String name, String path,
                                 HttpServerRequest request )
            throws Exception
    {
        String contentPath = UrlUtils.buildUrl( FOLO_TRACK_REST_BASE_PATH, trackingId, packageType, type, name, path );
        return doHead( contentPath, request );
    }

    public Uni<Response> doHead( String path, HttpServerRequest request )
            throws Exception
    {
        return normalizePathAnd( path, p -> classifier.classifyAnd( p, request, ( client, service ) -> wrapAsyncCall(
                client.head( p, request ).call(), request.method() ) ) );
    }

    public Uni<Response> doGet( String trackingId, String packageType, String type, String name, String path,
                                HttpServerRequest request )
            throws Exception
    {
        String contentPath = UrlUtils.buildUrl( FOLO_TRACK_REST_BASE_PATH, trackingId, packageType, type, name, path );
        return doGet( contentPath, request );
    }

    public Uni<Response> doGet( String path, HttpServerRequest request )
            throws Exception
    {
        return normalizePathAnd( path, p -> classifier.classifyAnd( p, request, ( client, service ) -> wrapAsyncCall(
                client.get( p, request ).call(), request.method() ) ) );
    }

    public Uni<Response> doPost( String trackingId, String packageType, String type, String name, String path,
                                 InputStream is, HttpServerRequest request )
            throws Exception
    {
        String contentPath = UrlUtils.buildUrl( FOLO_TRACK_REST_BASE_PATH, trackingId, packageType, type, name, path );
        return doPost( contentPath, is, request );
    }

    public Uni<Response> doPost( String path, InputStream is, HttpServerRequest request )
            throws Exception
    {
        return normalizePathAnd( path, p -> classifier.classifyAnd( p, request, ( client, service ) -> wrapAsyncCall(
                client.post( p, is, request ).call(), request.method() ) ) );
    }

    public Uni<Response> doPut( String trackingId, String packageType, String type, String name, String path,
                                InputStream is, HttpServerRequest request )
            throws Exception
    {
        String contentPath = UrlUtils.buildUrl( FOLO_TRACK_REST_BASE_PATH, trackingId, packageType, type, name, path );
        return doPut( contentPath, is, request );
    }

    public Uni<Response> doPut( String path, InputStream is, HttpServerRequest request ) throws Exception
    {
        return normalizePathAnd( path, p -> classifier.classifyAnd( p, request, ( client, service ) -> wrapAsyncCall(
                        client.put( p, is, request ).call(), request.method() ) ) );
    }

    public Uni<Response> doDelete( String path, HttpServerRequest request ) throws Exception
    {
        return normalizePathAnd( path, p -> classifier.classifyAnd( p, request, ( client, service ) -> wrapAsyncCall(
                client.delete( p ).headersFrom( request ).call(), request.method() ) ) );
    }

    public Uni<Response> wrapAsyncCall( WebClientAdapter.CallAdapter asyncCall, HttpMethod method )
    {
        Uni<Response> ret = asyncCall.enqueue().onItem().transform( ( resp ) -> convertProxyResp( resp, method ) );
        return ret.onFailure().recoverWithItem( this::handleProxyException );
    }

    public Uni<Boolean> validateChecksum( String trackingId, String packageType, String type, String name, String path,
                                          HttpServerRequest request )
    {
        Map<String, String> localChecksums = getChecksums( path );
        Uni<Boolean> resultUni = Uni.createFrom().item( false );

        for ( String checksumType : localChecksums.keySet() )
        {
            String localChecksum = localChecksums.get( checksumType );
            if ( localChecksum == null )
            {
                continue;
            }
            String checksumUrl = path + "." + checksumType;
            resultUni = resultUni.onItem().call( () -> {
                try
                {
                    return downloadAndCompareChecksum( trackingId, packageType, type, name, checksumUrl, localChecksum,
                                                       request ).onItem().invoke( result -> {
                        if ( result != null && result )
                        {
                            // This is just used to skip loop to avoid unnecessary checksum download
                            logger.debug(
                                    "Found the valid checksum compare result, stopping further checks, remote path {}",
                                    checksumUrl );
                            throw new FoundValidChecksumException();
                        }
                    } );
                }
                catch ( Exception e )
                {
                    logger.error( "Checksum download compare error for path: {}", checksumUrl, e );
                }
                return null;
            } );
        }
        return resultUni.onFailure().recoverWithItem( false ).onItem().transform( result -> {
            // If catch FoundValidChecksumException，return true
            return true;
        } ); // If no valid checksum compare result found, return false
    }

    private Uni<Boolean> downloadAndCompareChecksum( String trackingId, String packageType, String type, String name,
                                                     String checksumUrl, String localChecksum,
                                                     HttpServerRequest request )
            throws Exception
    {
        return doGet( trackingId, packageType, type, name, checksumUrl, request ).onItem().transform( response -> {
            if ( response.getStatus() == Response.Status.OK.getStatusCode() )
            {
                ProxyStreamingOutput streamingOutput = (ProxyStreamingOutput) response.getEntity();
                try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream())
                {
                    streamingOutput.write( outputStream );
                    String remoteChecksum = outputStream.toString();
                    return localChecksum.equals( remoteChecksum );
                }
                catch ( IOException e )
                {
                    logger.error( "Error to read remote checksum, path:{}.", checksumUrl, e );
                    return null;
                }
            }
            else
            {
                logger.error( "Failed to download remote checksum for {}: HTTP {}.", checksumUrl,
                              response.getStatus() );
                return null;
            }
        } );
    }

    /**
     * Send status 500 with error message body.
     * @param t error
     */
    Response handleProxyException( Throwable t )
    {
        logger.error( "Proxy error", t );
        return Response.status( INTERNAL_SERVER_ERROR ).entity( t + ". Caused by: " + t.getCause() ).build();
    }

    /**
     * Read status and headers from proxy resp and set them to direct response.
     * @param resp proxy resp
     */
    private Response convertProxyResp( okhttp3.Response resp, HttpMethod method )
    {
        logger.debug( "Proxy resp: {} {}", resp.code(), resp.message() );
        logger.trace( "Raw resp headers:\n{}", resp.headers() );
        Response.ResponseBuilder builder = Response.status( resp.code(), resp.message() );
        resp.headers().forEach( header -> {
            if ( isHeaderAllowed( header, method ) )
            {
                logger.debug( "Setting response header: {} = {}", header.getFirst(), header.getSecond() );
                builder.header( header.getFirst(), header.getSecond() );
            }
        } );
        builder.entity( new ProxyStreamingOutput( resp.body(), otel ) );
        return builder.build();
    }

    private boolean isHeaderAllowed( Pair<? extends String, ? extends String> header, HttpMethod method )
    {
        if ( method == HEAD )
        {
            return true;
        }
        String key = header.getFirst();
        return !FORBIDDEN_HEADERS.contains( key.toLowerCase() );
    }

    private Map<String, String> getChecksums( String path )
    {
        Map<String, String> result = new LinkedHashMap<>();
        HistoricalEntryDTO entryDTO = reportService.getHistoricalContentMap().get( path );
        if ( entryDTO != null )
        {
            result.put( ChecksumType.SHA1.getValue(), entryDTO.getSha1() );
            result.put( ChecksumType.SHA256.getValue(), entryDTO.getSha256() );
            result.put( ChecksumType.MD5.getValue(), entryDTO.getMd5() );
        }

        return result;
    }

    enum ChecksumType
    {
        SHA1( "sha1" ),
        SHA256( "sha256" ),
        MD5( "md5" );

        private final String value;

        ChecksumType( String value )
        {
            this.value = value;
        }

        public String getValue()
        {
            return value;
        }
    }

    class FoundValidChecksumException
            extends RuntimeException
    {
        public FoundValidChecksumException()
        {
            super( "Found a valid checksum, stopping further checks." );
        }
    }
}