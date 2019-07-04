package xyz.gianlu.librespot.player;

import com.spotify.connectstate.model.Connect;
import com.spotify.connectstate.model.Player.*;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spotify.player.proto.ContextTrackOuterClass.ContextTrack;
import spotify.player.proto.transfer.PlaybackOuterClass.Playback;
import spotify.player.proto.transfer.SessionOuterClass;
import spotify.player.proto.transfer.TransferStateOuterClass;
import xyz.gianlu.librespot.common.ProtoUtils;
import xyz.gianlu.librespot.connectstate.DeviceStateHandler;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TimeProvider;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.contexts.AbsSpotifyContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static spotify.player.proto.ContextOuterClass.Context;

/**
 * @author Gianlu
 */
public class StateWrapper implements DeviceStateHandler.Listener {
    private static final Logger LOGGER = Logger.getLogger(StateWrapper.class);
    private final PlayerState.Builder state;
    private final Session session;
    private final DeviceStateHandler device;
    private AbsSpotifyContext<?> context;
    private PagesLoader pages;
    private TracksKeeper tracksKeeper;

    StateWrapper(@NotNull Session session) {
        this.session = session;
        this.device = new DeviceStateHandler(session);
        this.state = initState();

        device.addListener(this);
    }

    @NotNull
    private static PlayerState.Builder initState() {
        return PlayerState.newBuilder()
                .setPlaybackSpeed(1.0)
                .setSuppressions(Suppressions.newBuilder().build())
                .setContextRestrictions(Restrictions.newBuilder().build())
                .setOptions(ContextPlayerOptions.newBuilder()
                        .setRepeatingContext(false)
                        .setShufflingContext(false)
                        .setRepeatingTrack(false))
                .setPositionAsOfTimestamp(0)
                .setIsPlaying(false);
    }

    void setState(boolean playing, boolean paused, boolean buffering) {
        state.setIsPlaying(playing).setIsPaused(paused).setIsBuffering(buffering);
    }

    boolean isPaused() {
        return state.getIsPaused();
    }

    boolean isActuallyPlaying() {
        return state.getIsPlaying() && !state.getIsPaused() && !state.getIsBuffering();
    }

    boolean isLoading() {
        return state.getIsBuffering();
    }

    boolean isShuffling() {
        return state.getOptions().getShufflingContext();
    }

    boolean isRepeatingContext() {
        return state.getOptions().getRepeatingContext();
    }

    boolean isRepeatingTrack() {
        return state.getOptions().getRepeatingTrack();
    }

    @Nullable
    String getContextUri() {
        return state.getContextUri();
    }

    private void setContext(@NotNull Context ctx) throws AbsSpotifyContext.UnsupportedContextException {
        String uri = ctx.getUri();
        this.context = AbsSpotifyContext.from(uri);
        this.state.setContextUri(uri);

        if (ctx.hasUrl()) this.state.setContextUrl(ctx.getUrl());
        else this.state.clearContextUrl();

        if (ctx.hasRestrictions()) {
            this.context.updateRestrictions(ctx.getRestrictions());
            // TODO: this.state.setRestrictions(ctx.getRestrictions());
        } else {
            this.state.clearRestrictions();
            this.state.clearContextRestrictions();
        }

        ProtoUtils.moveOverMetadata(ctx, state, "context_description", "track_count", "context_owner", "image_url");

        this.pages = PagesLoader.from(session, ctx);
        this.tracksKeeper = new TracksKeeper();
    }

    synchronized void updated() {
        updatePosition();
        device.updateState(Connect.PutStateReason.PLAYER_STATE_CHANGED, state.build());
    }

    void addListener(@NotNull DeviceStateHandler.Listener listener) {
        device.addListener(listener);
    }

    @Override
    public void ready() {
        device.updateState(Connect.PutStateReason.NEW_DEVICE, state.build());
        LOGGER.info("Notified new device (us)!");
    }

    @Override
    public void command(@NotNull String endpoint, @NotNull byte[] data) {
    }

