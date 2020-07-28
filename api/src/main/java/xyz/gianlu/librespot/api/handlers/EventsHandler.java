package xyz.gianlu.librespot.api.handlers;

import com.google.gson.JsonObject;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import xyz.gianlu.librespot.api.PlayerWrapper;
import xyz.gianlu.librespot.common.ProtobufToJson;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.metadata.PlayableId;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.TrackOrEpisode;

public final class EventsHandler extends WebSocketProtocolHandshakeHandler implements Player.EventsListener, PlayerWrapper.Listener, Session.ReconnectionListener {
    private static final Logger LOGGER = LogManager.getLogger(EventsHandler.class);

    public EventsHandler() {
        super((WebSocketConnectionCallback) (exchange, channel) -> LOGGER.info("Accepted new websocket connection from {}.", channel.getSourceAddress().getAddress()));
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
    public void onTrackChanged(@NotNull PlayableId id, @Nullable TrackOrEpisode metadata) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "trackChanged");
        obj.addProperty("uri", id.toSpotifyUri());
        if (metadata != null) {
            if (metadata.track != null) obj.add("track", ProtobufToJson.convert(metadata.track));
            else if (metadata.episode != null) obj.add("episode", ProtobufToJson.convert(metadata.episode));
        }

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
    public void onMetadataAvailable(@NotNull TrackOrEpisode metadata) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "metadataAvailable");
        if (metadata.track != null) obj.add("track", ProtobufToJson.convert(metadata.track));
        else if (metadata.episode != null) obj.add("episode", ProtobufToJson.convert(metadata.episode));
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
    public void onVolumeChanged(@Range(from = 0, to = 1) float volume) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "volumeChanged");
        obj.addProperty("value", volume);
        dispatch(obj);
    }

    @Override
    public void onPanicState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "panic");
        dispatch(obj);
    }

    @Override
    public void onSessionCleared(@NotNull Session old) {
        old.removeReconnectionListener(this);

        JsonObject obj = new JsonObject();
        obj.addProperty("event", "sessionCleared");
        dispatch(obj);
    }

    @Override
    public void onPlayerCleared(@NotNull Player old) {
        old.removeEventsListener(this);
    }

    @Override
    public void onNewSession(@NotNull Session session) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "sessionChanged");
        obj.addProperty("username", session.username());
        dispatch(obj);

        session.addReconnectionListener(this);
    }

    @Override
    public void onNewPlayer(@NotNull Player player) {
        player.addEventsListener(this);
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
