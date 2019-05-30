package xyz.gianlu.librespot.player;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.contexts.AbsSpotifyContext;
import xyz.gianlu.librespot.player.contexts.SearchContext;
import xyz.gianlu.librespot.player.providers.ContentProvider;
import xyz.gianlu.librespot.player.remote.Remote3Frame;
import xyz.gianlu.librespot.player.remote.Remote3Page;
import xyz.gianlu.librespot.player.remote.Remote3Track;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

/**
 * @author Gianlu
 */
public class StateWrapper {
    private static final Logger LOGGER = Logger.getLogger(StateWrapper.class);
    private static final int STATE_TRACKS_BEFORE = 20;
    private static final int STATE_TRACKS_AFTER = 40;
    private static final int STATE_MAX_TRACKS = STATE_TRACKS_AFTER + 1 + STATE_TRACKS_BEFORE;
    private final Spirc.State.Builder state;
    private final Session session;
    private AbsSpotifyContext<?> context;
    private boolean repeatingTrack = false;
    private TracksKeeper tracksKeeper;

    StateWrapper(@NotNull Session session) {
        this.session = session;
        this.state = initState();
    }

    @NotNull
    private static Spirc.State.Builder initState() {
        return Spirc.State.newBuilder()
                .setPositionMeasuredAt(0).setPositionMs(0)
                .setShuffle(false).setRepeat(false)
                .setRow(0).setPlayingFromFallback(true)
                .setStatus(Spirc.PlayStatus.kPlayStatusStop);
    }

    private static int[] getShuffleExchanges(int size, long seed) {
        int[] exchanges = new int[size - 1];
        Random rand = new Random(seed);
        for (int i = size - 1; i > 0; i--) {
            int n = rand.nextInt(i + 1);
            exchanges[size - 1 - i] = n;
        }
        return exchanges;
    }

    boolean isRepeatingTrack() {
        return repeatingTrack;
    }

    void setRepeatingTrack(boolean repeatingTrack) {
        this.repeatingTrack = repeatingTrack && context != null && context.canRepeatTrack();
    }

    @Nullable
    String getContextUri() {
        return state.getContextUri();
    }

    void setStatus(@NotNull Spirc.PlayStatus status) {
        state.setStatus(status);
    }

    boolean isStatus(@NotNull Spirc.PlayStatus status) {
        return status == state.getStatus();
    }

    long getPositionMeasuredAt() {
        return state.getPositionMeasuredAt();
    }

    void setPositionMeasuredAt(long ms) {
        state.setPositionMeasuredAt(ms);
    }

    int getPositionMs() {
        return state.getPositionMs();
    }

    void setPositionMs(int pos) {
        state.setPositionMs(pos);
    }

    void setRepeat(boolean repeat) {
        state.setRepeat(repeat && context != null && context.canRepeatContext());
    }

    void setShuffle(boolean shuffle) {
        state.setShuffle(shuffle && context != null && context.canRepeatContext());
        if (state.getShuffle()) shuffleContent(false);
        else unshuffleContent();
    }

    private void shuffleContent(boolean fully) {
        if (tracksKeeper == null) return;

        if (context != null && context.canShuffle())
            tracksKeeper.shuffle(session.random(), fully);
        else
            LOGGER.warn("Cannot shuffle: " + tracksKeeper);
    }

    private void unshuffleContent() {
        if (tracksKeeper == null) return;

        if (context != null && context.canShuffle())
            tracksKeeper.unshuffle();
        else
            LOGGER.warn("Cannot unshuffle: " + tracksKeeper);
    }

    private void setContext(@NotNull Remote3Frame.Context context) throws AbsSpotifyContext.UnsupportedContextException {
        setContext(context.uri);
        if (context.restrictions != null) this.context.updateRestrictions(context.restrictions);
    }

    private void setContext(@NotNull String context) throws AbsSpotifyContext.UnsupportedContextException {
        this.context = AbsSpotifyContext.from(context);
        this.state.setContextUri(context);
    }

    @NotNull
    private List<Remote3Page> getPages(@NotNull Remote3Frame.Context context) throws IOException, MercuryClient.MercuryException {
        MercuryRequests.ResolvedContextWrapper resolved = session.mercury().sendSync(MercuryRequests.resolveContext(context.uri));
        return resolved.pages();
    }

