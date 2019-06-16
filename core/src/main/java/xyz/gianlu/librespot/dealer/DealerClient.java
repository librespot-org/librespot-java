package xyz.gianlu.librespot.dealer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spotify.connectstate.model.Connect;
import com.spotify.connectstate.model.Player;
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
    private static final JsonParser PARSER = new JsonParser();
    private final Session session;
    private final WebSocket ws;

    public DealerClient(@NotNull Session session) throws IOException, MercuryClient.MercuryException {
        this.session = session;
        this.ws = new OkHttpClient().newWebSocket(new Request.Builder()
                .url(String.format("wss://%s/?access_token=%s", ApResolver.getRandomDealer(), session.tokens().get("playlist-read", null)))
                .build(), this);
    }

    @NotNull
    private static JsonObject createMessage(@NotNull MessageType type) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type.value());
        return obj;
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        System.out.println("OPEN");
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        t.printStackTrace();
    }

    private void sendHello(String connId) {
        Connect.PutStateRequest.Builder builder = Connect.PutStateRequest.newBuilder();
        builder.setPutStateReason(Connect.PutStateReason.NEW_DEVICE);
        builder.setMemberType(Connect.MemberType.CONNECT_STATE);
        builder.setDevice(Connect.Device.newBuilder()
                .setDeviceInfo(Connect.DeviceInfo.newBuilder()
                        .setCanPlay(true)
                        .setName(session.conf().deviceName())
                        .setVolume(65535)
                        .build())
                .setPlayerState(Player.PlayerState.newBuilder().build())
                .build());

        try {
            session.api().send(connId, builder.build().toByteArray());
        } catch (IOException | MercuryClient.MercuryException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        JsonObject obj = PARSER.parse(text).getAsJsonObject();

        MessageType type = MessageType.parse(obj.get("type").getAsString());
        switch (type) {
            case PING:
                sendPong();
                break;
            case PONG:
                ackPong();
                break;
            case MESSAGE:
                handleMessage(obj);
                break;
        }
    }

    private void ackPong() {
        // TODO: Send ping sometimes
    }

    private void sendPong() {
        ws.send(createMessage(MessageType.PONG).toString());
    }

    private void handleMessage(@NotNull JsonObject obj) {
        System.out.println("MESSAGE: " + obj);

        if (obj.get("uri").getAsString().startsWith("hm://pusher/v1/connections/")) {
            sendHello(obj.getAsJsonObject("headers").get("Spotify-Connection-Id").getAsString());
        }
    }
}
