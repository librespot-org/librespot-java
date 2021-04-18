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

import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.PlayerWrapper;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.Player;

/**
 * @author devgianlu
 */
public abstract class AbsPlayerHandler extends AbsSessionHandler {
    private final PlayerWrapper wrapper;

    public AbsPlayerHandler(@NotNull PlayerWrapper wrapper) {
        super(wrapper);
        this.wrapper = wrapper;
    }

    @Override
    protected final void handleRequest(@NotNull HttpServerExchange exchange, @NotNull Session session) throws Exception {
        Player player = wrapper.getPlayer();
        if (player == null) {
            exchange.setStatusCode(StatusCodes.NO_CONTENT);
            return;
        }

        handleRequest(exchange, session, player);
    }

    protected abstract void handleRequest(@NotNull HttpServerExchange exchange, @NotNull Session session, @NotNull Player player) throws Exception;
}
