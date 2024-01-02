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

import java.util.Arrays;
import java.util.List;

public class PreSeedConstants
{
    // Vert.x event types
    public static final String EVENT_PROXY_CONFIG_CHANGE = "proxy-config-change";

    // Auto generated, ignore such upstream headers
    public static final List<String> FORBIDDEN_HEADERS =
                    Arrays.asList( "content-length", "connection", "transfer-encoding" );

    public static final String FOLO_TRACK_REST_BASE_PATH = "api/folo/track";

    public static final String FOLO_ADMIN_REST_BASE_PATH = "/api/folo/admin";

    public static final String DEFAULT_REPO_PATH = System.getProperty( "user.home" ) + "/preSeedRepo";

    public static final String FOLO_BUILD = "folo-build";

    public static final String TRACKING_PATH = "Tracking-Path";

    public static final String TRACKING_ID = "Tracking-Id";
}