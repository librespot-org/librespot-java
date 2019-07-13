package xyz.gianlu.librespot.player;

import com.google.gson.JsonObject;
import com.spotify.connectstate.model.Connect;
import com.spotify.connectstate.model.Player.*;
import com.spotify.metadata.proto.Metadata;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spotify.player.proto.ContextPageOuterClass.ContextPage;
import spotify.player.proto.ContextTrackOuterClass.ContextTrack;
import spotify.player.proto.transfer.PlaybackOuterClass.Playback;
import spotify.player.proto.transfer.QueueOuterClass;
import spotify.player.proto.transfer.SessionOuterClass;
import spotify.player.proto.transfer.TransferStateOuterClass;
import xyz.gianlu.librespot.common.FisherYatesShuffle;
import xyz.gianlu.librespot.common.ProtoUtils;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.config.PlayerConf;
import xyz.gianlu.librespot.connectstate.DeviceStateHandler;
import xyz.gianlu.librespot.connectstate.DeviceStateHandler.PlayCommandWrapper;
import xyz.gianlu.librespot.connectstate.RestrictionsManager;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TimeProvider;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.model.*;
import xyz.gianlu.librespot.player.contexts.AbsSpotifyContext;

import java.io.IOException;
import java.util.*;

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

    boolean isPlaying() {
        return state.getIsPlaying();
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

    boolean isShufflingContext() {
        return state.getOptions().getShufflingContext();
    }

    void setShufflingContext(boolean value) {
        if (context == null) return;

        boolean old = isShufflingContext();
        state.getOptionsBuilder().setShufflingContext(value && context.restrictions.can(RestrictionsManager.Action.SHUFFLE));

        if (old != isShufflingContext()) tracksKeeper.toggleShuffle(isShufflingContext());
    }

    boolean isRepeatingContext() {
        return state.getOptions().getRepeatingContext();
    }

    void setRepeatingContext(boolean value) {
        if (context == null) return;

        state.getOptionsBuilder().setRepeatingContext(value && context.restrictions.can(RestrictionsManager.Action.REPEAT_CONTEXT));
    }

    boolean isRepeatingTrack() {
        return state.getOptions().getRepeatingTrack();
    }

    void setRepeatingTrack(boolean value) {
        if (context == null) return;

        state.getOptionsBuilder().setRepeatingTrack(value && context.restrictions.can(RestrictionsManager.Action.REPEAT_TRACK));
    }

    @Nullable
    String getContextUri() {
        return state.getContextUri();
    }

    private void setContext(@NotNull String uri) throws AbsSpotifyContext.UnsupportedContextException {
        this.context = AbsSpotifyContext.from(uri);
        this.state.setContextUri(uri);

        if (!context.isFinite()) {
            setRepeatingContext(false);
            setShufflingContext(false);
        }

        this.state.clearContextUrl();
        this.state.clearRestrictions();
        this.state.clearContextRestrictions();
        this.state.clearContextMetadata();

        this.pages = PagesLoader.from(session, uri);
        this.tracksKeeper = new TracksKeeper();

        this.device.setIsActive(true);
    }

    private void setContext(@NotNull Context ctx) throws AbsSpotifyContext.UnsupportedContextException {
        String uri = ctx.getUri();
        this.context = AbsSpotifyContext.from(uri);
        this.state.setContextUri(uri);

        if (!context.isFinite()) {
            setRepeatingContext(false);
            setShufflingContext(false);
        }

        if (ctx.hasUrl()) this.state.setContextUrl(ctx.getUrl());
        else this.state.clearContextUrl();

        state.clearContextMetadata();
        ProtoUtils.moveOverMetadata(ctx, state, "context_description", "track_count", "context_owner", "image_url");

        this.pages = PagesLoader.from(session, ctx);
        this.tracksKeeper = new TracksKeeper();

        this.device.setIsActive(true);
    }

    private void updateRestrictions() {
        if (isPaused())
            context.restrictions.disallow(RestrictionsManager.Action.PAUSE, "not_playing");
        else
            context.restrictions.allow(RestrictionsManager.Action.PAUSE);

        if (isPlaying())
            context.restrictions.disallow(RestrictionsManager.Action.RESUME, "not_paused");
        else
            context.restrictions.allow(RestrictionsManager.Action.RESUME);

        if (tracksKeeper.isPlayingFirst() && !isRepeatingContext())
            context.restrictions.disallow(RestrictionsManager.Action.SKIP_PREV, "no_prev_track");
        else
            context.restrictions.allow(RestrictionsManager.Action.SKIP_PREV);

        if (tracksKeeper.isPlayingLast() && !isRepeatingContext())
            context.restrictions.disallow(RestrictionsManager.Action.SKIP_NEXT, "no_next_track");
        else
            context.restrictions.allow(RestrictionsManager.Action.SKIP_NEXT);

        state.setRestrictions(context.restrictions.toProto());
    }

    synchronized void updated() {
        updatePosition();
        updateRestrictions();

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
    public void command(@NotNull DeviceStateHandler.Endpoint endpoint, @NotNull DeviceStateHandler.CommandBody data) {
    }

    @Override
    public void volumeChanged() {
        device.updateState(Connect.PutStateReason.VOLUME_CHANGED, state.build());
    }

    synchronized int getVolume() {
        return device.getVolume();
    }

    synchronized void enrichWithMetadata(@NotNull Metadata.Track track) {
        if (track.hasDuration()) state.setDuration(track.getDuration());

        ProvidedTrack.Builder builder = state.getTrackBuilder();
        if (track.hasPopularity()) builder.putMetadata("popularity", String.valueOf(track.getPopularity()));
        if (track.hasExplicit()) builder.putMetadata("is_explicit", String.valueOf(track.getExplicit()));
        if (track.hasHasLyrics()) builder.putMetadata("has_lyrics", String.valueOf(track.getHasLyrics()));
        if (track.hasName()) builder.putMetadata("title", String.valueOf(track.getName()));
        if (track.hasDiscNumber()) builder.putMetadata("album_disc_number", String.valueOf(track.getDiscNumber()));

        for (int i = 0; i < track.getArtistCount(); i++) {
            Metadata.Artist artist = track.getArtist(i);
            if (artist.hasName()) builder.putMetadata("artist_name" + (i == 0 ? "" : (":" + i)), artist.getName());
            if (artist.hasGid()) builder.putMetadata("artist_uri" + (i == 0 ? "" : (":" + i)),
                    ArtistId.fromHex(Utils.bytesToHex(artist.getGid())).toSpotifyUri());
        }

        if (track.hasAlbum()) {
            Metadata.Album album = track.getAlbum();
            if (album.getDiscCount() > 0) {
                builder.putMetadata("album_track_count", String.valueOf(ProtoUtils.getTrackCount(album)));
                builder.putMetadata("album_disc_count", String.valueOf(album.getDiscCount()));
            }
            if (album.hasName()) builder.putMetadata("album_title", album.getName());
            if (album.hasGid()) builder.putMetadata("album_uri",
                    AlbumId.fromHex(Utils.bytesToHex(album.getGid())).toSpotifyUri());

            for (int i = 0; i < album.getArtistCount(); i++) {
                Metadata.Artist artist = album.getArtist(i);
                if (artist.hasName())
                    builder.putMetadata("album_artist_name" + (i == 0 ? "" : (":" + i)), artist.getName());
                if (artist.hasGid()) builder.putMetadata("album_artist_uri" + (i == 0 ? "" : (":" + i)),
                        ArtistId.fromHex(Utils.bytesToHex(artist.getGid())).toSpotifyUri());
            }

            if (track.hasDiscNumber() && album.getDiscCount() < track.getDiscNumber() - 1) {
                Metadata.Disc disc = album.getDisc(track.getDiscNumber() - 1);
                for (int i = 0; i < disc.getTrackCount(); i++) {
                    if (disc.getTrack(i).getGid() == track.getGid()) {
                        builder.putMetadata("album_track_number", String.valueOf(i + 1));
                        break;
                    }
                }
            }

            if (album.hasCoverGroup()) ImageId.putAsMetadata(builder, album.getCoverGroup());
        }

        ProtoUtils.putFilesAsMetadata(builder, track.getFileList());
        state.setTrack(builder.build());
    }

    synchronized void enrichWithMetadata(@NotNull Metadata.Episode episode) {
        if (episode.hasDuration()) state.setDuration(episode.getDuration());

        ProvidedTrack.Builder builder = state.getTrackBuilder();
        if (episode.hasExplicit()) builder.putMetadata("is_explicit", String.valueOf(episode.getExplicit()));
        if (episode.hasName()) builder.putMetadata("title", String.valueOf(episode.getName()));

        if (episode.hasShow()) {
            Metadata.Show show = episode.getShow();
            if (show.hasName()) builder.putMetadata("album_title", show.getName());

            if (show.hasCoverImage()) ImageId.putAsMetadata(builder, show.getCoverImage());
        }

        if (episode.getAudioCount() > 0 && episode.getVideoCount() == 0) {
            builder.putMetadata("media.type", "audio");
        } else if (episode.getVideoCount() > 0) {
            builder.putMetadata("media.type", "video");
        }

        ProtoUtils.putFilesAsMetadata(builder, episode.getAudioList());
        state.setTrack(builder.build());
    }

    synchronized int getPosition() {
        int diff = (int) (TimeProvider.currentTimeMillis() - state.getTimestamp());
        return (int) (state.getPositionAsOfTimestamp() + diff);
    }

    synchronized void setPosition(long pos) {
        int sub = (int) Math.min(pos, 1000);
        long now = TimeProvider.currentTimeMillis();
        now -= sub;
        pos -= sub;

        state.setTimestamp(now);
        state.setPositionAsOfTimestamp(pos);
    }

    private void updatePosition() {
        setPosition(getPosition());
    }

    void loadContextWithTracks(@NotNull String uri, @NotNull List<ContextTrack> tracks) throws MercuryClient.MercuryException, IOException, AbsSpotifyContext.UnsupportedContextException {
        state.clearPlayOrigin();
        state.clearOptions();

        setContext(uri);
        pages.putFirstPage(tracks);
        tracksKeeper.initializeStart();
        setPosition(0);
    }

    void loadContext(@NotNull String uri) throws MercuryClient.MercuryException, IOException, AbsSpotifyContext.UnsupportedContextException {
        state.clearPlayOrigin();
        state.clearOptions();

        setContext(uri);
        tracksKeeper.initializeStart();
        setPosition(0);
    }

    void transfer(@NotNull TransferStateOuterClass.TransferState cmd) throws AbsSpotifyContext.UnsupportedContextException, IOException, MercuryClient.MercuryException {
        SessionOuterClass.Session ps = cmd.getCurrentSession();

        state.setPlayOrigin(ProtoUtils.convertPlayOrigin(ps.getPlayOrigin()));
        state.setOptions(ProtoUtils.convertPlayerOptions(cmd.getOptions()));
        setContext(ps.getContext());

        Playback pb = cmd.getPlayback();
        tracksKeeper.initializeFrom(tracks -> ProtoUtils.indexOfTrackByUid(tracks, ps.getCurrentUid()), pb.getCurrentTrack(), cmd.getQueue(), false);


        state.setPositionAsOfTimestamp(pb.getPositionAsOfTimestamp());
        state.setTimestamp(pb.getTimestamp());
    }

    void load(@NotNull JsonObject obj) throws AbsSpotifyContext.UnsupportedContextException, IOException, MercuryClient.MercuryException {
        state.setPlayOrigin(ProtoUtils.jsonToPlayOrigin(PlayCommandWrapper.getPlayOrigin(obj)));
        state.setOptions(ProtoUtils.jsonToPlayerOptions((PlayCommandWrapper.getPlayerOptionsOverride(obj))));
        setContext(ProtoUtils.jsonToContext(PlayCommandWrapper.getContext(obj)));

        String trackUid = PlayCommandWrapper.getSkipToUid(obj);
        String trackUri = PlayCommandWrapper.getSkipToUri(obj);
        Integer trackIndex = PlayCommandWrapper.getSkipToIndex(obj);

        if (trackUri != null) {
            tracksKeeper.initializeFrom(tracks -> ProtoUtils.indexOfTrackByUri(tracks, trackUri), null, null, true);
        } else if (trackUid != null) {
            tracksKeeper.initializeFrom(tracks -> ProtoUtils.indexOfTrackByUid(tracks, trackUid), null, null, true);
        } else if (trackIndex != null) {
            tracksKeeper.initializeFrom(tracks -> {
                if (trackIndex < tracks.size()) return trackIndex;
                else return -1;
            }, null, null, true);
        } else {
            tracksKeeper.initializeStart();
        }

        Integer seekTo = PlayCommandWrapper.getSeekTo(obj);
        if (seekTo != null) setPosition(seekTo);
        else setPosition(0);
    }

    synchronized void updateContext(@NotNull JsonObject obj) {
        String uri = PlayCommandWrapper.getContextUri(obj);
        if (!context.uri().equals(uri)) {
            LOGGER.warn(String.format("Received update of the wrong context! {context: %s, newUri: %s}", context, uri));
            return;
        }

        if (isShufflingContext()) LOGGER.warn("Updating shuffled context, that's bad!");

        try {
            tracksKeeper.updateContext(ProtoUtils.jsonToContextPages(PlayCommandWrapper.getPages(obj)));
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.error("Failed updating context!", ex);
        }
    }

    void skipTo(@NotNull ContextTrack track) {
        tracksKeeper.skipTo(track);
        setPosition(0);
    }

    @Nullable
    PlayableId nextPlayableDoNotSet() {
        try {
            return tracksKeeper.nextPlayableDoNotSet();
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.error("Failed fetching next playable.", ex);
            return null;
        }
    }

    @NotNull
    PlayableId getCurrentPlayable() {
        return PlayableId.from(tracksKeeper.getCurrentTrack());
    }

    @NotNull
    NextPlayable nextPlayable(@NotNull PlayerConf conf) {
        if (tracksKeeper == null) return NextPlayable.MISSING_TRACKS;

        try {
            return tracksKeeper.nextPlayable(conf);
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.error("Failed fetching next playable.", ex);
            return NextPlayable.MISSING_TRACKS;
        }
    }

    @NotNull
    PreviousPlayable previousPlayable() {
        if (tracksKeeper == null) return PreviousPlayable.MISSING_TRACKS;
        return tracksKeeper.previousPlayable();
    }

    void removeListener(@NotNull DeviceStateHandler.Listener listener) {
        device.removeListener(listener);
    }

    synchronized void addToQueue(@NotNull ContextTrack track) {
        tracksKeeper.addToQueue(track);
    }

    synchronized void setQueue(@Nullable List<ContextTrack> prevTracks, @Nullable List<ContextTrack> nextTracks) {
        tracksKeeper.setQueue(prevTracks, nextTracks);
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

    private interface TrackFinder {
        int find(@NotNull List<ContextTrack> tracks);
    }

    private class TracksKeeper {
        private final LinkedList<ContextTrack> queue = new LinkedList<>();
        private final List<ContextTrack> tracks = new ArrayList<>();
        private final FisherYatesShuffle<ContextTrack> shuffle = new FisherYatesShuffle<>(session.random());
        private volatile boolean isPlayingQueue = false;
        private volatile boolean cannotLoadMore = false;
        private volatile int shuffleKeepIndex = -1;

        private TracksKeeper() {
            checkComplete();
        }

        private void updateTrackCount() {
            if (context.isFinite())
                state.putContextMetadata("track_count", String.valueOf(tracks.size() + queue.size()));
            else state.removeContextMetadata("track_count");
        }

        private void checkComplete() {
            if (cannotLoadMore) return;

            if (context.isFinite()) {
                int total_tracks = Integer.parseInt(state.getContextMetadataOrDefault("track_count", "-1"));
                if (total_tracks == -1) cannotLoadMore = false;
                else cannotLoadMore = total_tracks == tracks.size();
            } else {
                cannotLoadMore = false;
            }
        }

        @NotNull
        synchronized ProvidedTrack getCurrentTrack() {
            return state.getTrack();
        }

        private int getCurrentTrackIndex() {
            return state.getIndex().getTrack();
        }

        private void setCurrentTrackIndex(int index) {
            if (isPlayingQueue) throw new IllegalStateException();

            state.setIndex(ContextIndex.newBuilder().setTrack(index).build());
            updateState();
        }

        private void updatePrevNextTracks() {
            int index = getCurrentTrackIndex();

            state.clearPrevTracks();
            for (int i = 0; i < index; i++)
                state.addPrevTracks(ProtoUtils.convertToProvidedTrack(tracks.get(i)));

            state.clearNextTracks();
            for (ContextTrack track : queue)
                state.addNextTracks(ProtoUtils.convertToProvidedTrack(track));

            for (int i = index + 1; i < tracks.size(); i++)
                state.addNextTracks(ProtoUtils.convertToProvidedTrack(tracks.get(i)));
        }

        private void updateTrackDuration() {
            ProvidedTrack current = getCurrentTrack();
            if (current.containsMetadata("duration"))
                state.setDuration(Long.parseLong(current.getMetadataOrThrow("duration")));
        }

        private void updateState() {
            if (isPlayingQueue)
                state.setTrack(ProtoUtils.convertToProvidedTrack(queue.remove()));
            else
                state.setTrack(ProtoUtils.convertToProvidedTrack(tracks.get(getCurrentTrackIndex())));

            updateTrackDuration();
            updatePrevNextTracks();
        }

        synchronized void addToQueue(@NotNull ContextTrack track) {
            queue.add(track);
            updatePrevNextTracks();
            updateTrackCount();
        }

        synchronized void setQueue(@Nullable List<ContextTrack> prevTracks, @Nullable List<ContextTrack> nextTracks) {
            ContextTrack current = tracks.get(getCurrentTrackIndex());

            queue.clear();
            tracks.clear();

            if (prevTracks != null) tracks.addAll(prevTracks);
            tracks.add(current);

            if (nextTracks != null) {
                for (ContextTrack track : nextTracks) {
                    if (ProtoUtils.isQueued(track)) queue.add(track);
                    else tracks.add(track);
                }
            }

            updateTrackCount();
            updatePrevNextTracks();
        }

        synchronized void updateContext(@NotNull List<ContextPage> updatedPages) throws IOException, MercuryClient.MercuryException {
            String current = getCurrentPlayable().toSpotifyUri();

            tracks.clear();
            pages = PagesLoader.from(session, context.uri());
            pages.putFirstPages(updatedPages);

            while (true) {
                if (pages.nextPage()) {
                    List<ContextTrack> newTracks = pages.currentPage();
                    int index = ProtoUtils.indexOfTrackByUri(newTracks, current);
                    if (index == -1) {
                        tracks.addAll(newTracks);
                        continue;
                    }

                    index += tracks.size();
                    tracks.addAll(newTracks);

                    setCurrentTrackIndex(index);
                    break;
                } else {
                    cannotLoadMore = true;
                    updateTrackCount();
                    throw new IllegalStateException("Couldn't find current track!");
                }
            }

            checkComplete();
        }

        synchronized void initializeStart() throws IOException, MercuryClient.MercuryException, AbsSpotifyContext.UnsupportedContextException {
            if (!pages.nextPage()) throw new IllegalStateException();

            tracks.clear();
            tracks.addAll(pages.currentPage());

            checkComplete();
            if (!PlayableId.hasAtLeastOneSupportedId(tracks))
                throw AbsSpotifyContext.UnsupportedContextException.noSupported();

            if (context.isFinite() && isShufflingContext())
                shuffleEntirely();

            setCurrentTrackIndex(0);
        }

        synchronized void initializeFrom(@NotNull TrackFinder finder, @Nullable ContextTrack track, @Nullable QueueOuterClass.Queue contextQueue, boolean shouldShuffle) throws IOException, MercuryClient.MercuryException, AbsSpotifyContext.UnsupportedContextException {
            tracks.clear();
            queue.clear();

            while (true) {
                if (pages.nextPage()) {
                    List<ContextTrack> newTracks = pages.currentPage();
                    int index = finder.find(newTracks);
                    if (index == -1) {
                        tracks.addAll(newTracks);
                        continue;
                    }

                    index += tracks.size();
                    tracks.addAll(newTracks);

                    if (context.isFinite() && shouldShuffle && isShufflingContext())
                        shuffleEntirely();

                    setCurrentTrackIndex(index);
                    break;
                } else {
                    cannotLoadMore = true;
                    updateTrackCount();
                    throw new IllegalStateException("Couldn't find current track!");
                }
            }

            if (contextQueue != null) {
                queue.addAll(contextQueue.getTracksList());
                isPlayingQueue = contextQueue.getIsPlayingQueue();
                updateState();
            }

            checkComplete();
            if (!PlayableId.hasAtLeastOneSupportedId(tracks))
                throw AbsSpotifyContext.UnsupportedContextException.noSupported();

            if (track != null) enrichCurrentTrack(track);
        }

        private void enrichCurrentTrack(@NotNull ContextTrack track) {
            if (isPlayingQueue) {
                ProvidedTrack.Builder builder = state.getTrackBuilder();
                ProtoUtils.enrichTrack(builder, track);
            } else {
                int index = getCurrentTrackIndex();
                ContextTrack.Builder current = tracks.get(index).toBuilder();
                ProtoUtils.enrichTrack(current, track);
                tracks.set(index, current.build());
                state.setTrack(ProtoUtils.convertToProvidedTrack(current.build()));
            }
        }

        synchronized void skipTo(@NotNull String uri) {
            if (!queue.isEmpty()) {
                List<ContextTrack> queueCopy = new ArrayList<>(queue);

                Iterator<ContextTrack> iterator = queue.iterator();
                while (iterator.hasNext()) {
                    if (Objects.equals(iterator.next().getUri(), uri)) {
                        isPlayingQueue = true;
                        updateState();
                        return;
                    } else {
                        iterator.remove();
                    }
                }

                queue.clear();
                queue.addAll(queueCopy);
            }

            int index = ProtoUtils.indexOfTrackByUri(tracks, uri);
            if (index == -1) throw new IllegalStateException();

            setCurrentTrackIndex(index);
        }

        synchronized void skipTo(@NotNull ContextTrack track) {
            skipTo(track.getUri());
            enrichCurrentTrack(track);
        }

        /**
         * Figures out what the next {@link PlayableId} should be. This is called directly by the preload function and therefore can return {@code null} as it doesn't account for repeating contexts.
         * This will also return {@link xyz.gianlu.librespot.mercury.model.UnsupportedId}.
         *
         * @return The next {@link PlayableId} or {@code null}
         */
        @Nullable
        synchronized PlayableId nextPlayableDoNotSet() throws IOException, MercuryClient.MercuryException {
            if (!queue.isEmpty())
                return PlayableId.from(queue.peek());

            int current = getCurrentTrackIndex();
            if (current == tracks.size() - 1) {
                if (isShufflingContext() || cannotLoadMore) return null;

                if (pages.nextPage()) {
                    tracks.addAll(pages.currentPage());
                } else {
                    cannotLoadMore = true;
                    updateTrackCount();
                    return null;
                }
            }

            if (!context.isFinite() && tracks.size() - current <= 5) {
                if (pages.nextPage()) {
                    tracks.addAll(pages.currentPage());
                    LOGGER.trace("Preloaded next page due to infinite context.");
                } else {
                    LOGGER.warn("Couldn't (pre)load next page of context!");
                }
            }

            return PlayableId.from(tracks.get(current + 1));
        }

        @NotNull
        synchronized NextPlayable nextPlayable(@NotNull PlayerConf conf) throws IOException, MercuryClient.MercuryException {
            if (!queue.isEmpty()) {
                isPlayingQueue = true;
                updateState();

                if (getCurrentPlayable() instanceof UnsupportedId)
                    return nextPlayable(conf);

                return NextPlayable.OK_PLAY;
            }

            isPlayingQueue = false;

            boolean play = true;
            PlayableId next = nextPlayableDoNotSet();
            if (next == null) {
                if (!context.isFinite()) return NextPlayable.MISSING_TRACKS;

                if (isRepeatingContext()) {
                    setCurrentTrackIndex(0);
                } else {
                    if (conf.getAutoplayEnabled()) {
                        return NextPlayable.AUTOPLAY;
                    } else {
                        setCurrentTrackIndex(0);
                        play = false;
                    }
                }
            } else {
                setCurrentTrackIndex(getCurrentTrackIndex() + 1);
            }

            if (getCurrentPlayable() instanceof UnsupportedId)
                return nextPlayable(conf);

            if (play) return NextPlayable.OK_PLAY;
            else return NextPlayable.OK_PAUSE;
        }

        @NotNull
        synchronized PreviousPlayable previousPlayable() {
            int index = getCurrentTrackIndex();
            if (isPlayingQueue) {
                index += 1;
                isPlayingQueue = false;
            }

            if (index == 0) {
                if (isRepeatingContext() && context.isFinite())
                    setCurrentTrackIndex(tracks.size() - 1);
            } else {
                setCurrentTrackIndex(index - 1);
            }

            if (getCurrentPlayable() instanceof UnsupportedId)
                return previousPlayable();

            return PreviousPlayable.OK;
        }

        synchronized boolean isPlayingFirst() {
            return getCurrentTrackIndex() == 0;
        }

        synchronized boolean isPlayingLast() {
            if (cannotLoadMore && queue.isEmpty()) return getCurrentTrackIndex() == tracks.size();
            else return false;
        }

        /**
         * Tries to load all the tracks of this context, must be called on a non-shuffled and finite context!
         *
         * @return Whether the operation was successful.
         */
        private boolean loadAllTracks() {
            if (!context.isFinite()) throw new IllegalStateException();

            try {
                while (true) {
                    if (pages.nextPage()) tracks.addAll(pages.currentPage());
                    else break;
                }
            } catch (IOException | MercuryClient.MercuryException ex) {
                LOGGER.error("Failed loading all tracks!", ex);
                return false;
            }

            cannotLoadMore = true;
            updateTrackCount();

            return true;
        }

        /**
         * Shuffles the entire track list without caring about the current state, must be called before {@link #setCurrentTrackIndex(int)}!
         */
        synchronized void shuffleEntirely() {
            if (!context.isFinite()) throw new IllegalStateException("Cannot shuffle infinite context!");
            if (tracks.size() <= 1) return;
            if (isPlayingQueue) return;

            if (!cannotLoadMore) {
                if (loadAllTracks()) {
                    LOGGER.trace("Loaded all tracks before shuffling (entirely).");
                } else {
                    LOGGER.error("Cannot shuffle entire context!");
                    return;
                }
            }

            shuffle.shuffle(tracks, true);
            LOGGER.trace("Shuffled context entirely!");
        }

        synchronized void toggleShuffle(boolean value) {
            if (!context.isFinite()) throw new IllegalStateException("Cannot shuffle infinite context!");
            if (tracks.size() <= 1) return;
            if (isPlayingQueue) return;

            if (value) {
                if (!cannotLoadMore) {
                    if (loadAllTracks()) {
                        LOGGER.trace("Loaded all tracks before shuffling.");
                    } else {
                        LOGGER.error("Cannot shuffle context!");
                        return;
                    }
                }

                PlayableId currentlyPlaying = getCurrentPlayable();
                shuffle.shuffle(tracks, true);
                shuffleKeepIndex = ProtoUtils.indexOfTrackByUri(tracks, currentlyPlaying.toSpotifyUri());
                Collections.swap(tracks, 0, shuffleKeepIndex);
                setCurrentTrackIndex(0);

                LOGGER.trace(String.format("Shuffled context! {keepIndex: %d}", shuffleKeepIndex));
            } else {
                if (shuffle.canUnshuffle(tracks.size())) {
                    PlayableId currentlyPlaying = getCurrentPlayable();
                    if (shuffleKeepIndex != -1) Collections.swap(tracks, 0, shuffleKeepIndex);

                    shuffle.unshuffle(tracks);
                    setCurrentTrackIndex(ProtoUtils.indexOfTrackByUri(tracks, currentlyPlaying.toSpotifyUri()));

                    LOGGER.trace("Unshuffled using Fisher-Yates.");
                } else {
                    PlayableId id = getCurrentPlayable();

                    tracks.clear();
                    pages = PagesLoader.from(session, context.uri());
                    loadAllTracks();

                    setCurrentTrackIndex(ProtoUtils.indexOfTrackByUri(tracks, id.toSpotifyUri()));
                    LOGGER.trace("Unshuffled by reloading context.");
                }
            }
        }
    }
}