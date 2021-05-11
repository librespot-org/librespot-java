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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.ZeroconfServer;
import xyz.gianlu.librespot.api.Utils;
import xyz.gianlu.zeroconf.DiscoveredService;
import xyz.gianlu.zeroconf.Zeroconf;

import java.io.IOException;
import java.util.Deque;
import java.util.Map;

/**
 * @author devgianlu
 */
public final class DiscoveryHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryHandler.class);
    private final Zeroconf zeroconf;

    public DiscoveryHandler() {
        zeroconf = new Zeroconf()
                .setUseIpv4(true)
                .setUseIpv6(false);

        try {
            zeroconf.addAllNetworkInterfaces();
        } catch (IOException ex) {
            LOGGER.error("Failed adding network interfaces for Zeroconf.", ex);
        }
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        exchange.startBlocking();
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        Map<String, Deque<String>> params = Utils.readParameters(exchange);
        String action = Utils.getFirstString(params, "action");
        if (action == null) {
            Utils.invalidParameter(exchange, "action");
            return;
        }

        switch (action) {
            case "list":
                JsonArray array = new JsonArray();
                for (DiscoveredService service : zeroconf.discover(ZeroconfServer.SERVICE, "tcp", ".local")) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("name", service.name);
                    obj.addProperty("target", service.target);
                    obj.addProperty("port", service.port);
                    array.add(obj);
                }

                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.getResponseSender().send(array.toString());
                break;
            default:
                Utils.invalidParameter(exchange, "action");
                break;
        }
    }
}
