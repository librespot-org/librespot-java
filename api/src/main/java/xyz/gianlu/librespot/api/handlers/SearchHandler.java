package xyz.gianlu.librespot.api.handlers;

import io.undertow.server.HttpServerExchange;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.SessionWrapper;
import xyz.gianlu.librespot.api.Utils;
import xyz.gianlu.librespot.core.SearchManager;
import xyz.gianlu.librespot.core.Session;

import java.util.Deque;
import java.util.Map;

public final class SearchHandler extends AbsSessionHandler {

    public SearchHandler(@NotNull SessionWrapper wrapper) {
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
        String query = Utils.getFirstString(params, "query");
        if (query == null) {
            Utils.invalidParameter(exchange, "query");
            return;
        }

        exchange.getResponseSender().send(session.search()
                .request(new SearchManager.SearchRequest(query))
                .toString());
    }
}
