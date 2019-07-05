package xyz.gianlu.librespot.connectstate;

import com.google.gson.JsonObject;
import com.google.protobuf.InvalidProtocolBufferException;
import com.spotify.connectstate.model.Connect;
import com.spotify.connectstate.model.Player;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.BytesArrayList;
import xyz.gianlu.librespot.Version;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TimeProvider;
import xyz.gianlu.librespot.dealer.DealerClient;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.player.PlayerRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

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

    @Override
    public void onMessage(@NotNull String uri, @NotNull Map<String, String> headers, @NotNull BytesArrayList payloads) throws IOException {
        if (uri.startsWith("hm://pusher/v1/connections/")) {
            connectionId = headers.get("Spotify-Connection-Id");
            notifyReady();
        } else if (uri.startsWith("hm://connect-state/v1/cluster")) {
            System.out.println("RECEIVED CLUSTER UPDATE");

            /*
            try {
                System.out.println(Connect.ClusterUpdate.parseFrom(payloads.stream()));
            } catch (IOException e) {
                e.printStackTrace();
            }
            */
        } else if (uri.startsWith("hm://connect-state/v1/connect/volume")) {
            Connect.SetVolumeCommand cmd = Connect.SetVolumeCommand.parseFrom(payloads.stream());
            deviceInfo.setVolume(cmd.getVolume());

            LOGGER.trace(String.format("Update volume. {volume: %d/65536}", cmd.getVolume()));
            if (cmd.hasCommandOptions()) {
                putState.setLastCommandMessageId(cmd.getCommandOptions().getMessageId())
                        .clearLastCommandSentByDeviceId();
            }

            notifyVolumeChange();
        } else {
            System.out.println("RECEIVED MSG: " + uri); // FIXME
            System.out.println(payloads.toHex());
        }
    }

    @Override
    public void onRequest(@NotNull String mid, int pid, @NotNull String sender, @NotNull JsonObject command) {
        putState.setLastCommandMessageId(pid).setLastCommandSentByDeviceId(sender);

        Endpoint endpoint = Endpoint.parse(command.get("endpoint").getAsString());

        byte[] data = null;
        if (command.has("data"))
            data = Base64.getDecoder().decode(command.get("data").getAsString());

        String value = null;
        if (command.has("value"))
            value = command.get("value").getAsString();

        notifyCommand(endpoint, new CommandBody(data, value));
    }

    public void updateState(@NotNull Connect.PutStateReason reason, @NotNull Player.PlayerState state) {
        try {
            putState(reason, state);
            LOGGER.info(String.format("Updated state. {reason: %s}", reason));
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed updating state!", ex);
        }
    }

    public void setIsActive(boolean active) {
        if (!putState.getIsActive() && active)
            putState.setIsActive(true).setStartedPlayingAt(TimeProvider.currentTimeMillis());
        else
            putState.setIsActive(false).clearStartedPlayingAt();
    }

    private void putState(@NotNull Connect.PutStateReason reason, @NotNull Player.PlayerState state) throws IOException, MercuryClient.MercuryException {
        if (connectionId == null) throw new IllegalStateException();

        putState.setPutStateReason(reason)
                .getDeviceBuilder().setDeviceInfo(deviceInfo).setPlayerState(state);

        session.api().putConnectState(connectionId, putState.build());
    }

    public int getVolume() {
        return deviceInfo.getVolume();
    }

    public enum Endpoint {
        Pause("pause"), Resume("resume"), SeekTo("seek_to"), SkipNext("skip_next"),
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
    }

    public static class CommandBody {
        private final byte[] data;
        private final String value;

        private CommandBody(@Nullable byte[] data, @Nullable String value) {
            this.data = data;
            this.value = value;
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
    }
}
