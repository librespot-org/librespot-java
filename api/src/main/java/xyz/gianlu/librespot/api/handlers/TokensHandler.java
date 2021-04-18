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
import io.undertow.server.HttpServerExchange;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.SessionWrapper;
import xyz.gianlu.librespot.api.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TokenProvider;

import java.util.Deque;
import java.util.Map;

public final class TokensHandler extends AbsSessionHandler {

    public TokensHandler(@NotNull SessionWrapper wrapper) {
        super(wrapper);
    }

    @Override
    protected void handleRequest(@NotNull HttpServerExchange exchange, @NotNull Session session) throws Exception {
        exchange.startBlocking();
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        Map<String, Deque<String>> params = Utils.readParameters(exchange);
        String scope = Utils.getFirstString(params, "scope");
        if (scope == null) {
            Utils.invalidParameter(exchange, "scope");
            return;
        }

        TokenProvider.StoredToken token = session.tokens().getToken(scope);
        JsonObject obj = new JsonObject();
        obj.addProperty("token", token.accessToken);
        obj.addProperty("timestamp", token.timestamp);
        obj.addProperty("expiresIn", token.expiresIn);
        exchange.getResponseSender().send(obj.toString());
    }
}
