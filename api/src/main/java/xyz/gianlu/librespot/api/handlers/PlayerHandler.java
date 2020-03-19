package xyz.gianlu.librespot.api.handlers;

import com.google.gson.JsonObject;
import com.spotify.metadata.Metadata;
import io.undertow.server.HttpServerExchange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.api.SessionWrapper;
import xyz.gianlu.librespot.api.Utils;
import xyz.gianlu.librespot.common.ProtobufToJson;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.model.EpisodeId;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.mercury.model.TrackId;
import xyz.gianlu.librespot.player.PlayerRunner;

import java.util.Deque;
import java.util.Map;
import java.util.Objects;

public final class PlayerHandler extends AbsSessionHandler {

    public PlayerHandler(@NotNull SessionWrapper wrapper) {
        super(wrapper);
    }

    private void setVolume(HttpServerExchange exchange, Session session, @Nullable String valStr) {
        if (valStr == null) {
            Utils.invalidParameter(exchange, "volume");
            return;
        }

        int val;
        try {
            val = Integer.parseInt(valStr);
        } catch (Exception ex) {
            Utils.invalidParameter(exchange, "volume", "Not an integer");
            return;
        }

        if (val < 0 || val > PlayerRunner.VOLUME_MAX) {
            Utils.invalidParameter(exchange, "volume", "Must be >= 0 and <= " + PlayerRunner.VOLUME_MAX);
            return;
        }

        session.player().setVolume(val);
    }

    private void load(HttpServerExchange exchange, Session session, @Nullable String uri, boolean play) {
        if (uri == null) {
            Utils.invalidParameter(exchange, "uri");
            return;
        }

        session.player().load(uri, play);
    }

    private void current(HttpServerExchange exchange, Session session) {
        PlayableId id = session.player().currentPlayableId();

        JsonObject obj = new JsonObject();
        if (id != null) obj.addProperty("current", id.toSpotifyUri());

        long time = session.player().time();
        obj.addProperty("trackTime", time);

        if (id instanceof TrackId) {
            Metadata.Track track = session.player().currentTrack();
            if (track == null) {
                Utils.internalError(exchange, "Missing track metadata. Try again.");
                return;
            }

            obj.add("track", ProtobufToJson.convert(track));
        } else if (id instanceof EpisodeId) {
            Metadata.Episode episode = session.player().currentEpisode();
            if (episode == null) {
                Utils.internalError(exchange, "Missing episode metadata. Try again.");
                return;
            }

            obj.add("episode", ProtobufToJson.convert(episode));
        } else {
            Utils.internalError(exchange, "Invalid PlayableId: " + id);
            return;
        }

        exchange.getResponseSender().send(obj.toString());
    }

    @Override
    protected void handleRequest(@NotNull HttpServerExchange exchange, @NotNull Session session) throws Exception {
        exchange.startBlocking();
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        Map<String, Deque<String>> params = Utils.readParameters(exchange);
        String cmdStr = Utils.getFirstString(params, "cmd");
        if (cmdStr == null) {
            Utils.invalidParameter(exchange, "cmd");
            return;
        }

        Command cmd = Command.parse(cmdStr);
        if (cmd == null) {
            Utils.invalidParameter(exchange, "cmd");
            return;
        }

        switch (cmd) {
            case CURRENT:
                current(exchange, session);
                return;
            case SET_VOLUME:
                setVolume(exchange, session, Utils.getFirstString(params, "volume"));
                return;
            case VOLUME_UP:
                session.player().volumeUp();
                return;
            case VOLUME_DOWN:
                session.player().volumeDown();
                return;
            case LOAD:
                load(exchange, session, Utils.getFirstString(params, "uri"), Utils.getFirstBoolean(params, "play"));
                return;
            case PAUSE:
                session.player().pause();
                return;
            case RESUME:
                session.player().play();
                return;
            case PREV:
                session.player().previous();
                return;
            case NEXT:
                session.player().next();
                return;
            default:
                throw new IllegalArgumentException(cmd.name());
        }
    }

    private enum Command {
        LOAD("load"), PAUSE("pause"), RESUME("resume"),
        NEXT("next"), PREV("prev"), SET_VOLUME("set-volume"),
        VOLUME_UP("volume-up"), VOLUME_DOWN("volume-down"), CURRENT("current");

        private final String name;

        Command(String name) {
            this.name = name;
        }

        @Nullable
        private static Command parse(@NotNull String val) {
            for (Command cmd : values())
                if (Objects.equals(cmd.name, val))
                    return cmd;

            return null;
        }
    }
}
