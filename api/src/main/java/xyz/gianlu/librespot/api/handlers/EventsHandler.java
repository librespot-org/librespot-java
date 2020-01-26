package xyz.gianlu.librespot.api.handlers;

import com.google.gson.JsonObject;
import com.spotify.metadata.Metadata;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.api.SessionWrapper;
import xyz.gianlu.librespot.common.ProtobufToJson;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.Player;

public final class EventsHandler extends WebSocketProtocolHandshakeHandler implements Player.EventsListener, SessionWrapper.Listener, Session.ReconnectionListener {
    private static final Logger LOGGER = Logger.getLogger(EventsHandler.class);

    public EventsHandler() {
        super((WebSocketConnectionCallback) (exchange, channel) -> LOGGER.info(String.format("Accepted new websocket connection from %s.", channel.getSourceAddress().getAddress())));
    }

    private void dispatch(@NotNull JsonObject obj) {
        for (WebSocketChannel channel : getPeerConnections())
            WebSockets.sendText(obj.toString(), channel, null);
    }

    @Override
    public void onContextChanged(@NotNull String newUri) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "contextChanged");
        obj.addProperty("uri", newUri);
        dispatch(obj);
    }

    @Override
    public void onTrackChanged(@NotNull PlayableId id, Metadata.@Nullable Track track, Metadata.@Nullable Episode episode) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "trackChanged");
        obj.addProperty("uri", id.toSpotifyUri());
        if (track != null) obj.add("track", ProtobufToJson.convert(track));
        if (episode != null) obj.add("episode", ProtobufToJson.convert(episode));
        dispatch(obj);
    }

    @Override
    public void onPlaybackPaused(long trackTime) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "playbackPaused");
        obj.addProperty("trackTime", trackTime);
        dispatch(obj);
    }

    @Override
    public void onPlaybackResumed(long trackTime) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "playbackResumed");
        obj.addProperty("trackTime", trackTime);
        dispatch(obj);
    }

    @Override
    public void onTrackSeeked(long trackTime) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "trackSeeked");
        obj.addProperty("trackTime", trackTime);
        dispatch(obj);
    }

    @Override
    public void onMetadataAvailable(Metadata.@Nullable Track track, Metadata.@Nullable Episode episode) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "metadataAvailable");
        if (track != null) obj.add("track", ProtobufToJson.convert(track));
        if (episode != null) obj.add("episode", ProtobufToJson.convert(episode));
        dispatch(obj);
    }

    @Override
    public void onPlaybackHaltStateChanged(boolean halted, long trackTime) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "playbackHaltStateChanged");
        obj.addProperty("trackTime", trackTime);
        obj.addProperty("halted", halted);
        dispatch(obj);
    }

    @Override
    public void onInactiveSession(boolean timeout) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "inactiveSession");
        obj.addProperty("timeout", timeout);
        dispatch(obj);
    }

    @Override
    public void onSessionCleared() {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "sessionCleared");
        dispatch(obj);
    }

    @Override
    public void onNewSession(@NotNull Session session) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "sessionChanged");
        obj.addProperty("username", session.username());
        dispatch(obj);

        session.player().addEventsListener(this);
        session.addReconnectionListener(this);
    }

    @Override
    public void onConnectionDropped() {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "connectionDropped");
        dispatch(obj);
    }

    @Override
    public void onConnectionEstablished() {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "connectionEstablished");
        dispatch(obj);
    }
}
