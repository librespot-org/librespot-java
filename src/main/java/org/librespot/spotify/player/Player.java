package org.librespot.spotify.player;

import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.crypto.Packet;
import org.librespot.spotify.mercury.MercuryClient;
import org.librespot.spotify.mercury.MercuryRequests;
import org.librespot.spotify.mercury.model.TrackId;
import org.librespot.spotify.proto.Metadata;
import org.librespot.spotify.proto.Spirc;
import org.librespot.spotify.spirc.FrameListener;
import org.librespot.spotify.spirc.SpotifyIrc;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.io.IOException;
import java.util.Arrays;

/**
 * @author Gianlu
 */
public class Player implements FrameListener {
    private final Session session;
    private final SpotifyIrc spirc;
    private final Spirc.State.Builder state;
    private final Mixer mixer;
    private final AudioKeyManager keyManager;

    public Player(@NotNull Session session) {
        this.session = session;
        this.keyManager = new AudioKeyManager(session);
        this.spirc = session.spirc();
        this.state = initState();
        this.mixer = AudioSystem.getMixer(AudioSystem.getMixerInfo()[0]);

        spirc.addListener(this);
    }

    @NotNull
    private Spirc.State.Builder initState() {
        return Spirc.State.newBuilder()
                .setPositionMeasuredAt(0)
                .setPositionMs(0)
                .setShuffle(false)
                .setRepeat(false)
                .setStatus(Spirc.PlayStatus.kPlayStatusStop);
    }

    @Override
    public void frame(Spirc.@NotNull Frame frame) {
        switch (frame.getTyp()) {
            case kMessageTypeLoad:
                handleLoad(frame);
                break;
        }
    }

    private void loadTrack(boolean play) { // TODO
        Spirc.TrackRef ref = state.getTrack(state.getPlayingTrackIndex());

        try {
            Metadata.Track track = session.mercury().requestSync(MercuryRequests.getTrack(new TrackId(ref)));
            System.out.println("TRACK: " + track.getName());

            byte[] key = keyManager.getAudioKey(track, track.getFile(0));
            System.out.println("KEY: " + Arrays.toString(key));
        } catch (IOException | MercuryClient.MercuryException | AudioKeyManager.KeyErrorException ex) {
            ex.printStackTrace();
        }
    }

    private void handleLoad(@NotNull Spirc.Frame frame) {
        if (!spirc.deviceState().getIsActive()) {
            spirc.deviceState()
                    .setIsActive(true)
                    .setBecameActiveAt(System.currentTimeMillis());
        }

        state.setPlayingTrackIndex(frame.getState().getPlayingTrackIndex());
        state.clearTrack();
        state.addAllTrack(frame.getState().getTrackList());
        state.setContextUri(frame.getState().getContextUri());
        state.setRepeat(frame.getState().getRepeat());
        state.setShuffle(frame.getState().getShuffle());

        if (state.getTrackCount() > 0) {
            state.setPositionMs(frame.getState().getPositionMs());
            state.setPositionMeasuredAt(System.currentTimeMillis());

            loadTrack(frame.getState().getStatus() == Spirc.PlayStatus.kPlayStatusPlay);
        } else {
            state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
        }

        spirc.deviceStateUpdated();
    }

    public void handle(@NotNull Packet packet) {
        if (packet.is(Packet.Type.AesKey) || packet.is(Packet.Type.AesKeyError)) {
            keyManager.handle(packet);
        }
    }
}
