package xyz.gianlu.librespot.api.handlers;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
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
        Session s = wrapper.get();
        if (s == null) {
            exchange.setStatusCode(StatusCodes.NO_CONTENT);
            return;
        }

        handleRequest(exchange, s);
    }

    protected abstract void handleRequest(@NotNull HttpServerExchange exchange, @NotNull Session session) throws Exception;
}
