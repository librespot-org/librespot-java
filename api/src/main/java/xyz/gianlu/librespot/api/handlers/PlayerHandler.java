package xyz.gianlu.librespot.api.handlers;

import com.google.gson.JsonObject;
import io.undertow.server.HttpServerExchange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.api.PlayerWrapper;
import xyz.gianlu.librespot.api.Utils;
import xyz.gianlu.librespot.common.ProtobufToJson;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.metadata.EpisodeId;
import xyz.gianlu.librespot.metadata.PlayableId;
import xyz.gianlu.librespot.metadata.TrackId;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.TrackOrEpisode;

import java.util.Deque;
import java.util.Map;
import java.util.Objects;

public final class PlayerHandler extends AbsPlayerHandler {

    public PlayerHandler(@NotNull PlayerWrapper wrapper) {
        super(wrapper);
    }

    private static void setVolume(HttpServerExchange exchange, @NotNull Player player, @Nullable String valStr, @Nullable String stepStr) {
        if (valStr != null && stepStr != null) {
            // Reject requests with both parameters
            Utils.invalidParameter(exchange, "step", "Cannot be passed alongside volume");
        } else if (valStr != null) {
            // Absolute volume change
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

            player.setVolume(val);
        } else if (stepStr != null) {
            // Relative volume change in number of steps
            int val;
            try {
                val = Integer.parseInt(stepStr);
            } catch (Exception ex) {
                Utils.invalidParameter(exchange, "step", "Not an integer");
                return;
            }

            if (val > 0) player.volumeUp(val);
            else if (val < 0) player.volumeDown(Math.abs(val));
            else Utils.invalidParameter(exchange, "step", "Must be non zero");
        } else {
            Utils.invalidParameter(exchange, "volume");
        }
    }

    private static void setRepeat(HttpServerExchange exchange, @NotNull Player player, @Nullable String mode) {
        if (mode == null) {
            Utils.invalidParameter(exchange, "val");
            return;
        }

        switch (mode) {
            case "none":
                player.setRepeat(false, false);
                break;
            case "context":
                player.setRepeat(false, true);
                break;
            case "track":
                player.setRepeat(true, false);
                break;
            default:
                Utils.invalidParameter(exchange, "val", "Unknown mode");
                break;
        }
    }

    private static void load(HttpServerExchange exchange, @NotNull Player player, @Nullable String uri, boolean play, boolean shuffle) {
        if (uri == null) {
            Utils.invalidParameter(exchange, "uri");
            return;
        }

        player.load(uri, play, shuffle);
    }

    private static void current(HttpServerExchange exchange, @NotNull Player player) {
        PlayableId id = player.currentPlayable();

        JsonObject obj = new JsonObject();
        if (id != null) obj.addProperty("current", id.toSpotifyUri());

        long time = player.time();
        obj.addProperty("trackTime", time);

        TrackOrEpisode metadata = player.currentMetadata();
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
        } else if (id != null) {
            Utils.internalError(exchange, "Invalid PlayableId: " + id);
            return;
        }

        exchange.getResponseSender().send(obj.toString());
    }

    private static void tracks(@NotNull HttpServerExchange exchange, @NotNull Player player, boolean withQueue) {
        Player.Tracks tracks = player.tracks(withQueue);

        JsonObject obj = new JsonObject();
        obj.add("current", tracks.current == null ? null : ProtobufToJson.convert(tracks.current));
        obj.add("next", ProtobufToJson.convertList(tracks.next));
        obj.add("prev", ProtobufToJson.convertList(tracks.previous));
        exchange.getResponseSender().send(obj.toString());
    }

    private static void addToQueue(HttpServerExchange exchange, @NotNull Player player, String uri) {
        if (uri == null) {
            Utils.invalidParameter(exchange, "uri");
            return;
        }

        player.addToQueue(uri);
    }

    private static void removeFromQueue(HttpServerExchange exchange, @NotNull Player player, String uri) {
        if (uri == null) {
            Utils.invalidParameter(exchange, "uri");
            return;
        }

        player.removeFromQueue(uri);
    }

    private static void seek(HttpServerExchange exchange, @NotNull Player player, @Nullable String valStr) {
        if (valStr == null) {
            Utils.invalidParameter(exchange, "pos");
            return;
        }

        int pos;
        try {
            pos = Integer.parseInt(valStr);
        } catch (Exception ex) {
            Utils.invalidParameter(exchange, "pos", "Not an integer");
            return;
        }

        if (pos < 0) {
            Utils.invalidParameter(exchange, "pos", "Cannot be negative");
            return;
        }

        player.seek(pos);
    }

    @Override
    protected void handleRequest(@NotNull HttpServerExchange exchange, @NotNull Session session, @NotNull Player player) throws Exception {
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
                current(exchange, player);
                return;
            case SET_VOLUME:
                setVolume(exchange, player, Utils.getFirstString(params, "volume"), Utils.getFirstString(params, "step"));
                return;
            case VOLUME_UP:
                player.volumeUp();
                return;
            case VOLUME_DOWN:
                player.volumeDown();
                return;
            case LOAD:
                load(exchange, player, Utils.getFirstString(params, "uri"), Utils.getFirstBoolean(params, "play"), Utils.getFirstBoolean(params, "shuffle"));
                return;
            case PLAY_PAUSE:
                player.playPause();
                return;
            case PAUSE:
                player.pause();
                return;
            case RESUME:
                player.play();
                return;
            case PREV:
                player.previous();
                return;
            case NEXT:
                player.next();
                return;
            case SEEK:
                seek(exchange, player, Utils.getFirstString(params, "pos"));
                return;
            case TRACKS:
                tracks(exchange, player, Utils.getFirstBoolean(params, "withQueue"));
                return;
            case ADD_TO_QUEUE:
                addToQueue(exchange, player, Utils.getFirstString(params, "uri"));
                break;
            case REMOVE_FROM_QUEUE:
                removeFromQueue(exchange, player, Utils.getFirstString(params, "uri"));
                break;
            case SHUFFLE:
                player.setShuffle(Utils.getFirstBoolean(params, "val"));
                break;
            case REPEAT:
                setRepeat(exchange, player, Utils.getFirstString(params, "val"));
                break;
            default:
                throw new IllegalArgumentException(cmd.name());
        }
    }

    private enum Command {
        LOAD("load"), PLAY_PAUSE("play-pause"), PAUSE("pause"), RESUME("resume"), TRACKS("tracks"),
        NEXT("next"), PREV("prev"), SET_VOLUME("set-volume"), SEEK("seek"), SHUFFLE("shuffle"),
        VOLUME_UP("volume-up"), VOLUME_DOWN("volume-down"), CURRENT("current"), REPEAT("repeat"),
        ADD_TO_QUEUE("addToQueue"), REMOVE_FROM_QUEUE("removeFromQueue");

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
