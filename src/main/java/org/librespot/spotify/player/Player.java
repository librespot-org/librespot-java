package org.librespot.spotify.player;

import javazoom.spi.vorbis.sampled.file.VorbisAudioFileReader;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.mercury.MercuryClient;
import org.librespot.spotify.mercury.MercuryRequests;
import org.librespot.spotify.mercury.model.TrackId;
import org.librespot.spotify.proto.Metadata;
import org.librespot.spotify.proto.Spirc;
import org.librespot.spotify.spirc.FrameListener;
import org.librespot.spotify.spirc.SpotifyIrc;

import javax.sound.sampled.*;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author Gianlu
 */
public class Player implements FrameListener {
    private static final Logger LOGGER = Logger.getLogger(Player.class);
    private final Session session;
    private final SpotifyIrc spirc;
    private final Spirc.State.Builder state;
    private final Mixer mixer;
    private AudioFileStreaming currentFile;

    public Player(@NotNull Session session) {
        this.session = session;
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

    private void loadTrack(boolean play) {
        Spirc.TrackRef ref = state.getTrack(state.getPlayingTrackIndex());

        try {
            Metadata.Track track = session.mercury().requestSync(MercuryRequests.getTrack(new TrackId(ref)));
            System.out.println("TRACK: " + track.getName());

            Metadata.AudioFile file = track.getFile(1);
            byte[] key = session.audioKey().getAudioKey(track, file);
            System.out.println("FILE: " + file.getFormat());

            currentFile = new AudioFileStreaming(session, file, key);
            currentFile.open();

            InputStream in = currentFile.stream();

            NormalizationData normalizationData = NormalizationData.read(in);
            System.out.println("NORM: " + normalizationData.getFactor());

            if (in.skip(0xa7) != 0xa7) throw new IOException();

            new Thread(() -> {
                try {
                    in.mark(0);

                    FileOutputStream out = new FileOutputStream("C:\\Users\\Gianlu\\Desktop\\test.ogg");

                    byte[] buffer = new byte[5634];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        out.flush();
                    }

                    System.out.println("ENDED FILE");
                    out.close();

                    in.reset();

                    AudioInputStream audioIn = new VorbisAudioFileReader().getAudioInputStream(in);
                    Clip clip = AudioSystem.getClip();
                    clip.open(audioIn);
                    clip.start();
                } catch (LineUnavailableException | UnsupportedAudioFileException | IOException e) {
                    e.printStackTrace();
                }
            }).start();
        } catch (IOException | MercuryClient.MercuryException ex) {
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
}
