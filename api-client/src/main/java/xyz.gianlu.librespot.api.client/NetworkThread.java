package xyz.gianlu.librespot.api.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Gianlu
 */
public class NetworkThread extends Thread {
    private static final JsonParser PARSER = new JsonParser();
    private final Client client;
    private final Listener listener;
    private final Map<String, Callback> requests = new HashMap<>();

    public NetworkThread(@NotNull String uri, @NotNull Listener listener) {
        client = new Client(URI.create(uri));
        this.listener = listener;
        start();
    }

    public void close() {
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

    public void send(String id, String method, String params, @NotNull Callback listener) {
        JsonObject obj = new JsonObject();
        obj.addProperty("jsonrpc", "2.0");
        obj.addProperty("id", id);
        obj.addProperty("method", method);
        if (!params.isEmpty()) obj.add("params", PARSER.parse(params));

        client.send(obj.toString());
        requests.put(id, listener);
    }

    public interface Callback {
        void response(@NotNull JsonObject json);
    }

    public interface Listener {
        void connected();

        void closed();

        void unknownResponse(@NotNull JsonObject obj);
    }

    private class Client extends WebSocketClient {
        Client(@NotNull URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake serverHandshake) {
            listener.connected();
        }

        @Override
        public void onMessage(String s) {
            JsonObject obj = PARSER.parse(s).getAsJsonObject();
            String id = obj.get("id").getAsString();
            Callback callback = requests.get(id);
            if (callback == null) listener.unknownResponse(obj);
            else callback.response(obj);
        }

        @Override
        public void onClose(int i, String s, boolean b) {
            listener.closed();
        }

        @Override
        public void onError(Exception e) {
            e.printStackTrace();
        }
    }
}
