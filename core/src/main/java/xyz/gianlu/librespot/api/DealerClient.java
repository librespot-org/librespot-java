package xyz.gianlu.librespot.api;

import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.ApResolver;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;

/**
 * @author Gianlu
 */
public class DealerClient extends WebSocketListener {
    private final Session session;
    private final WebSocket ws;

    public DealerClient(@NotNull Session session) throws IOException, MercuryClient.MercuryException {
        this.session = session;
        this.ws = new OkHttpClient().newWebSocket(new Request.Builder()
                .url(String.format("wss://%s/?access_token=%s", ApResolver.getRandomDealer(), session.tokens().get("playlist-read", null)))
                .build(), this);
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {

    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {

    }
}
