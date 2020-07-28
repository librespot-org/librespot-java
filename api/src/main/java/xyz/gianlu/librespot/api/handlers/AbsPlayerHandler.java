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
