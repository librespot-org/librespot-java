package xyz.gianlu.librespot.api.handlers;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.PathTemplateMatch;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.SessionWrapper;
import xyz.gianlu.librespot.api.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;

import java.io.IOException;
import java.util.Map;

public class ProfileHandler extends AbsSessionHandler {

    public ProfileHandler(@NotNull SessionWrapper wrapper) {
        super(wrapper);
    }

    private static void profileAction(@NotNull HttpServerExchange exchange, @NotNull Session session, String suffix) throws IOException {
        String uri = "hm://user-profile-view/v2/desktop/profile/" + suffix;
        try {
            MercuryRequests.GeneralJson resp = session.mercury().sendSync(MercuryRequests.generalJsonGet(uri));
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send(resp.toString());
        } catch (MercuryClient.MercuryException e) {
            exchange.setStatusCode(e.statusCode);
            exchange.getResponseSender().send("Upstream Error: " + e.statusCode);
        }
    }

    @Override
    protected void handleRequest(@NotNull HttpServerExchange exchange, @NotNull Session session) throws Exception {
        exchange.startBlocking();
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        Map<String, String> params = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY).getParameters();
        String user_id = params.get("user_id");
        String action = params.get("action");

        switch (action) {
            case "followers":
            case "following":
                profileAction(exchange, session, user_id + "/" + action);
                break;
            default:
                Utils.invalidParameter(exchange, "action");
        }
    }
}
