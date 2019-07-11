package xyz.gianlu.librespot.dealer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.core.ApResolver;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author Gianlu
 */
public class DealerClient extends WebSocketListener implements Closeable {
    private static final JsonParser PARSER = new JsonParser();
    private static final Logger LOGGER = Logger.getLogger(DealerClient.class);
    private final WebSocket ws;
    private final Map<MessageListener, List<String>> listeners = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new NameThreadFactory((r) -> "dealer-ping-" + r.hashCode()));
    private volatile boolean receivedPong = false;

    public DealerClient(@NotNull Session session) throws IOException, MercuryClient.MercuryException {
        this.ws = new OkHttpClient().newWebSocket(new Request.Builder()
                .url(String.format("wss://%s/?access_token=%s", ApResolver.getRandomDealer(), session.tokens().get("playlist-read")))
                .build(), this);
    }

    @Override
    public void onOpen(@NotNull WebSocket webSocket, Response response) {
        LOGGER.debug(String.format("Dealer connected! {host: %s}", response.request().url().host()));

        scheduler.scheduleAtFixedRate(() -> {
            sendPing();
            receivedPong = false;

            scheduler.schedule(() -> {
                if (!receivedPong) wentAway();
                receivedPong = false;
            }, 3, TimeUnit.SECONDS);
        }, 0, 30, TimeUnit.SECONDS);
    }

    private void wentAway() {
        listeners.clear();
        scheduler.shutdown();
        ws.cancel();

        LOGGER.warn("Dealer went away!"); // TODO: Handling unexpected disconnection
    }

    private void sendPing() {
        ws.send("{\"type\":\"ping\"}");
    }

    @Override
    public void onFailure(@NotNull WebSocket webSocket, Throwable t, Response response) {
        t.printStackTrace(); // TODO: Handle failure
    }

    @Override
    public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
        System.out.println("CLOSING"); // TODO

        wentAway();
    }

    @Override
    public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
        System.out.println("CLOSED"); // TODO

        wentAway();
    }

    @Override
    public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
        JsonObject obj = PARSER.parse(text).getAsJsonObject();

        waitForListeners();

        MessageType type = MessageType.parse(obj.get("type").getAsString());
        switch (type) {
            case MESSAGE:
                handleMessage(obj);
                break;
            case REQUEST:
                handleRequest(obj);
                break;
            case PONG:
                receivedPong = true;
                break;
            case PING:
                break;
        }
    }

    private void waitForListeners() {
        synchronized (listeners) {
            if (!listeners.isEmpty()) return;

            try {
                listeners.wait();
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void handleRequest(@NotNull JsonObject obj) {
        String mid = obj.get("message_ident").getAsString();

        JsonObject payload = obj.getAsJsonObject("payload");
        int pid = payload.get("message_id").getAsInt();
        String sender = payload.get("sent_by_device_id").getAsString();

        JsonObject command = payload.getAsJsonObject("command");

        boolean interesting = false;
        synchronized (listeners) {
            for (MessageListener listener : listeners.keySet()) {
                boolean dispatched = false;
                List<String> keys = listeners.get(listener);
                for (String key : keys) {
                    if (mid.startsWith(key) && !dispatched) {
                        interesting = true;
                        listener.onRequest(mid, pid, sender, command);
                        dispatched = true;
                    }
                }
            }
        }

        if (!interesting) LOGGER.warn("Couldn't dispatch command: " + mid);
    }

    private void handleMessage(@NotNull JsonObject obj) {
        String uri = obj.get("uri").getAsString();

        JsonArray payloads = obj.getAsJsonArray("payloads");
        String[] decodedPayloads;
        if (payloads != null) {
            decodedPayloads = new String[payloads.size()];
            for (int i = 0; i < payloads.size(); i++) decodedPayloads[i] = payloads.get(i).getAsString();
        } else {
            decodedPayloads = new String[0];
        }

        JsonObject headers = obj.getAsJsonObject("headers");
        Map<String, String> parsedHeaders = new HashMap<>();
        if (headers != null) {
            for (String key : headers.keySet())
                parsedHeaders.put(key, headers.get(key).getAsString());
        }

        boolean interesting = false;
        synchronized (listeners) {
            for (MessageListener listener : listeners.keySet()) {
                boolean dispatched = false;
                List<String> keys = listeners.get(listener);
                for (String key : keys) {
                    if (uri.startsWith(key) && !dispatched) {
                        try {
                            interesting = true;
                            listener.onMessage(uri, parsedHeaders, decodedPayloads);
                            dispatched = true;
                        } catch (IOException ex) {
                            LOGGER.error("Failed dispatching message!", ex);
                        }
                    }
                }
            }
        }

        if (!interesting) LOGGER.warn("Couldn't dispatch message: " + uri);
    }

    public void addListener(@NotNull MessageListener listener, @NotNull String... uris) {
        synchronized (listeners) {
            listeners.put(listener, Arrays.asList(uris));
            listeners.notifyAll();
        }
    }

    public void removeListener(@NotNull MessageListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    @Override
    public void close() {
        ws.close(100, null);
    }

    public interface MessageListener {
        void onMessage(@NotNull String uri, @NotNull Map<String, String> headers, @NotNull String[] payloads) throws IOException;

        void onRequest(@NotNull String mid, int pid, @NotNull String sender, @NotNull JsonObject command);
    }
}
