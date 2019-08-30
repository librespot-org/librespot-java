package xyz.gianlu.librespot.connectstate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TextFormat;
import com.spotify.connectstate.model.Connect;
import com.spotify.connectstate.model.Player;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spotify.player.proto.ContextTrackOuterClass.ContextTrack;
import xyz.gianlu.librespot.BytesArrayList;
import xyz.gianlu.librespot.Version;
import xyz.gianlu.librespot.common.ProtoUtils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TimeProvider;
import xyz.gianlu.librespot.dealer.DealerClient;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.player.PlayerRunner;

import java.io.IOException;
import java.util.*;

/**
 * @author Gianlu
 */
public class DeviceStateHandler implements DealerClient.MessageListener {
    private static final Logger LOGGER = Logger.getLogger(DeviceStateHandler.class);
    private final Session session;
    private final Connect.DeviceInfo.Builder deviceInfo;
    private final List<Listener> listeners = new ArrayList<>();
    private final Connect.PutStateRequest.Builder putState;
    private volatile String connectionId = null;

    public DeviceStateHandler(@NotNull Session session) {
        this.session = session;
        this.deviceInfo = initializeDeviceInfo(session);
        this.putState = Connect.PutStateRequest.newBuilder()
                .setMemberType(Connect.MemberType.CONNECT_STATE)
                .setDevice(Connect.Device.newBuilder()
                        .setDeviceInfo(deviceInfo)
                        .build());

        session.dealer().addListener(this, "hm://pusher/v1/connections/", "hm://connect-state/v1/");
    }

    @NotNull
    private static Connect.DeviceInfo.Builder initializeDeviceInfo(@NotNull Session session) {
        return Connect.DeviceInfo.newBuilder()
                .setCanPlay(true)
                .setVolume(session.conf().initialVolume())
                .setName(session.deviceName())
                .setDeviceId(session.deviceId())
                .setDeviceType(session.deviceType())
                .setDeviceSoftwareVersion(Version.versionString())
                .setSpircVersion("3.2.6")
                .setCapabilities(Connect.Capabilities.newBuilder()
                        .setCanBePlayer(true).setGaiaEqConnectId(true).setSupportsLogout(true)
                        .setIsObservable(true).setCommandAcks(true).setSupportsRename(false)
                        .setSupportsPlaylistV2(true).setIsControllable(true).setSupportsTransferCommand(true)
                        .setSupportsCommandRequest(true).setVolumeSteps(PlayerRunner.VOLUME_STEPS)
                        .addSupportedTypes("audio/episode")
                        .addSupportedTypes("audio/track")
                        .build());
    }

