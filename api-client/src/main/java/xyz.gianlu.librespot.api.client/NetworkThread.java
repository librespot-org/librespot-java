package xyz.gianlu.librespot.api.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author Gianlu
 */
public class NetworkThread extends Thread {
    private static final JsonParser PARSER = new JsonParser();
    private final Client client;
    private final Listener listener;
    private final Map<String, Callback> requests = new HashMap<>();

    NetworkThread(@NotNull URI uri, @NotNull Listener listener) {
        client = new Client(uri);
        this.listener = listener;
        start();
    }

    private static void addParams(@NotNull JsonObject obj, @Nullable String params) {
        if (params == null) return;

        if (params.isEmpty()) {
            obj.addProperty("params", "");
        } else if ((params.startsWith("{") && params.endsWith("}")) || (params.startsWith("[") && params.endsWith("]"))) {
            obj.add("params", PARSER.parse(params));
        } else {
            obj.addProperty("params", params);
        }
    }

    void close() {
        client.close();
    }

    @Override
    public void run() {
        try {
            client.connectBlocking();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    void sendGeneral(@NotNull String id, @NotNull String method, @Nullable String params, @NotNull Callback listener) {
        JsonObject obj = new JsonObject();
        obj.addProperty("jsonrpc", "2.0");
        obj.addProperty("id", id);
        obj.addProperty("method", method);
        addParams(obj, params);

        client.send(obj.toString());
        requests.put(id, listener);
    }

    void sendPlayer(@NotNull String suffix, @NotNull Callback listener) {
        sendGeneral(String.valueOf(ThreadLocalRandom.current().nextInt(1000)), "player." + suffix, null, listener);
    }

    void sendMercury(@NotNull String method, @NotNull String uri, @Nullable String contentType, @NotNull Map<String, String> headers, @NotNull Callback listener) {
        String id = String.valueOf(ThreadLocalRandom.current().nextInt(1000));

        JsonObject obj = new JsonObject();
        obj.addProperty("jsonrpc", "2.0");
        obj.addProperty("id", id);
        obj.addProperty("method", "mercury.request");

        JsonObject params = new JsonObject();
        obj.add("params", params);

        params.addProperty("method", method);
        params.addProperty("uri", uri);
        if (contentType != null && !contentType.isEmpty())
            params.addProperty("contentType", contentType);

        if (!headers.isEmpty()) {
            JsonArray array = new JsonArray(headers.size());
            params.add("headers", array);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                JsonObject e = new JsonObject();
                e.addProperty("key", entry.getKey());
                e.addProperty("value", entry.getValue());
                array.add(e);
            }
        }

        client.send(obj.toString());
        requests.put(id, listener);
    }

    public interface Callback {
        void response(@NotNull JsonObject json);
    }

    public interface Listener {
        void connected();

        void error(@NotNull Throwable ex);

        void closed();

        void unknownResponse(@NotNull JsonObject obj);
    }

    private class Client extends WebSocketClient {
        Client(@NotNull URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake serverHandshake) {
            Platform.runLater(listener::connected);
        }

        @Override
        public void onMessage(String s) {
            JsonObject obj = PARSER.parse(s).getAsJsonObject();
            String id = obj.get("id").getAsString();
            Callback callback = requests.remove(id);
            if (callback == null) Platform.runLater(() -> listener.unknownResponse(obj));
            else Platform.runLater(() -> callback.response(obj));
        }

        @Override
        public void onClose(int i, String s, boolean b) {
            Platform.runLater(listener::closed);
        }

        @Override
        public void onError(Exception ex) {
            Platform.runLater(() -> listener.error(ex));
        }
    }
}
