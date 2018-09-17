package org.librespot.spotify.spirc;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.Version;
import org.librespot.spotify.mercury.MercuryClient;
import org.librespot.spotify.mercury.SubListener;
import org.librespot.spotify.proto.Spirc;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Gianlu
 */
public class SpotifyIrc {
    private static final Logger LOGGER = Logger.getLogger(SpotifyIrc.class);
    private final MercuryClient mercury;
    private final AtomicInteger seqHolder = new AtomicInteger(1);
    private final String uri;
    private Spirc.DeviceState deviceState;

    public SpotifyIrc(@NotNull MercuryClient mercury) throws IOException, InterruptedException, IrcException, MercuryClient.PubSubException {
        this.mercury = mercury;
        this.uri = String.format("hm://remote/3/user/%s/", mercury.username());

        initializeState();

        mercury.subscribe(uri, new SpircListener() {
            @Override
            public void frame(Spirc.@NotNull Frame frame) {
                System.out.println("FRAME: " + frame.getTyp());
            }
        });

        send(Spirc.MessageType.kMessageTypeHello);
    }

    private void initializeState() {
        deviceState = Spirc.DeviceState.newBuilder()
                .setCanPlay(true)
                .setIsActive(false)
                .setVolume(0)
                .setSwVersion(Version.versionString())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kCanBePlayer)
                        .addIntValue(1)
                        .build())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kDeviceType)
                        .addIntValue(mercury.deviceType().val)
                        .build())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kGaiaEqConnectId)
                        .addIntValue(1)
                        .build())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kSupportsLogout)
                        .addIntValue(0)
                        .build())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kIsObservable)
                        .addIntValue(1)
                        .build())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kVolumeSteps)
                        .addIntValue(64)
                        .build())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kSupportedContexts)
                        .addStringValue("album")
                        .addStringValue("playlist")
                        .addStringValue("search")
                        .addStringValue("inbox")
                        .addStringValue("toplist")
                        .addStringValue("starred")
                        .addStringValue("publishedstarred")
                        .addStringValue("track")
                        .build())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kSupportedTypes)
                        .addStringValue("audio/local")
                        .addStringValue("audio/track")
                        .addStringValue("local")
                        .addStringValue("track")
                        .build())
                .build();
    }

    public synchronized void send(@NotNull Spirc.MessageType type) throws IOException, InterruptedException, IrcException {
        LOGGER.trace("Send frame, type: " + type);

        Spirc.Frame frame = Spirc.Frame.newBuilder()
                .setVersion(1)
                .setTyp(type)
                .setSeqNr(seqHolder.getAndIncrement())
                .setIdent(mercury.deviceId())
                .setProtocolVersion("2.0.0")
                .setDeviceState(deviceState)
                .setStateUpdateId(System.currentTimeMillis())
                .build();

        MercuryClient.Response response = mercury.sendSync(uri, MercuryClient.Method.SEND, new byte[][]{frame.toByteArray()});
        if (response.statusCode == 200) {
            LOGGER.trace("Frame sent successfully, type: " + type);
        } else {
            throw new IrcException(response);
        }
    }

    public static class IrcException extends Exception {
        private IrcException(MercuryClient.Response response) {
            super(String.format("status: %d", response.statusCode));
        }
    }

    private abstract static class SpircListener implements SubListener {

        public abstract void frame(@NotNull Spirc.Frame frame);

        @Override
        public final void event(MercuryClient.@NotNull Response resp) {
            try {
                frame(Spirc.Frame.parseFrom(resp.payload[0]));
            } catch (InvalidProtocolBufferException ex) {
                LOGGER.fatal("Couldn't create Frame!", ex);
            }
        }
    }
}
