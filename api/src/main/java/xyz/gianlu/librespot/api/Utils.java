/*
 * Copyright 2021 devgianlu
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

package xyz.gianlu.librespot.api;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.QueryParameterUtils;
import io.undertow.util.StatusCodes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Deque;
import java.util.Map;

public final class Utils {
    private static final String INVALID_PARAM_BODY = "{\"name\":\"%s\"}";
    private static final String INTERNAL_ERROR_BODY = "{\"msg\":\"%s\"}";
    private static final String INVALID_PARAM_WITH_REASON_BODY = "{\"name\":\"%s\",\"reason\":\"%s\"}";

    private Utils() {
    }

    @NotNull
    public static Map<String, Deque<String>> readParameters(@NotNull HttpServerExchange exchange) throws IOException {
        Map<String, Deque<String>> map = exchange.getQueryParameters();
        if (!exchange.isInIoThread()) {
            String body;
            try (InputStream in = exchange.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
                body = out.toString();
            }

            return QueryParameterUtils.mergeQueryParametersWithNewQueryString(map, body, "UTF-8");
        } else {
            return map;
        }
    }

    @Nullable
    public static String getFirstString(@NotNull Map<String, Deque<String>> params, @NotNull String key) {
        Deque<String> q = params.get(key);
        return q == null ? null : q.getFirst();
    }

    public static boolean getFirstBoolean(@NotNull Map<String, Deque<String>> params, @NotNull String key) {
        return Boolean.parseBoolean(getFirstString(params, key));
    }

    public static void invalidParameter(@NotNull HttpServerExchange exchange, @NotNull String name) {
        exchange.setStatusCode(StatusCodes.BAD_REQUEST);
        exchange.getResponseSender().send(String.format(INVALID_PARAM_BODY, name));
    }

    public static void invalidParameter(@NotNull HttpServerExchange exchange, @NotNull String name, @NotNull String reason) {
        exchange.setStatusCode(StatusCodes.BAD_REQUEST);
        exchange.getResponseSender().send(String.format(INVALID_PARAM_WITH_REASON_BODY, name, reason));
    }

    public static void internalError(@NotNull HttpServerExchange exchange, @NotNull Exception ex) {
        internalError(exchange, ex.getMessage());
    }

    public static void internalError(@NotNull HttpServerExchange exchange, @NotNull String reason) {
        exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
        exchange.getResponseSender().send(String.format(INTERNAL_ERROR_BODY, reason));
    }
}
