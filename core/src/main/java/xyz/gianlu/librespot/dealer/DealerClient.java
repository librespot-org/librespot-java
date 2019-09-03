package xyz.gianlu.librespot.dealer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.core.ApResolver;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @author Gianlu
 */
public class DealerClient extends WebSocketListener implements Closeable {
    private static final JsonParser PARSER = new JsonParser();
    private static final Logger LOGGER = Logger.getLogger(DealerClient.class);
    private final Map<MessageListener, List<String>> listeners = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new NameThreadFactory((r) -> "dealer-scheduler-" + r.hashCode()));
    private final Session session;
    private final Looper looper = new Looper();
    private ScheduledFuture<?> lastScheduledPing;
    private WebSocket ws;
    private volatile boolean receivedPong = false;
    private volatile boolean closed = true;
    private ScheduledFuture<?> scheduledReconnect;

    public DealerClient(@NotNull Session session) throws IOException, MercuryClient.MercuryException {
        this.session = session;
        new Thread(looper, "dealer-looper").start();

        connect();
    }

    private void connect() throws IOException, MercuryClient.MercuryException {
        this.ws = session.client().newWebSocket(new Request.Builder()
                .url(String.format("wss://%s/?access_token=%s", ApResolver.getRandomDealer(), session.tokens().get("playlist-read")))
                .build(), this);
    }

    @Override
    public void onOpen(@NotNull WebSocket webSocket, Response response) {
        LOGGER.debug(String.format("Dealer connected! {host: %s}", response.request().url().host()));
        closed = false;
        if (scheduledReconnect != null) scheduledReconnect.cancel(true);

        lastScheduledPing = scheduler.scheduleAtFixedRate(() -> {
            sendPing();
            receivedPong = false;

            scheduler.schedule(() -> {
                if (lastScheduledPing == null || lastScheduledPing.isCancelled()) return;

                if (!receivedPong) {
                    LOGGER.warn("Did not receive ping in 3 seconds. Reconnecting...");
                    reconnect();
                }
                receivedPong = false;
            }, 3, TimeUnit.SECONDS);
        }, 0, 30, TimeUnit.SECONDS);
    }

    private void scheduleReconnection() {
        if (scheduledReconnect != null) scheduledReconnect.cancel(true);
        scheduledReconnect = scheduler.schedule(this::reconnect, 10, TimeUnit.SECONDS);
    }

    private void reconnect() {
        if (closed) return;

        closed = true;
        if (ws != null) ws.cancel();
        if (lastScheduledPing != null) {
            lastScheduledPing.cancel(true);
            lastScheduledPing = null;
        }

        if (scheduledReconnect != null) {
            scheduledReconnect.cancel(true);
            scheduledReconnect = null;
        }

        try {
            connect();
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.error("Failed reconnecting! Retrying in 10 seconds...", ex);
            scheduleReconnection();
        }
    }

    private void sendPing() {
        ws.send("{\"type\":\"ping\"}");
    }

    @Override
    public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response response) {
        if (t instanceof SocketException) {
            if (closed) {
                LOGGER.error("Failed reconnecting! Retrying in 10 seconds...", t);
                scheduleReconnection();
            } else {
                LOGGER.warn("An exception occurred. Reconnecting...", t);
                reconnect();
            }
            return;
        }

        LOGGER.error("Unexpected failure when handling message!", t);
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
            default:
                throw new IllegalArgumentException("Unknown message type for " + type);
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
                        looper.submit(() -> listener.onRequest(mid, pid, sender, command));
                        dispatched = true;
                    }
                }
            }
        }

        if (!interesting) LOGGER.debug("Couldn't dispatch command: " + mid);
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
                        interesting = true;
                        looper.submit(() -> {
                            try {
                                listener.onMessage(uri, parsedHeaders, decodedPayloads);
                            } catch (IOException ex) {
                                LOGGER.error("Failed dispatching message!", ex);
                            }
                        });
                        dispatched = true;
                    }
                }
            }
        }

        if (!interesting) LOGGER.debug("Couldn't dispatch message: " + uri);
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
        closed = true;
        ws.close(1000, null);

        listeners.clear();
    }

    public interface MessageListener {
        void onMessage(@NotNull String uri, @NotNull Map<String, String> headers, @NotNull String[] payloads) throws IOException;

        void onRequest(@NotNull String mid, int pid, @NotNull String sender, @NotNull JsonObject command);
    }

    private static class Looper implements Runnable, Closeable {
        private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        private boolean shouldStop = false;

        void submit(@NotNull Runnable task) {
            tasks.add(task);
        }

        @Override
        public void run() {
            while (!shouldStop) {
                try {
                    tasks.take().run();
                } catch (InterruptedException ex) {
                    break;
                }
            }
        }

        @Override
        public void close() {
            shouldStop = true;
        }
    }
}
