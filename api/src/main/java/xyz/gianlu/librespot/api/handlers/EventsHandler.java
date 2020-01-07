package xyz.gianlu.librespot.api.handlers;

import com.google.gson.JsonObject;
import com.spotify.metadata.proto.Metadata;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.ProtobufToJson;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.Player;

public class EventsHandler extends WebSocketProtocolHandshakeHandler implements Player.EventsListener {
    private static final Logger LOGGER = Logger.getLogger(EventsHandler.class);

    public EventsHandler(@NotNull Session session) {
        super((WebSocketConnectionCallback) (exchange, channel) -> LOGGER.info(String.format("Accepted new websocket connection from %s.", channel.getSourceAddress().getAddress())));
        session.player().addEventsListener(this);
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
}
