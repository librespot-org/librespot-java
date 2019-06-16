package xyz.gianlu.librespot.connectstate;

import com.google.protobuf.InvalidProtocolBufferException;
import com.spotify.connectstate.model.Connect;
import com.spotify.connectstate.model.Player;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.Version;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
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
    private volatile String connectionId = null;

    public DeviceStateHandler(@NotNull Session session) {
        this.session = session;
        this.deviceInfo = initializeDeviceInfo(session);

        session.dealer().addListener(this, "hm://pusher/v1/connections/", "hm://");
    }

    @NotNull
    private static Connect.DeviceInfo.Builder initializeDeviceInfo(@NotNull Session session) {
        return Connect.DeviceInfo.newBuilder()
                .setCanPlay(true)
                .setVolume(session.conf().initialVolume())
                .setName(session.deviceName())
                .setDeviceType(session.deviceType())
                .setDeviceSoftwareVersion(Version.versionString())
                .setSpircVersion("3.2.6")
                .setCapabilities(Connect.Capabilities.newBuilder()
                        .setCanBePlayer(true)
                        .setGaiaEqConnectId(true)
                        .setSupportsLogout(true)
                        .setIsObservable(true)
                        .setVolumeSteps(PlayerRunner.VOLUME_STEPS)
                        // .addSupportedTypes("audio/ad")
                        .addSupportedTypes("audio/episode")
                        // .addSupportedTypes("audio/interruption")
                        // .addSupportedTypes("audio/local")
                        .addSupportedTypes("audio/track")
                        // .addSupportedTypes("video/ad")
                        // .addSupportedTypes("video/episode")
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

    private void notifyFrame(@NotNull Spirc.Frame frame) {
        synchronized (listeners) {
            for (Listener listener : listeners) {
                listener.frame(frame);
            }
        }
    }

    @Override
    public void onMessage(@NotNull String uri, @NotNull Map<String, String> headers, @NotNull byte[][] payloads) {
        if (uri.startsWith("hm://pusher/v1/connections/")) {
            connectionId = headers.get("Spotify-Connection-Id");
            notifyReady();
        } else if (uri.startsWith("hm://remote/3/user/")) {
            try {
                notifyFrame(Spirc.Frame.parseFrom(payloads[0]));
            } catch (InvalidProtocolBufferException ex) {
                LOGGER.error("Failed paring frame!", ex);
            }
        } else {
            System.out.println("RECEIVED MSG: " + uri); // FIXME
            System.out.println(Utils.bytesToHex(payloads[0]));
        }
    }

    public void updateState(@NotNull Connect.PutStateReason reason, @NotNull Player.PlayerState state) {
        try {
            putState(reason, state);
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed updating state!", ex);
        }
    }

    private void putState(@NotNull Connect.PutStateReason reason, @NotNull Player.PlayerState state) throws IOException, MercuryClient.MercuryException {
        if (connectionId == null) throw new IllegalStateException();

        Connect.PutStateRequest.Builder builder = Connect.PutStateRequest.newBuilder();
        builder.setPutStateReason(reason);
        builder.setMemberType(Connect.MemberType.SPIRC_V3);
        builder.setDevice(Connect.Device.newBuilder()
                .setDeviceInfo(deviceInfo)
                .setPlayerState(state)
                .build());

        session.api().putConnectState(connectionId, builder.build());
    }

    @NotNull
    public Connect.DeviceInfo.Builder info() {
        return deviceInfo;
    }

    public interface Listener {
        void ready();

        void frame(@NotNull Spirc.Frame frame);
    }
}