    @NotNull
    private List<Remote3Track> getTracks(@NotNull String pageUrl) throws IOException {
        MercuryClient.Response resp = session.mercury().sendSync(RawMercuryRequest.newBuilder()
                .setUri(pageUrl).setMethod("GET").build());

        JsonObject obj = new JsonParser().parse(new InputStreamReader(resp.payload.stream())).getAsJsonObject();
        return Remote3Track.array(obj.getAsJsonArray("tracks"));
    }

    private void loadPageTracks(@NotNull List<Remote3Track> tracks, @Nullable TrackSelector selector, int totalTracks) {
        boolean allTracks = totalTracks == tracks.size();
        tracksKeeper = new TracksKeeper(this.context, tracks, allTracks);

        int updated = PlayableId.removeUnsupported(tracks, selector == null ? -1 : selector.trackIndex); // FIXME
        if (selector != null && updated != -1) selector.trackIndex = updated;

        PlayableId current = selector != null ? selector.find(tracks) : null;
        tracksKeeper.setPlaying(current);

        if (state.getShuffle()) shuffleContent(current == null);
    }

    private void loadPage(@NotNull Remote3Page page, @Nullable TrackSelector selector, int totalTracks) throws IOException {
        List<Remote3Track> tracks = page.tracks;
        if (tracks == null) {
            if (page.pageUrl != null) {
                tracks = getTracks(page.pageUrl);
                totalTracks = tracks.size(); // Page URL should return all tracks
            } else {
                throw new IllegalStateException("How do I load this page?!");
            }
        }

        loadPageTracks(tracks, selector, totalTracks);
    }

    void updated() {
        if (tracksKeeper != null) tracksKeeper.dumpToState(state);
        session.spirc().deviceStateUpdated(state);
    }

    void seekTo(@Nullable String uri) {
        if (tracksKeeper == null || uri == null) return;

        tracksKeeper.seekTo(uri);
    }

    void loadStation(@NotNull MercuryRequests.StationsWrapper station) throws AbsSpotifyContext.UnsupportedContextException {
        setContext(station.uri());

        List<Remote3Track> tracks = station.tracks();
        loadPageTracks(tracks, null, tracks.size());
    }

    void loadFromUri(@NotNull String context) throws IOException, MercuryClient.MercuryException, AbsSpotifyContext.UnsupportedContextException {
        setContext(context);

        MercuryRequests.ResolvedContextWrapper resolved = session.mercury().sendSync(MercuryRequests.resolveContext(context));
        Remote3Page page = resolved.pages().get(0);
        loadPage(page, null, page.tracks.size()); // Resolve context should always return all tracks
    }

    void load(@NotNull Remote3Frame frame) throws IOException, MercuryClient.MercuryException, AbsSpotifyContext.UnsupportedContextException {
        if (frame.context == null) throw new IllegalArgumentException("Missing context object!");

        if (frame.options != null && frame.options.playerOptionsOverride != null) {
            Optional.ofNullable(frame.options.playerOptionsOverride.repeatingContext).ifPresent(state::setRepeat);
            Optional.ofNullable(frame.options.playerOptionsOverride.shufflingContext).ifPresent(state::setShuffle);
            Optional.ofNullable(frame.options.playerOptionsOverride.repeatingTrack).ifPresent(this::setRepeatingTrack);
        }

        if (frame.context.uri == null) {
            state.clearTrack();
            state.setPlayingTrackIndex(0);
            return;
        }

        setContext(frame.context);
        if (context instanceof SearchContext) {
            state.setContextDescription(((SearchContext) context).searchTerm);
        } else {
            JsonObject metadata = frame.context.metadata;
            if (metadata == null) {
                MercuryRequests.ResolvedContextWrapper resolved = session.mercury().sendSync(MercuryRequests.resolveContext(frame.context.uri));
                metadata = resolved.metadata();
            }

            JsonElement elm = metadata.get("context_description");
            if (elm != null) state.setContextDescription(elm.getAsString());
            else state.setContextDescription("");
        }

        int pageIndex;
        if (frame.options == null || frame.options.skipTo == null) {
            pageIndex = 0;
        } else {
            pageIndex = frame.options.skipTo.pageIndex;
            if (pageIndex == -1) pageIndex = 0;
        }

        List<Remote3Page> pages = frame.context.pages;
        if (pages == null) pages = getPages(frame.context);

        int totalTracks = -1;
        if (frame.context.metadata != null) {
            JsonElement elm = frame.context.metadata.get("track_count");
            if (elm != null && elm.isJsonPrimitive()) totalTracks = elm.getAsInt();
        }

        Remote3Page page = pages.get(pageIndex);
        loadPage(page, new TrackSelector(frame.options == null ? null : frame.options.skipTo), totalTracks);
    }

