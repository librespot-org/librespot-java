package xyz.gianlu.librespot.connectstate;

import com.google.protobuf.InvalidProtocolBufferException;
import com.spotify.connectstate.model.Connect;
import com.spotify.connectstate.model.Player;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.BytesArrayList;
import xyz.gianlu.librespot.Version;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TimeProvider;
import xyz.gianlu.librespot.dealer.DealerClient;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.player.PlayerRunner;

import java.io.IOException;
import java.util.ArrayList;
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

        session.dealer().addListener(this, "hm://pusher/v1/connections/", "hm://");
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

    private void notifyCommand(@NotNull String endpoint, @NotNull byte[] data) {
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
    public void onRequest(@NotNull String mid, int pid, @NotNull String sender, @NotNull String endpoint, @NotNull byte[] data) {
        putState.setLastCommandMessageId(pid).setLastCommandSentByDeviceId(sender);
        notifyCommand(endpoint, data);
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

    public interface Listener {
        void ready();

        void command(@NotNull String endpoint, @NotNull byte[] data) throws InvalidProtocolBufferException;

        void volumeChanged();
    }
}
