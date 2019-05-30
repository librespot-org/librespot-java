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
import xyz.gianlu.librespot.player.remote.Remote3Frame;
import xyz.gianlu.librespot.player.remote.Remote3Page;
import xyz.gianlu.librespot.player.remote.Remote3Track;
import xyz.gianlu.librespot.spirc.SpotifyIrc;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

/**
 * @author Gianlu
 */
public class StateWrapper {
    private static final Logger LOGGER = Logger.getLogger(StateWrapper.class);
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

    private int lastQueuedSongIndex() {
        int lastQueued = -1;
        int firstQueued = -1;
        for (int i = state.getPlayingTrackIndex(); i < state.getTrackCount(); i++) {
            if (state.getTrack(i).getQueued()) {
                if (firstQueued == -1) firstQueued = i;
            } else {
                if (firstQueued != -1 && lastQueued == -1) lastQueued = i - 1;
            }
        }

        return lastQueued;
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
        tracksKeeper.dumpToState(state, current);

        if (state.getShuffle()) shuffleContent(selector == null || !selector.findMatch());
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
        session.spirc().deviceStateUpdated(state);
    }

    void seekTo(@Nullable String uri) { // FIXME: We may need to update the tracks before doing this
        int pos = -1;
        List<Spirc.TrackRef> tracks = state.getTrackList();
        for (int i = 0; i < tracks.size(); i++) {
            Spirc.TrackRef track = tracks.get(i);
            if (track.getUri().equals(uri)) {
                pos = i;
                break;
            }
        }

        if (pos == -1)
            pos = 0;

        state.setPlayingTrackIndex(pos);
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

        PlayableId.removeUnsupported(tracks, -1); // FIXME
        tracksKeeper.update(tracks);
        tracksKeeper.dumpToState(state, getCurrentTrack());
    }

    void setQueue(@NotNull Remote3Frame frame) { // FIXME: Rewrite
        Spirc.TrackRef currentlyPlaying = state.getTrack(state.getPlayingTrackIndex());

        state.clearTrack();

        List<Remote3Track> prevTracks = frame.prevTracks;
        if (prevTracks != null) {
            PlayableId.removeUnsupported(prevTracks, -1);
            for (Remote3Track track : prevTracks)
                state.addTrack(track.toTrackRef());
        }

        state.addTrack(currentlyPlaying);
        int index = state.getTrackCount() - 1;

        List<Remote3Track> nextTracks = frame.nextTracks;
        if (nextTracks != null) {
            PlayableId.removeUnsupported(nextTracks, -1);
            for (Remote3Track track : nextTracks)
                state.addTrack(track.toTrackRef());
        }

        state.setPlayingTrackIndex(index);
        SpotifyIrc.trimTracks(state);
    }

