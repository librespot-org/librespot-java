package xyz.gianlu.librespot.player.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TextFormat;
import com.spotify.connectstate.Connect;
import com.spotify.connectstate.Player;
import com.spotify.context.ContextTrackOuterClass.ContextTrack;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.Version;
import xyz.gianlu.librespot.common.AsyncWorker;
import xyz.gianlu.librespot.common.ProtoUtils;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TimeProvider;
import xyz.gianlu.librespot.dealer.DealerClient;
import xyz.gianlu.librespot.dealer.DealerClient.RequestResult;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.player.Configuration;

import java.io.Closeable;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.*;

/**
 * @author Gianlu
 */
public final class DeviceStateHandler implements Closeable, DealerClient.MessageListener, DealerClient.RequestListener {
    private static final Logger LOGGER = LogManager.getLogger(DeviceStateHandler.class);

    static {
        try {
            ProtoUtils.overrideDefaultValue(Connect.PutStateRequest.getDescriptor().findFieldByName("has_been_playing_for_ms"), -1);
        } catch (IllegalAccessException | NoSuchFieldException ex) {
            LOGGER.warn("Failed changing default value!", ex);
        }
    }

    private final Session session;
    private final Connect.DeviceInfo.Builder deviceInfo;
    private final List<Listener> listeners = Collections.synchronizedList(new ArrayList<>());
    private final Connect.PutStateRequest.Builder putState;
    private final AsyncWorker<Connect.PutStateRequest> putStateWorker;
    private volatile String connectionId = null;

    public DeviceStateHandler(@NotNull Session session, @NotNull Configuration conf) {
        this.session = session;
        this.deviceInfo = initializeDeviceInfo(session, conf);
        this.putStateWorker = new AsyncWorker<>("put-state-worker", this::putConnectState);
        this.putState = Connect.PutStateRequest.newBuilder()
                .setMemberType(Connect.MemberType.CONNECT_STATE)
                .setDevice(Connect.Device.newBuilder()
                        .setDeviceInfo(deviceInfo)
                        .build());

        session.dealer().addMessageListener(this, "hm://pusher/v1/connections/", "hm://connect-state/v1/connect/volume", "hm://connect-state/v1/cluster");
        session.dealer().addRequestListener(this, "hm://connect-state/v1/");
    }

    @NotNull
    private static Connect.DeviceInfo.Builder initializeDeviceInfo(@NotNull Session session, @NotNull Configuration conf) {
        return Connect.DeviceInfo.newBuilder()
                .setCanPlay(true)
                .setVolume(conf.initialVolume)
                .setName(session.deviceName())
                .setDeviceId(session.deviceId())
                .setDeviceType(session.deviceType())
                .setDeviceSoftwareVersion(Version.versionString())
                .setSpircVersion("3.2.6")
                .setCapabilities(Connect.Capabilities.newBuilder()
                        .setCanBePlayer(true).setGaiaEqConnectId(true).setSupportsLogout(true)
                        .setIsObservable(true).setCommandAcks(true).setSupportsRename(false)
                        .setSupportsPlaylistV2(true).setIsControllable(true).setSupportsTransferCommand(true)
                        .setSupportsCommandRequest(true).setVolumeSteps(conf.volumeSteps)
                        .setSupportsGzipPushes(true).setNeedsFullPlayerState(false)
                        .addSupportedTypes("audio/episode")
                        .addSupportedTypes("audio/track")
                        .build());
    }

    public void addListener(@NotNull Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull Listener listener) {
        listeners.remove(listener);
    }

    private void notifyReady() {
        for (Listener listener : new ArrayList<>(listeners))
            listener.ready();
    }

    private void notifyCommand(@NotNull Endpoint endpoint, @NotNull CommandBody data) {
        if (listeners.isEmpty()) {
            LOGGER.warn("Cannot dispatch command because there are no listeners. {command: {}}", endpoint);
            return;
        }

        for (Listener listener : new ArrayList<>(listeners)) {
            try {
                listener.command(endpoint, data);
            } catch (InvalidProtocolBufferException ex) {
                LOGGER.error("Failed parsing command!", ex);
            }
        }
    }

    private void notifyVolumeChange() {
        for (Listener listener : new ArrayList<>(listeners))
            listener.volumeChanged();
    }

    private void notifyNotActive() {
        for (Listener listener : new ArrayList<>(listeners))
            listener.notActive();
    }

    private synchronized void updateConnectionId(@NotNull String newer) {
        try {
            newer = URLDecoder.decode(newer, "UTF-8");
        } catch (UnsupportedEncodingException ignored) {
        }

        if (connectionId == null || !connectionId.equals(newer)) {
            connectionId = newer;
            LOGGER.debug("Updated Spotify-Connection-Id: " + connectionId);
            notifyReady();
        }
    }

