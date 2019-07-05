package xyz.gianlu.librespot.dealer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.BytesArrayList;
import xyz.gianlu.librespot.core.ApResolver;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author Gianlu
 */
public class DealerClient extends WebSocketListener {
    private static final JsonParser PARSER = new JsonParser();
    private static final Logger LOGGER = Logger.getLogger(DealerClient.class);
    private final WebSocket ws;
    private final Map<MessageListener, List<String>> listeners = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean receivedPong = false;

    public DealerClient(@NotNull Session session) throws IOException, MercuryClient.MercuryException {
        this.ws = new OkHttpClient().newWebSocket(new Request.Builder()
                .url(String.format("wss://%s/?access_token=%s", ApResolver.getRandomDealer(), session.tokens().get("playlist-read", null)))
                .build(), this);
    }

    @Override
    public void onOpen(@NotNull WebSocket webSocket, Response response) {
        LOGGER.debug(String.format("Dealer connected! {host: %s}", response.request().url().host()));

        scheduler.scheduleAtFixedRate(() -> {
            sendPing();
            receivedPong = false;

            try {
                Thread.sleep(5000);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }

            if (!receivedPong) wentAway();
        }, 0, 30, TimeUnit.SECONDS);
    }

    private void wentAway() {
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
    public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
        listeners.clear();

        // TODO: Closed
    }

    @Override
    public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
        JsonObject obj = PARSER.parse(text).getAsJsonObject();

        // FIXME: Sometimes, this fails due to a race condition (network too fast)

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

    private void handleRequest(@NotNull JsonObject obj) {
        String mid = obj.get("message_ident").getAsString();

        JsonObject payload = obj.getAsJsonObject("payload");
        int pid = payload.get("message_id").getAsInt();
        String sender = payload.get("sent_by_device_id").getAsString();

        JsonObject command = payload.getAsJsonObject("command");
        byte[] data = Base64.getDecoder().decode(command.get("data").getAsString());
        String endpoint = command.get("endpoint").getAsString();

        boolean interesting = false;
        synchronized (listeners) {
            for (MessageListener listener : listeners.keySet()) {
                boolean dispatched = false;
                List<String> keys = listeners.get(listener);
                for (String key : keys) {
                    if (mid.startsWith(key) && !dispatched) {
                        interesting = true;
                        listener.onRequest(mid, pid, sender, endpoint, data);
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
        byte[][] decodedPayloads;
        if (payloads != null) {
            decodedPayloads = new byte[payloads.size()][];
            for (int i = 0; i < payloads.size(); i++) {
                decodedPayloads[i] = Base64.getDecoder().decode(payloads.get(i).getAsString());
            }
        } else {
            decodedPayloads = new byte[0][];
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
                            listener.onMessage(uri, parsedHeaders, new BytesArrayList(decodedPayloads));
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
        }
    }

    public void removeListener(@NotNull MessageListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public interface MessageListener {
        void onMessage(@NotNull String uri, @NotNull Map<String, String> headers, @NotNull BytesArrayList payloads) throws IOException;

        void onRequest(@NotNull String mid, int pid, @NotNull String sender, @NotNull String endpoint, @NotNull byte[] data);
    }
}