    void updateContext(@NotNull Remote3Frame.Context context) {
        Remote3Page page;
        List<Remote3Track> tracks;
        if (context.pages == null || context.pages.isEmpty() || (page = context.pages.get(0)) == null || (tracks = page.tracks) == null) {
            LOGGER.warn("Did not update context. Malformed request.");
            return;
        }

        int totalTracks = -1;
        if (context.metadata != null) {
            JsonElement elm = context.metadata.get("track_count");
            if (elm != null && elm.isJsonPrimitive()) totalTracks = elm.getAsInt();
        }

        boolean allTracks = totalTracks == tracks.size();
        PlayableId.removeUnsupported(tracks, -1); // FIXME
        tracksKeeper.update(tracks, allTracks);
    }

    void setQueue(@NotNull Remote3Frame frame) {
        if (tracksKeeper == null) return;

        tracksKeeper.setQueue(frame.prevTracks, frame.nextTracks);
    }

    void addToQueue(@NotNull Remote3Frame frame) {
        if (tracksKeeper == null) return;

        if (frame.track == null)
            throw new IllegalArgumentException("Missing track object!");

        tracksKeeper.addToQueue(frame.track);
    }

    boolean hasTracks() {
        return tracksKeeper != null;
    }

    @NotNull
    PlayableId getCurrentTrack() {
        return tracksKeeper.currentlyPlaying();
    }

    @NotNull
    StateWrapper.NextPlayable nextPlayable(@NotNull Player.Configuration conf) {
        if (tracksKeeper == null) return NextPlayable.MISSING_TRACKS;
        return tracksKeeper.nextPlayable(conf);
    }

    @Nullable
    PlayableId nextPlayableDoNotSet() {
        return tracksKeeper.nextPlayableDoNotSet();
    }

