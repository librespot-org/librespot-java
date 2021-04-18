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

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.SessionWrapper;
import xyz.gianlu.librespot.core.Session;

/**
 * @author Gianlu
 */
public abstract class AbsSessionHandler implements HttpHandler {
    private final SessionWrapper wrapper;

    public AbsSessionHandler(@NotNull SessionWrapper wrapper) {
        this.wrapper = wrapper;
    }

    @Override
    public final void handleRequest(HttpServerExchange exchange) throws Exception {
        Session session = wrapper.getSession();
        if (session == null) {
            exchange.setStatusCode(StatusCodes.NO_CONTENT);
            return;
        }

        if (session.reconnecting()) {
            exchange.setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
            exchange.getResponseHeaders().add(Headers.RETRY_AFTER, 10);
            return;
        }

        if (!session.isValid()) {
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
            return;
        }

        handleRequest(exchange, session);
    }

    protected abstract void handleRequest(@NotNull HttpServerExchange exchange, @NotNull Session session) throws Exception;
}