    void addToQueue(@NotNull Remote3Frame frame) { // FIXME: Rewrite
        if (frame.track == null)
            throw new IllegalArgumentException("Missing track object!");

        int index = lastQueuedSongIndex();
        if (index == -1) index = state.getPlayingTrackIndex() + 1;

        state.addTrack(index, frame.track.toTrackRef());
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
        private final AbsSpotifyContext<?> context;
        private final List<Remote3Track> tracks;
        private int playingIndex = -1;
        private volatile boolean complete;
        private long shuffleSeed = 0;

        private TracksKeeper(@NotNull AbsSpotifyContext<?> context, @NotNull List<Remote3Track> tracks, boolean all) {
            this.context = context;
            this.tracks = new ArrayList<>(tracks.size());
            this.tracks.addAll(tracks);

            complete = all && context.isFinite();
            if (!all) fetchAsync();

            // TODO: We should probably subscribe to events on this context
        }

        void shuffle(@NotNull Random random, boolean fully) {
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

            LOGGER.trace("Shuffled, seed: " + shuffleSeed);
        }

        void unshuffle() {
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

            /* TODO: Resolve context and load it to unshuffle
            if (state.hasContextUri()) {
                List<Remote3Track> tracks;
                try {
                    MercuryRequests.ResolvedContextWrapper context = mercury.sendSync(MercuryRequests.resolveContext(state.getContextUri()));
                    tracks = context.pages().get(0).tracks;
                } catch (IOException | MercuryClient.MercuryException ex) {
                    LOGGER.fatal("Cannot unshuffle context!", ex);
                    return;
                }

                PlayableId.removeUnsupported(tracks, -1);

                Spirc.TrackRef current = state.getTrack(state.getPlayingTrackIndex());
                String currentTrackUri = TrackId.fromTrackRef(current).toSpotifyUri();

                List<Spirc.TrackRef> rebuildState = new ArrayList<>(SpotifyIrc.MAX_TRACKS);
                boolean add = false;
                int count = SpotifyIrc.MAX_TRACKS;
                for (Remote3Track track : tracks) {
                    if (add || track.uri.equals(currentTrackUri)) {
                        rebuildState.add(track.toTrackRef());

                        add = true;
                        count--;
                        if (count <= 0) break;
                    }
                }

                if (rebuildState.isEmpty())
                    throw new IllegalStateException("State cannot be empty!");

                state.clearTrack();
                state.addAllTrack(rebuildState);
                state.setPlayingTrackIndex(0);
                SpotifyIrc.trimTracks(state);

                LOGGER.trace("Unshuffled using context-resolve.");
            } else {
                LOGGER.fatal("Cannot unshuffle context! Did not know seed and context is missing.");
            }
             */
        }

        private void fetchAsync() {
            session.mercury().send(MercuryRequests.resolveContext(context.uri()), new MercuryClient.JsonCallback<MercuryRequests.ResolvedContextWrapper>() {
                @Override
                public void response(MercuryRequests.@NotNull ResolvedContextWrapper json) {
                    complete = true;
                    // TODO: Load complete context
                }

                @Override
                public void exception(@NotNull Exception ex) {
                    complete = false;
                    // FIXME: Failed loading complete context, shit.
                }
            });
        }

        private int indexOf(@NotNull PlayableId current) {
            for (int i = 0; i < tracks.size(); i++)
                if (tracks.get(i).is(current))
                    return i;

            return -1;
        }

        void dumpToState(@NotNull Spirc.State.Builder state, @Nullable PlayableId current) {
            int currentIndex = current == null ? -1 : indexOf(current);
            if (currentIndex == -1) currentIndex = 0;

            playingIndex = currentIndex;

            // FIXME: Using test values
            int from = Math.max(0, currentIndex - 20);
            int to = Math.min(tracks.size(), currentIndex + 20);
            int relativeIndex = currentIndex - from;

            state.clearTrack();
            for (int i = from; i < to; i++)
                state.addTrack(tracks.get(i).toTrackRef());

            state.setPlayingTrackIndex(relativeIndex);
        }

        @NotNull
        PlayableId currentlyPlaying() {
            return tracks.get(playingIndex).id();
        }

        /**
         * @return Next song or {@code null} if at the end of the list
         */
        @Nullable
        PlayableId nextPlayableDoNotSet() {
            if (playingIndex + 1 >= tracks.size()) return null; // TODO: Check that list is complete
            return tracks.get(playingIndex + 1).id();
        }

        @NotNull
        NextPlayable nextPlayable(@NotNull Player.Configuration conf) {
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

        @NotNull
        PreviousPlayable previousPlayable() {
            if (playingIndex == 0) {
                if (state.getRepeat())
                    playingIndex = tracks.size() - 1; // TODO: Check that list is complete
            } else {
                playingIndex--;
            }

            return PreviousPlayable.OK;
        }

        void update(@NotNull List<Remote3Track> newTracks) {
            tracks.clear();

            PlayableId previouslyPlaying = newTracks.get(playingIndex).id();
            TrackSelector selector = new TrackSelector(previouslyPlaying);
            for (int i = 0; i < newTracks.size(); i++) {
                Remote3Track track = newTracks.get(i);
                tracks.add(track);
                selector.inspect(i, track);
            }

            playingIndex = selector.playingIndex();

            // TODO: What happens to complete?
        }
    }
}