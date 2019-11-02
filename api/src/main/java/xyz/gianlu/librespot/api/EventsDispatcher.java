package xyz.gianlu.librespot.api;

import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.Session;

public class EventsDispatcher implements WebSocketConnectionCallback {
    private final Session session;

    public EventsDispatcher(@NotNull Session session) {
        this.session = session;
    }

    @Override
    public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        // TODO
    }
}
