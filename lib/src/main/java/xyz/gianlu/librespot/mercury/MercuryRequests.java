/*
 * Copyright 2022 devgianlu
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

package xyz.gianlu.librespot.mercury;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.json.GenericJson;
import xyz.gianlu.librespot.json.ResolvedContextWrapper;
import xyz.gianlu.librespot.json.StationsWrapper;

/**
 * @author Gianlu
 */
public final class MercuryRequests {
    public static final String KEYMASTER_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd";

    private MercuryRequests() {
    }

    @NotNull
    public static JsonMercuryRequest<StationsWrapper> getStationFor(@NotNull String context) {
        return new JsonMercuryRequest<>(RawMercuryRequest.get("hm://radio-apollo/v3/stations/" + context), StationsWrapper.class);
    }

    @NotNull
    public static RawMercuryRequest autoplayQuery(@NotNull String context) {
        return RawMercuryRequest.get("hm://autoplay-enabled/query?uri=" + context);
    }

    @NotNull
    public static JsonMercuryRequest<ResolvedContextWrapper> resolveContext(@NotNull String uri) {
        return new JsonMercuryRequest<>(RawMercuryRequest.get(String.format("hm://context-resolve/v1/%s", uri)), ResolvedContextWrapper.class);
    }

    @NotNull
    public static JsonMercuryRequest<GenericJson> requestToken(@NotNull String deviceId, @NotNull String scope) {
        return new JsonMercuryRequest<>(RawMercuryRequest.get(String.format("hm://keymaster/token/authenticated?scope=%s&client_id=%s&device_id=%s", scope, KEYMASTER_CLIENT_ID, deviceId)), GenericJson.class);
    }
}
