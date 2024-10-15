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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.Startup;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.json.JsonObject;
import org.commonjava.util.sidecar.client.folo.TrackingService;
import org.commonjava.util.sidecar.config.SidecarConfig;
import org.commonjava.util.sidecar.model.StoreKey;
import org.commonjava.util.sidecar.model.dto.HistoricalContentDTO;
import org.commonjava.util.sidecar.model.dto.HistoricalEntryDTO;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.commonjava.util.sidecar.services.PreSeedConstants.DEFAULT_REPO_PATH;
import static org.commonjava.util.sidecar.services.PreSeedConstants.FOLO_BUILD;
import static org.commonjava.util.sidecar.services.PreSeedConstants.TRACKING_ID;
import static org.commonjava.util.sidecar.services.PreSeedConstants.TRACKING_PATH;
import static org.commonjava.util.sidecar.util.SidecarUtils.getBuildConfigId;

@Startup
@ApplicationScoped
public class ReportService
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final HashMap<String, HistoricalEntryDTO> historicalContentMap = new HashMap<>();

    @Inject
    @RestClient
    TrackingService trackingService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SidecarConfig sidecarConfig;

    @PostConstruct
    void init()
    {
        loadReport( sidecarConfig.localRepository.orElse( DEFAULT_REPO_PATH ) );
    }

    private void loadReport( String path )
    {
        if ( getBuildConfigId() != null )
        {
            HistoricalContentDTO content;
            Path filePath = Path.of( path, File.separator, getBuildConfigId() );
            logger.info( "Loading build content history:" + filePath );
            try
            {
                String json = Files.readString( filePath );
                content = objectMapper.readValue( json, HistoricalContentDTO.class );
                if ( content == null )
                {
                    logger.warn( "Failed to read historical content which is empty." );
                    return;
                }
                for ( HistoricalEntryDTO download : content.getDownloads() )
                {
                    String trackingPath =
                            download.getPath().startsWith( "/" ) ? download.getPath().substring( 1 ) : path;
                    this.historicalContentMap.put( trackingPath, download );
                }
            }
            catch ( IOException e )
            {
                logger.error( "convert file " + filePath + " to object failed" );
            }
        }
    }

    @ConsumeEvent( value = FOLO_BUILD )
    public void storeTrackedDownload( JsonObject message )
            throws Exception
    {
        String trackingPath = message.getString( TRACKING_PATH );
        String trackingId = message.getString( TRACKING_ID );
        logger.debug( "Consuming folo record seal event for path:{}, trackingId:{}", trackingPath, trackingId );

        HistoricalEntryDTO entryDTO = historicalContentMap.get( trackingPath );
        if ( null == entryDTO )
        {
            logger.warn( "No historical entry meta is found for tracking {}.", trackingPath );
            return;
        }
        StoreKey storeKey = entryDTO.getStoreKey();
        if ( null == storeKey )
        {
            logger.warn( "No StoreKey is found for tracking {} historical entry.", trackingPath );
            return;
        }
        String originalUrl = entryDTO.getOriginUrl() == null ? "" : entryDTO.getOriginUrl();
        boolean exception = false;
        try
        {
            Response response = trackingService.recordArtifact( trackingId, trackingPath, storeKey.getPackageType(),
                                                                 storeKey.getType().name(), storeKey.getName(),
                                                                 originalUrl, entryDTO.getSize(), entryDTO.getMd5(),
                                                                 entryDTO.getSha1(), entryDTO.getSha256() );
            logger.debug(
                    "Finished consuming folo record seal event for path:{}, trackingId:{}, rep code: {}, body: {}",
                    trackingPath, trackingId, response.getStatus(), response.getStatusInfo().getReasonPhrase() );
        }
        catch ( Exception e )
        {
            exception = true;
            throw new Exception( "Sidecar failed to record folo for historical entry, will stop.", e );
        }
        finally
        {
            if ( exception )
            {
                // exit with default code -1
                Quarkus.asyncExit();
            }
        }

    }

}
