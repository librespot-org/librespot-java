package xyz.gianlu.librespot.api.handlers;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.SessionWrapper;
import xyz.gianlu.librespot.api.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;

import java.io.IOException;
import java.util.Deque;
import java.util.Map;

public final class ProfileHandler extends AbsSessionHandler {
    private static final Logger LOGGER = LogManager.getLogger(ProfileHandler.class);

    public ProfileHandler(@NotNull SessionWrapper wrapper) {
        super(wrapper);
    }

    private static void profileAction(@NotNull HttpServerExchange exchange, @NotNull Session session, @NotNull String userId, @NotNull String action) throws IOException {
        String uri = String.format("hm://user-profile-view/v2/desktop/profile/%s/%s", userId, action);

        try {
            MercuryRequests.GenericJson resp = session.mercury().sendSync(MercuryRequests.getGenericJson(uri));
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send(resp.toString());
        } catch (MercuryClient.MercuryException ex) {
            Utils.internalError(exchange, ex);
            LOGGER.error("Failed handling api request. {uri: {}}", uri, ex);
        }
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

        switch (action) {
            case "followers":
            case "following":
                profileAction(exchange, session, userId, action);
                break;
            default:
                Utils.invalidParameter(exchange, "action");
        }
    }
}
