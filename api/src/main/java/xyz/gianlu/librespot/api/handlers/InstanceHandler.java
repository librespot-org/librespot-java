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

package xyz.gianlu.librespot.api.handlers;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.api.ApiServer;
import xyz.gianlu.librespot.api.PlayerWrapper;
import xyz.gianlu.librespot.api.SessionWrapper;
import xyz.gianlu.librespot.api.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.Player;

import java.io.IOException;
import java.util.Deque;
import java.util.Map;

/**
 * @author devgianlu
 */
public final class InstanceHandler {

    public static AbsSessionHandler forSession(@NotNull ApiServer server, @NotNull SessionWrapper wrapper) {
        return new SessionHandler(server, wrapper);
    }

    public static AbsPlayerHandler forPlayer(@NotNull ApiServer server, @NotNull PlayerWrapper wrapper) {
        return new PlayerHandler(server, wrapper);
    }

    @Nullable
    private static String getAction(@NotNull HttpServerExchange exchange) throws IOException {
        Map<String, Deque<String>> params = Utils.readParameters(exchange);
        String action = Utils.getFirstString(params, "action");
        if (action == null) {
            Utils.invalidParameter(exchange, "action");
            return null;
        }

        return action;
    }

    private static String getInstanceInfo(@NotNull Session session) throws JsonSyntaxException {
        JsonObject infoObj = new JsonObject();
        infoObj.addProperty("device_id", session.deviceId());
        infoObj.addProperty("device_name", session.deviceName());
        infoObj.addProperty("device_type", session.deviceType().toString());
        infoObj.addProperty("country_code", session.countryCode());
        infoObj.addProperty("preferred_locale", session.preferredLocale());
        return infoObj.toString();
    }

    private static class SessionHandler extends AbsSessionHandler {
        private final ApiServer server;

        SessionHandler(ApiServer server, @NotNull SessionWrapper wrapper) {
            super(wrapper);
            this.server = server;
        }

        @Override
        protected void handleRequest(@NotNull HttpServerExchange exchange, @NotNull Session session) throws Exception {
            exchange.startBlocking();
            if (exchange.isInIoThread()) {
                exchange.dispatch(this);
                return;
            }

            String requestMethod = exchange.getRequestMethod().toString();
            switch(requestMethod) {
                case "GET":
                    String info = getInstanceInfo(session);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.getResponseSender().send(info);
                    return;
                case "POST":
                    String action = getAction(exchange);
                    if (action == null) return;

                    switch (action) {
                        case "terminate":
                            exchange.endExchange();
                            new Thread(server::stop).start();
                            break;
                        case "close":
                            session.close();
                            break;
                        default:
                            Utils.methodNotAllowed(exchange);
                            break;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static class PlayerHandler extends AbsPlayerHandler {
        private final ApiServer server;

        PlayerHandler(@NotNull ApiServer server, @NotNull PlayerWrapper wrapper) {
            super(wrapper);
            this.server = server;
        }

        @Override
        protected void handleRequest(@NotNull HttpServerExchange exchange, @NotNull Session session, @NotNull Player player) throws Exception {
            exchange.startBlocking();
            if (exchange.isInIoThread()) {
                exchange.dispatch(this);
                return;
            }

            String requestMethod = exchange.getRequestMethod().toString();
            switch (requestMethod) {
                case "GET":
                    String info = getInstanceInfo(session);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.getResponseSender().send(info);
                    break;
                case "POST":
                    handlePostRequest(exchange, session, player);
                    break;
                default:
                    break;
            }
        }

        private void handlePostRequest(@NotNull HttpServerExchange exchange, @NotNull Session session, @NotNull Player player) throws Exception {
            String action = getAction(exchange);
            if (action == null) return;

            switch (action) {
                case "terminate":
                    exchange.endExchange();
                    new Thread(server::stop).start();
                    break;
                case "close":
                    session.close();
                    break;
                default:
                    Utils.methodNotAllowed(exchange);
                    break;
            }
        }
    }
    }