    public void addListener(@NotNull Listener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public void removeListener(@NotNull Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void notifyReady() {
        synchronized (listeners) {
            for (Listener listener : listeners) {
                listener.ready();
            }
        }
    }

    private void notifyCommand(@NotNull Endpoint endpoint, @NotNull CommandBody data) {
        synchronized (listeners) {
            for (Listener listener : listeners) {
                try {
                    listener.command(endpoint, data);
                } catch (InvalidProtocolBufferException ex) {
                    LOGGER.error("Failed parsing command!", ex);
                }
            }
        }
    }

    private void notifyVolumeChange() {
        synchronized (listeners) {
            for (Listener listener : listeners)
                listener.volumeChanged();
        }
    }

    private void notifyNotActive() {
        synchronized (listeners) {
            for (Listener listener : listeners)
                listener.notActive();
        }
    }

    @Override
    public synchronized void onMessage(@NotNull String uri, @NotNull Map<String, String> headers, @NotNull String[] payloads) throws IOException {
        if (uri.startsWith("hm://pusher/v1/connections/")) {
            connectionId = headers.get("Spotify-Connection-Id");
            notifyReady();
        } else if (Objects.equals(uri, "hm://connect-state/v1/connect/volume")) {
            Connect.SetVolumeCommand cmd = Connect.SetVolumeCommand.parseFrom(BytesArrayList.streamBase64(payloads));
            deviceInfo.setVolume(cmd.getVolume());

            LOGGER.trace(String.format("Update volume. {volume: %d/%d}", cmd.getVolume(), PlayerRunner.VOLUME_MAX));
            if (cmd.hasCommandOptions()) {
                putState.setLastCommandMessageId(cmd.getCommandOptions().getMessageId())
                        .clearLastCommandSentByDeviceId();
            }

            notifyVolumeChange();
        } else if (Objects.equals(uri, "hm://connect-state/v1/cluster")) {
            Connect.ClusterUpdate update = Connect.ClusterUpdate.parseFrom(BytesArrayList.streamBase64(payloads));

            long now = TimeProvider.currentTimeMillis();
            LOGGER.debug(String.format("Received cluster update at %d: %s", now, TextFormat.shortDebugString(update)));

            long ts = update.getCluster().getTimestamp() - 3000; // Workaround
            if (!session.deviceId().equals(update.getCluster().getActiveDeviceId()) && isActive() && now > startedPlayingAt() && ts > startedPlayingAt())
                notifyNotActive();
        } else {
            LOGGER.warn(String.format("Message left unhandled! {uri: %s, rawPayloads: %s}", uri, Arrays.toString(payloads)));
        }
    }

    @Override
    public void onRequest(@NotNull String mid, int pid, @NotNull String sender, @NotNull JsonObject command) {
        putState.setLastCommandMessageId(pid).setLastCommandSentByDeviceId(sender);

        Endpoint endpoint = Endpoint.parse(command.get("endpoint").getAsString());
        notifyCommand(endpoint, new CommandBody(command));
    }

    public void updateState(@NotNull Connect.PutStateReason reason, @NotNull Player.PlayerState state) {
        try {
            putState(reason, state);
            LOGGER.info(String.format("Updated state. {ts: %d, reason: %s, state: %s}", TimeProvider.currentTimeMillis(), reason, TextFormat.shortDebugString(state)));
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed updating state!", ex);
        }
    }

    private synchronized long startedPlayingAt() {
        return putState.getStartedPlayingAt();
    }

    private synchronized boolean isActive() {
        return putState.getIsActive();
    }

    public synchronized void setIsActive(boolean active) {
        if (active) {
            if (!putState.getIsActive()) {
                long now = TimeProvider.currentTimeMillis();
                putState.setIsActive(true).setStartedPlayingAt(now);
                LOGGER.debug(String.format("Device is now active. {ts: %d}", now));
            }
        } else {
            putState.setIsActive(false).clearStartedPlayingAt();
        }
    }

    private synchronized void putState(@NotNull Connect.PutStateReason reason, @NotNull Player.PlayerState state) throws IOException, MercuryClient.MercuryException {
        if (connectionId == null) throw new IllegalStateException();

        putState.setPutStateReason(reason)
                .getDeviceBuilder().setDeviceInfo(deviceInfo).setPlayerState(state);

        session.api().putConnectState(connectionId, putState.build());
    }

    public synchronized int getVolume() {
        return deviceInfo.getVolume();
    }

    public enum Endpoint {
        Play("play"), Pause("pause"), Resume("resume"), SeekTo("seek_to"), SkipNext("skip_next"),
        SkipPrev("skip_prev"), SetShufflingContext("set_shuffling_context"), SetRepeatingContext("set_repeating_context"),
        SetRepeatingTrack("set_repeating_track"), UpdateContext("update_context"), SetQueue("set_queue"),
        AddToQueue("add_to_queue"), Transfer("transfer");

        private final String val;

        Endpoint(@NotNull String val) {
            this.val = val;
        }

        @NotNull
        public static Endpoint parse(@NotNull String value) {
            for (Endpoint e : values())
                if (e.val.equals(value))
                    return e;

            throw new IllegalArgumentException("Unknown endpoint for " + value);
        }
    }

    public interface Listener {
        void ready();

        void command(@NotNull Endpoint endpoint, @NotNull CommandBody data) throws InvalidProtocolBufferException;

        void volumeChanged();

        void notActive();
    }

    public static final class PlayCommandHelper {
        private PlayCommandHelper() {
        }

        @Nullable
        public static Boolean isInitiallyPaused(@NotNull JsonObject obj) {
            JsonObject options = obj.getAsJsonObject("options");
            if (options == null) return null;

            JsonElement elm;
            if ((elm = options.get("initially_paused")) != null && elm.isJsonPrimitive()) return elm.getAsBoolean();
            else return null;
        }

        @Nullable
        public static String getContextUri(JsonObject obj) {
            JsonObject context = obj.getAsJsonObject("context");
            if (context == null) return null;

            JsonElement elm;
            if ((elm = context.get("uri")) != null && elm.isJsonPrimitive()) return elm.getAsString();
            else return null;
        }

        @NotNull
        public static JsonObject getPlayOrigin(@NotNull JsonObject obj) {
            return obj.getAsJsonObject("play_origin");
        }

        @NotNull
        public static JsonObject getContext(@NotNull JsonObject obj) {
            return obj.getAsJsonObject("context");
        }

        @NotNull
        public static JsonObject getPlayerOptionsOverride(@NotNull JsonObject obj) {
            return obj.getAsJsonObject("options").getAsJsonObject("player_options_override");
        }

        @Nullable
        public static String getSkipToUid(@NotNull JsonObject obj) {
            JsonObject parent = obj.getAsJsonObject("options");
            if (parent == null) return null;

            parent = parent.getAsJsonObject("skip_to");
            if (parent == null) return null;

            JsonElement elm;
            if ((elm = parent.get("track_uid")) != null && elm.isJsonPrimitive()) return elm.getAsString();
            else return null;
        }

        @Nullable
        public static String getSkipToUri(@NotNull JsonObject obj) {
            JsonObject parent = obj.getAsJsonObject("options");
            if (parent == null) return null;

            parent = parent.getAsJsonObject("skip_to");
            if (parent == null) return null;

            JsonElement elm;
            if ((elm = parent.get("track_uri")) != null && elm.isJsonPrimitive()) return elm.getAsString();
            else return null;
        }

        @Nullable
        public static List<ContextTrack> getNextTracks(@NotNull JsonObject obj) {
            JsonArray prevTracks = obj.getAsJsonArray("next_tracks");
            if (prevTracks == null) return null;

            return ProtoUtils.jsonToContextTracks(prevTracks);
        }

        @Nullable
        public static List<ContextTrack> getPrevTracks(@NotNull JsonObject obj) {
            JsonArray prevTracks = obj.getAsJsonArray("prev_tracks");
            if (prevTracks == null) return null;

            return ProtoUtils.jsonToContextTracks(prevTracks);
        }

        @Nullable
        public static ContextTrack getTrack(@NotNull JsonObject obj) {
            JsonObject track = obj.getAsJsonObject("track");
            if (track == null) return null;
            return ProtoUtils.jsonToContextTrack(track);
        }

        @Nullable
        public static Integer getSkipToIndex(@NotNull JsonObject obj) {
            JsonObject parent = obj.getAsJsonObject("options");
            if (parent == null) return null;

            parent = parent.getAsJsonObject("skip_to");
            if (parent == null) return null;

            JsonElement elm;
            if ((elm = parent.get("track_index")) != null && elm.isJsonPrimitive()) return elm.getAsInt();
            else return null;
        }

        @Nullable
        public static Integer getSeekTo(@NotNull JsonObject obj) {
            JsonObject options = obj.getAsJsonObject("options");
            if (options == null) return null;

            JsonElement elm;
            if ((elm = options.get("seek_to")) != null && elm.isJsonPrimitive()) return elm.getAsInt();
            else return null;
        }

        @NotNull
        public static JsonArray getPages(@NotNull JsonObject obj) {
            JsonObject context = getContext(obj);
            return context.getAsJsonArray("pages");
        }

        @NotNull
        public static JsonObject getMetadata(@NotNull JsonObject obj) {
            return getContext(obj).getAsJsonObject("metadata");
        }
    }

    public static class CommandBody {
        private final JsonObject obj;
        private final byte[] data;
        private final String value;

        private CommandBody(@NotNull JsonObject obj) {
            this.obj = obj;

            if (obj.has("data")) data = Base64.getDecoder().decode(obj.get("data").getAsString());
            else data = null;

            if (obj.has("value")) value = obj.get("value").getAsString();
            else value = null;
        }

        @NotNull
        public JsonObject obj() {
            return obj;
        }

        public byte[] data() {
            return data;
        }

        public String value() {
            return value;
        }

        public Integer valueInt() {
            return value == null ? null : Integer.parseInt(value);
        }

        public Boolean valueBool() {
            return value == null ? null : Boolean.parseBoolean(value);
        }
    }
}
