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

package xyz.gianlu.librespot.api.handlers;

import com.google.gson.JsonObject;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.SessionWrapper;
import xyz.gianlu.librespot.api.Utils;
import xyz.gianlu.librespot.core.Session;

import java.util.Deque;
import java.util.Map;

public final class ProfileHandler extends AbsSessionHandler {
    private static final Logger LOGGER = LogManager.getLogger(ProfileHandler.class);

    public ProfileHandler(@NotNull SessionWrapper wrapper) {
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
        String userId = Utils.getFirstString(params, "user_id");
        if (userId == null) {
            Utils.invalidParameter(exchange, "user_id");
            return;
        }

        String action = Utils.getFirstString(params, "action");
        if (action == null) {
            Utils.invalidParameter(exchange, "action");
            return;
        }

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        switch (action) {
            case "followers":
                JsonObject followers = session.api().getUserFollowers(userId);
                exchange.getResponseSender().send(followers.toString());
                break;
            case "following":
                JsonObject following = session.api().getUserFollowing(userId);
                exchange.getResponseSender().send(following.toString());
                break;
            case "profile":
                JsonObject profile = session.api().getUserProfile(userId, null, null);
                exchange.getResponseSender().send(profile.toString());
                break;
            default:
                Utils.invalidParameter(exchange, "action");
        }
    }
}
