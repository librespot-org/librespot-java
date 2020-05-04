package xyz.gianlu.librespot.api.handlers;

import com.google.gson.JsonObject;
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
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.TrackOrEpisode;

import java.util.Deque;
import java.util.Map;
import java.util.Objects;

public final class PlayerHandler extends AbsSessionHandler {

    public PlayerHandler(@NotNull SessionWrapper wrapper) {
        super(wrapper);
    }

    private static void setVolume(HttpServerExchange exchange, @NotNull Session session, @Nullable String valStr) {
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

        if (val < 0 || val > Player.VOLUME_MAX) {
            Utils.invalidParameter(exchange, "volume", "Must be >= 0 and <= " + Player.VOLUME_MAX);
            return;
        }

        session.player().setVolume(val);
    }

    private static void load(HttpServerExchange exchange, @NotNull Session session, @Nullable String uri, boolean play) {
        if (uri == null) {
            Utils.invalidParameter(exchange, "uri");
            return;
        }

        session.player().load(uri, play);
    }

    private static void current(HttpServerExchange exchange, @NotNull Session session) {
        PlayableId id;
        try {
            id = session.player().currentPlayable();
        } catch (IllegalStateException ex) {
            id = null;
        }

        JsonObject obj = new JsonObject();
        if (id != null) obj.addProperty("current", id.toSpotifyUri());

        long time = session.player().time();
        obj.addProperty("trackTime", time);

        TrackOrEpisode metadata = session.player().currentMetadata();
        if (id instanceof TrackId) {
            if (metadata == null || metadata.track == null) {
                Utils.internalError(exchange, "Missing track metadata. Try again.");
                return;
            }

            obj.add("track", ProtobufToJson.convert(metadata.track));
        } else if (id instanceof EpisodeId) {
            if (metadata == null || metadata.episode == null) {
                Utils.internalError(exchange, "Missing episode metadata. Try again.");
                return;
            }

            obj.add("episode", ProtobufToJson.convert(metadata.episode));
        } else {
            Utils.internalError(exchange, "Invalid PlayableId: " + id);
            return;
        }

        exchange.getResponseSender().send(obj.toString());
    }

    private static void tracks(HttpServerExchange exchange, @NotNull Session session, boolean withQueue) {
        Player.Tracks tracks = session.player().tracks(withQueue);

        JsonObject obj = new JsonObject();
        obj.add("current", tracks.current == null ? null : ProtobufToJson.convert(tracks.current));
        obj.add("next", ProtobufToJson.convertList(tracks.next));
        obj.add("prev", ProtobufToJson.convertList(tracks.previous));
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
            case TRACKS:
                tracks(exchange, session, Utils.getFirstBoolean(params, "withQueue"));
                return;
            default:
                throw new IllegalArgumentException(cmd.name());
        }
    }

    private enum Command {
        LOAD("load"), PAUSE("pause"), RESUME("resume"), TRACKS("tracks"),
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