    @Override
    public void onMessage(@NotNull String uri, @NotNull Map<String, String> headers, @NotNull byte[] payload) throws IOException {
        if (uri.startsWith("hm://pusher/v1/connections/")) {
            updateConnectionId(headers.get("Spotify-Connection-Id"));
        } else if (Objects.equals(uri, "hm://connect-state/v1/connect/volume")) {
            Connect.SetVolumeCommand cmd = Connect.SetVolumeCommand.parseFrom(payload);
            synchronized (this) {
                deviceInfo.setVolume(cmd.getVolume());
                if (cmd.hasCommandOptions()) {
                    putState.setLastCommandMessageId(cmd.getCommandOptions().getMessageId())
                            .clearLastCommandSentByDeviceId();
                }
            }

            LOGGER.trace("Update volume. {volume: {}/{}}", cmd.getVolume(), xyz.gianlu.librespot.player.Player.VOLUME_MAX);
            notifyVolumeChange();
        } else if (Objects.equals(uri, "hm://connect-state/v1/cluster")) {
            Connect.ClusterUpdate update = Connect.ClusterUpdate.parseFrom(payload);

            long now = TimeProvider.currentTimeMillis();
            LOGGER.trace("Received cluster update at {}: {}", () -> now, () -> TextFormat.shortDebugString(update));

            long ts = update.getCluster().getTimestamp() - 3000; // Workaround
            if (!session.deviceId().equals(update.getCluster().getActiveDeviceId()) && isActive() && now > startedPlayingAt() && ts > startedPlayingAt())
                notifyNotActive();
        } else {
            LOGGER.warn("Message left unhandled! {uri: {}}", uri);
        }
    }

    @NotNull
    @Override
    public RequestResult onRequest(@NotNull String mid, int pid, @NotNull String sender, @NotNull JsonObject command) {
        putState.setLastCommandMessageId(pid).setLastCommandSentByDeviceId(sender);

        Endpoint endpoint = Endpoint.parse(command.get("endpoint").getAsString());
        notifyCommand(endpoint, new CommandBody(command));
        return RequestResult.SUCCESS;
    }

    @Nullable
    public synchronized String getLastCommandSentByDeviceId() {
        return putState.getLastCommandSentByDeviceId();
    }

    private synchronized long startedPlayingAt() {
        return putState.getStartedPlayingAt();
    }

    public synchronized boolean isActive() {
        return putState.getIsActive();
    }

    public synchronized void setIsActive(boolean active) {
        if (active) {
            if (!putState.getIsActive()) {
                long now = TimeProvider.currentTimeMillis();
                putState.setIsActive(true).setStartedPlayingAt(now);
                LOGGER.debug("Device is now active. {ts: {}}", now);
            }
        } else {
            putState.setIsActive(false).clearStartedPlayingAt();
        }
    }

    public synchronized void updateState(@NotNull Connect.PutStateReason reason, int playerTime, @NotNull Player.PlayerState state) {
        if (connectionId == null) throw new IllegalStateException();

        if (playerTime == -1) putState.clearHasBeenPlayingForMs();
        else putState.setHasBeenPlayingForMs(playerTime);

        putState.setPutStateReason(reason)
                .setClientSideTimestamp(TimeProvider.currentTimeMillis())
                .getDeviceBuilder().setDeviceInfo(deviceInfo).setPlayerState(state);

        putStateWorker.submit(putState.build());
    }

    public synchronized int getVolume() {
        return deviceInfo.getVolume();
    }

    public void setVolume(int val) {
        synchronized (this) {
            deviceInfo.setVolume(val);
        }

        notifyVolumeChange();
        LOGGER.trace("Update volume. {volume: {}/{}}", val, xyz.gianlu.librespot.player.Player.VOLUME_MAX);
    }

    @Override
    public void close() {
        session.dealer().removeMessageListener(this);
        session.dealer().removeRequestListener(this);

        putStateWorker.close();
        listeners.clear();
    }

    /**
     * Performs the network request related to {@link Connect.PutStateRequest}. This MUST be called only from {@link DeviceStateHandler#putStateWorker}.
     *
     * @param req The {@link Connect.PutStateRequest}
     */
    private void putConnectState(@NotNull Connect.PutStateRequest req) {
        try {
            session.api().putConnectState(connectionId, req);
            if (LOGGER.getLevel().isLessSpecificThan(Level.TRACE)) {
                LOGGER.info("Put state. {ts: {}, connId: {}, reason: {}, request: {}}", req.getClientSideTimestamp(),
                        Utils.truncateMiddle(connectionId, 10), req.getPutStateReason(), TextFormat.shortDebugString(putState));
            } else {
                LOGGER.info("Put state. {ts: {}, connId: {}, reason: {}}", req.getClientSideTimestamp(),
                        Utils.truncateMiddle(connectionId, 10), req.getPutStateReason());
            }
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.error("Failed updating state.", ex);
        }
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

        public static boolean willSkipToSomething(@NotNull JsonObject obj) {
            JsonObject parent = obj.getAsJsonObject("options");
            if (parent == null) return false;

            parent = parent.getAsJsonObject("skip_to");
            if (parent == null) return false;

            return parent.has("track_uid") || parent.has("track_uri") || parent.has("track_index");
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