    @Override
    public void volumeChanged() {
        device.updateState(Connect.PutStateReason.VOLUME_CHANGED, state.build());
    }

    int getVolume() {
        return device.getVolume();
    }

    int getPosition() {
        int diff = (int) (TimeProvider.currentTimeMillis() - state.getTimestamp());
        return (int) (state.getPosition() + diff);
    }

    void setPosition(long pos) {
        state.setTimestamp(TimeProvider.currentTimeMillis());
        state.setPositionAsOfTimestamp(pos);
    }

    private void updatePosition() {
        setPosition(getPosition());
    }

    void transfer(@NotNull TransferStateOuterClass.TransferState cmd) throws AbsSpotifyContext.UnsupportedContextException, IOException {
        SessionOuterClass.Session ps = cmd.getCurrentSession();

        state.setPlayOrigin(ProtoUtils.convertPlayOrigin(ps.getPlayOrigin()));

        Context ctx = ps.getContext();
        setContext(ctx);

        state.setOptions(ProtoUtils.convertPlayerOptions(cmd.getOptions()));

        Playback pb = cmd.getPlayback();
        tracksKeeper.initialize(ps.getCurrentUid(), pb.getCurrentTrack());

        state.setPositionAsOfTimestamp(pb.getPositionAsOfTimestamp());
        state.setTimestamp(pb.getTimestamp());
        updatePosition();

        device.setIsActive(true);
    }

    @Nullable
    PlayableId nextPlayableDoNotSet() {
        return null; // TODO
    }

    @NotNull
    PlayableId getCurrentPlayable() {
        return PlayableId.from(tracksKeeper.getCurrentTrack());
    }

    @NotNull
    NextPlayable nextPlayable(@NotNull Player.Configuration conf) {
        return NextPlayable.MISSING_TRACKS; // TODO
    }

    @NotNull
    PreviousPlayable previousPlayable() {
        return PreviousPlayable.MISSING_TRACKS; // TODO
    }

    public enum PreviousPlayable {
        MISSING_TRACKS, OK;

        public boolean isOk() {
            return this == OK;
        }
    }

    public enum NextPlayable {
        MISSING_TRACKS, AUTOPLAY,
        OK_PLAY, OK_PAUSE;

        public boolean isOk() {
            return this == OK_PLAY || this == OK_PAUSE;
        }
    }

    private class TracksKeeper {
        private final List<ContextTrack> tracks = new ArrayList<>();

        private TracksKeeper() {
        }

        @NotNull
        private ProvidedTrack getCurrentTrack() {
            return state.getTrack();
        }

        private int getCurrentTrackIndex() {
            return state.getIndex().getTrack();
        }

        void initialize(@NotNull String currentUid, @NotNull ContextTrack track) throws IOException {
            tracks.clear();

            boolean found = false;
            while (!found) {
                List<ContextTrack> newTracks = pages.currentPage();
                int index = ProtoUtils.indexOfTrackByUid(newTracks, currentUid);
                if (index == -1) {
                    tracks.addAll(newTracks);
                    if (!pages.nextPage()) throw new IllegalStateException("Couldn't find current track!");
                    continue;
                }

                index += tracks.size();
                tracks.addAll(newTracks);

                state.setIndex(ContextIndex.newBuilder().setTrack(index).build());
                found = true;
            }

            int index = getCurrentTrackIndex();
            ContextTrack.Builder current = tracks.get(index).toBuilder();
            ProtoUtils.enrichTrack(current, track);
            tracks.set(index, current.build());

            state.setTrack(ProtoUtils.convertToProvidedTrack(current.build()));

            state.clearPrevTracks();
            for (int i = 0; i < index; i++)
                state.addPrevTracks(ProtoUtils.convertToProvidedTrack(tracks.get(index)));

            state.clearNextTracks();
            for (int i = index + 1; i < tracks.size(); i++)
                state.addNextTracks(ProtoUtils.convertToProvidedTrack(tracks.get(index)));
        }
    }
}