    @NotNull
    PreviousPlayable previousPlayable() {
        if (tracksKeeper == null) return PreviousPlayable.MISSING_TRACKS;
        return tracksKeeper.previousPlayable();
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

    private static class TrackSelector {
        private final String trackUid;
        private final String trackUri;
        private int trackIndex;
        private int selectedIndex = -1;

        TrackSelector(@Nullable Remote3Frame.Options.SkipTo skipTo) {
            if (skipTo == null) {
                trackUid = null;
                trackUri = null;
                trackIndex = -1;
            } else {
                trackUri = skipTo.trackUri;
                if (skipTo.trackUid != null) {
                    trackUid = skipTo.trackUid;
                    trackIndex = -1;
                } else {
                    trackIndex = skipTo.trackIndex;
                    trackUid = null;
                }
            }
        }

        TrackSelector(@NotNull PlayableId id) {
            trackUid = null;
            trackIndex = -1;
            trackUri = id.toSpotifyUri();
        }

        void inspect(int index, @NotNull Remote3Track track) {
            if (findMatch()) return;

            if (trackUid == null && trackUri == null) return;

            if (trackUid != null) {
                if (Objects.equals(track.uid, trackUid))
                    selectedIndex = index;
            } else {
                if (Objects.equals(track.uri, trackUri))
                    selectedIndex = index;
            }
        }

        boolean findMatch() {
            return selectedIndex != -1 || trackIndex != -1;
        }

        int playingIndex() {
            if (trackIndex != -1) return trackIndex;
            if (trackUid == null && trackUri == null) return 0;
            return selectedIndex == -1 ? 0 : selectedIndex;
        }

        @NotNull
        public PlayableId find(@NotNull List<Remote3Track> tracks) {
            if (trackIndex != -1) return tracks.get(trackIndex).id();

            if (trackUid == null && trackUri == null) return tracks.get(0).id();

            for (Remote3Track track : tracks) {
                if (trackUid != null) {
                    if (Objects.equals(track.uid, trackUid))
                        return track.id();
                } else {
                    if (Objects.equals(track.uri, trackUri))
                        return track.id();
                }
            }

            return tracks.get(0).id();
        }
    }

    private class TracksKeeper {
        private static final int LOAD_MORE_THRESHOLD = 3;
        private final AbsSpotifyContext<?> context;
        private final List<Remote3Track> tracks;
        private int playingIndex = -1;
        private volatile boolean complete;
        private long shuffleSeed = 0;
        private ContentProvider provider;

        private TracksKeeper(@NotNull AbsSpotifyContext<?> context, @NotNull List<Remote3Track> tracks, boolean all) {
            this.context = context;
            this.tracks = new ArrayList<>(tracks.size());
            this.tracks.addAll(tracks);

            complete = all && context.isFinite();
            if (!all) fetchAsync();

            // TODO: We should probably subscribe to events on this context
        }

        synchronized void shuffle(@NotNull Random random, boolean fully) { // FIXME: Broken
            if (tracks.size() <= 1)
                return;

            shuffleSeed = random.nextLong();

            if (fully) {
                Collections.shuffle(tracks, new Random(shuffleSeed));
            } else {
                if (playingIndex != 0) {
                    Collections.swap(tracks, 0, playingIndex);
                    playingIndex = 0;
                }

                int size = tracks.size() - 1;
                int[] exchanges = getShuffleExchanges(size, shuffleSeed);
                for (int i = size - 1; i > 1; i--) {
                    int n = exchanges[size - 1 - i];
                    Collections.swap(tracks, i, n + 1);
                }
            }

            LOGGER.trace(String.format("Shuffled {seed: %d, fully: %b}", shuffleSeed, fully));
        }

        synchronized void unshuffle() { // FIXME: Broken
            if (tracks.size() <= 1)
                return;

            if (shuffleSeed != 0) {
                if (playingIndex != 0) {
                    Collections.swap(tracks, 0, playingIndex);
                    playingIndex = 0;
                }

                int size = tracks.size() - 1;
                int[] exchanges = getShuffleExchanges(size, shuffleSeed);
                for (int i = 1; i < size; i++) {
                    int n = exchanges[size - i - 1];
                    Collections.swap(tracks, i, n + 1);
                }

                LOGGER.trace("Unshuffled using seed: " + shuffleSeed);
                return;
            }

            if (state.hasContextUri()) {
                PlayableId currentlyPlaying = currentlyPlaying();

                try {
                    loadAllTracks();
                } catch (IOException | MercuryClient.MercuryException ex) {
                    LOGGER.fatal("Cannot unshuffle context!", ex);
                    return;
                }

                setPlaying(currentlyPlaying);

                LOGGER.trace("Unshuffled using context-resolve.");
            } else {
                LOGGER.fatal("Cannot unshuffle context! Did not know seed and context is missing.");
            }
        }

        private void loadAllTracks() throws IOException, MercuryClient.MercuryException {
            MercuryRequests.ResolvedContextWrapper resolved = session.mercury().sendSync(MercuryRequests.resolveContext(context.uri()));
            updateWithPage(resolved.pages().get(0), true);
        }

        private void updateWithPage(@NotNull Remote3Page page, boolean complete) throws IOException {
            List<Remote3Track> newTracks = page.tracks;
            if (newTracks == null) {
                if (page.pageUrl != null) {
                    newTracks = getTracks(page.pageUrl);
                } else {
                    throw new IllegalStateException("How do I load this page?!");
                }
            }

            update(newTracks, complete);
        }

        private void fetchAsync() {
            session.mercury().send(MercuryRequests.resolveContext(context.uri()), new MercuryClient.JsonCallback<MercuryRequests.ResolvedContextWrapper>() {
                @Override
                public void response(MercuryRequests.@NotNull ResolvedContextWrapper json) {
                    try {
                        updateWithPage(json.pages().get(0), true);
                    } catch (IOException ex) {
                        exception(ex);
                    }
                }

                @Override
                public void exception(@NotNull Exception ex) {
                    LOGGER.error("Failed resolving context asynchronously!", ex);
                    complete = false;
                }
            });
        }

        synchronized void dumpToState(@NotNull Spirc.State.Builder state) {
            int from = Math.max(0, playingIndex - STATE_TRACKS_BEFORE);
            int to = Math.min(tracks.size(), playingIndex + STATE_TRACKS_AFTER);
            int relativeIndex = playingIndex - from;

            state.clearTrack();
            for (int i = from; i < to; i++)
                state.addTrack(tracks.get(i).toTrackRef());

            state.setPlayingTrackIndex(relativeIndex);
        }

        @NotNull
        synchronized PlayableId currentlyPlaying() {
            return tracks.get(playingIndex).id();
        }

        /**
         * @return Next song or {@code null} if at the end of the list
         */
        @Nullable
        synchronized PlayableId nextPlayableDoNotSet() {
            if (context.isFinite()) {
                if (!complete && tracks.size() - playingIndex <= LOAD_MORE_THRESHOLD) {
                    try {
                        loadAllTracks();
                    } catch (IOException | MercuryClient.MercuryException ex) {
                        LOGGER.error("Failed resolving context.", ex);
                    }
                }
            } else {
                if (complete) throw new IllegalStateException();

                if (playingIndex + 1 >= state.getTrackCount() - LOAD_MORE_THRESHOLD) {
                    if (provider == null) provider = context.initProvider(session);
                    if (provider == null) throw new IllegalStateException();

                    try {
                        Remote3Page page = provider.nextPage();
                        tracks.addAll(page.tracks);
                        LOGGER.debug("Fetched more tracks, size: " + tracks.size());
                    } catch (IOException | MercuryClient.MercuryException ex) {
                        LOGGER.fatal("Failed loading more content!", ex);
                    }
                }
            }

            if (playingIndex + 1 >= tracks.size()) return null;
            return tracks.get(playingIndex + 1).id();
        }

        @NotNull
        synchronized NextPlayable nextPlayable(@NotNull Player.Configuration conf) {
            boolean play = true;
            PlayableId next = nextPlayableDoNotSet();
            if (next == null) {
                if (state.getRepeat()) {
                    playingIndex = 0;
                } else {
                    if (conf.autoplayEnabled()) {
                        return NextPlayable.AUTOPLAY;
                    } else {
                        playingIndex = 0;
                        play = false;
                    }
                }
            } else {
                playingIndex++;
            }

            if (play) return NextPlayable.OK_PLAY;
            else return NextPlayable.OK_PAUSE;
        }

        synchronized void setPlaying(@Nullable PlayableId id) {
            if (id == null) {
                playingIndex = 0;
                return;
            }

            playingIndex = indexOf(id);
            if (playingIndex == -1) playingIndex = 0;
        }

        private int indexOf(@NotNull PlayableId current) {
            for (int i = 0; i < tracks.size(); i++)
                if (tracks.get(i).is(current))
                    return i;

            return -1;
        }

        @NotNull
        synchronized PreviousPlayable previousPlayable() {
            if (playingIndex == 0) {
                if (state.getRepeat()) {
                    if (!complete) {
                        try {
                            loadAllTracks();
                        } catch (IOException | MercuryClient.MercuryException ex) {
                            LOGGER.error("Failed resolving context.", ex);
                        }
                    }

                    playingIndex = tracks.size() - 1;
                }
            } else {
                playingIndex--;
            }

            return PreviousPlayable.OK;
        }

        synchronized void update(@NotNull List<Remote3Track> newTracks, boolean complete) { // TODO: What if it's shuffled?
            tracks.clear();

            PlayableId previouslyPlaying = newTracks.get(playingIndex).id();
            TrackSelector selector = new TrackSelector(previouslyPlaying);
            for (int i = 0; i < newTracks.size(); i++) {
                Remote3Track track = newTracks.get(i);
                tracks.add(track);
                selector.inspect(i, track);
            }

            playingIndex = selector.playingIndex();

            if (complete) this.complete = true;
        }

        private int lastQueuedSongIndex() {
            int lastQueued = -1;
            int firstQueued = -1;
            for (int i = playingIndex; i < tracks.size(); i++) {
                if (tracks.get(i).isQueued()) {
                    if (firstQueued == -1) firstQueued = i;
                } else {
                    if (firstQueued != -1 && lastQueued == -1) lastQueued = i - 1;
                }
            }

            return lastQueued;
        }

        synchronized void addToQueue(@NotNull Remote3Track track) {
            int index = lastQueuedSongIndex();
            if (index == -1) index = playingIndex + 1;
            tracks.add(index, track);
        }

        synchronized void setQueue(@Nullable List<Remote3Track> prevTracks, @Nullable List<Remote3Track> nextTracks) {
            Remote3Track currentlyPlaying = tracks.get(playingIndex);
            tracks.clear();

            if (prevTracks != null) {
                PlayableId.removeUnsupported(prevTracks, -1);
                tracks.addAll(prevTracks);
            }

            tracks.add(currentlyPlaying);
            playingIndex = tracks.size() - 1;

            if (nextTracks != null) {
                PlayableId.removeUnsupported(nextTracks, -1);
                tracks.addAll(nextTracks);
            }
        }

        synchronized void seekTo(@NotNull String uri) {
            PlayableId id = context.createId(uri);
            int index = indexOf(id);
            if (index != -1) {
                playingIndex = index;
                return;
            }

            if (complete) {
                LOGGER.warn("Couldn't seek to track: did not found in list.");
                return;
            }

            // TODO: Fetch all tracks and do it again
        }
    }
}