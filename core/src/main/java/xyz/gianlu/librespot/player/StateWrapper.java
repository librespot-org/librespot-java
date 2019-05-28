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
import xyz.gianlu.librespot.player.contexts.SearchContext;
import xyz.gianlu.librespot.player.contexts.SpotifyContext;
import xyz.gianlu.librespot.player.remote.Remote3Frame;
import xyz.gianlu.librespot.player.remote.Remote3Page;
import xyz.gianlu.librespot.player.remote.Remote3Track;
import xyz.gianlu.librespot.player.tracks.PlayablesProvider;
import xyz.gianlu.librespot.player.tracks.ShuffleableProvider;
import xyz.gianlu.librespot.player.tracks.StationProvider;
import xyz.gianlu.librespot.spirc.SpotifyIrc;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @author Gianlu
 */
public class StateWrapper {
    private static final Logger LOGGER = Logger.getLogger(StateWrapper.class);
    private final Spirc.State.Builder state;
    private final Session session;
    private boolean repeatingTrack = false;
    private PlayablesProvider playablesProvider;

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

    boolean isRepeatingTrack() {
        return repeatingTrack;
    }

    void setRepeatingTrack(boolean repeatingTrack) {
        this.repeatingTrack = repeatingTrack;
    }

    @Nullable
    String getContextUri() {
        return state.getContextUri();
    }

    @NotNull
    private Spirc.PlayStatus getStatus() {
        return state.getStatus();
    }

    void setStatus(@NotNull Spirc.PlayStatus status) {
        state.setStatus(status);
    }

    boolean isStatus(@NotNull Spirc.PlayStatus status) {
        return status == getStatus();
    }

    void setShuffle(boolean shuffle) {
        state.setShuffle(shuffle && (playablesProvider == null || playablesProvider.canShuffle()));
        if (state.getShuffle()) shuffleContent(false);
        else unshuffleContent();
    }

    private void loadPlayablesProvider(@NotNull String uri) throws SpotifyContext.UnsupportedContextException {
        SpotifyContext context = SpotifyContext.from(uri);
        playablesProvider = context.initProvider(session, state);
    }

    private void shuffleContent(boolean fully) {
        if (playablesProvider == null) return;

        if (playablesProvider.canShuffle() && playablesProvider instanceof ShuffleableProvider)
            ((ShuffleableProvider) playablesProvider).shuffleContent(session.random(), fully);
        else
            LOGGER.warn("Cannot shuffle provider: " + playablesProvider);
    }

    private void unshuffleContent() {
        if (playablesProvider == null) return;

        if (playablesProvider.canShuffle() && playablesProvider instanceof ShuffleableProvider)
            ((ShuffleableProvider) playablesProvider).unshuffleContent();
        else
            LOGGER.warn("Cannot unshuffle provider: " + playablesProvider);
    }

    void updated() {
        session.spirc().deviceStateUpdated(state);
    }

    void seekTo(@Nullable String uri) {
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

    boolean getRepeat() {
        return state.getRepeat();
    }

    void setRepeat(boolean repeat) {
        state.setRepeat(repeat && (playablesProvider == null || playablesProvider.canRepeat()));
    }

    void setPlayingTrackIndex(int i) {
        state.setPlayingTrackIndex(i);
    }

    int getTrackCount() {
        return state.getTrackCount();
    }

    void loadStation(@NotNull MercuryRequests.StationsWrapper station) throws SpotifyContext.UnsupportedContextException {
        state.setContextUri(station.uri());

        state.clearTrack();
        state.addAllTrack(station.tracks());
        state.setPlayingTrackIndex(0);
        SpotifyIrc.trimTracks(state);

        loadPlayablesProvider(station.uri());
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

    void loadFromUri(@NotNull String context) throws IOException, MercuryClient.MercuryException, SpotifyContext.UnsupportedContextException {
        state.setContextUri(context);
        state.clearTrack();

        MercuryRequests.ResolvedContextWrapper resolved = session.mercury().sendSync(MercuryRequests.resolveContext(context));
        loadPage(resolved.pages().get(0), null);
        loadPlayablesProvider(context);
    }

    private void loadPage(@NotNull Remote3Page page, @Nullable TrackSelector selector) throws IOException {
        List<Remote3Track> tracks = page.tracks;
        if (tracks == null) {
            if (page.pageUrl != null) tracks = getTracks(page.pageUrl);
            else throw new IllegalStateException("How do I load this page?!");
        }

        int updated = PlayableId.removeUnsupported(tracks, selector == null ? -1 : selector.trackIndex);
        if (selector != null && updated != -1) selector.trackIndex = updated;

        for (int i = 0; i < tracks.size(); i++) {
            Remote3Track track = tracks.get(i);
            state.addTrack(track.toTrackRef());
            if (selector != null) selector.inspect(i, track);
        }

        state.setPlayingTrackIndex(selector == null ? 0 : selector.playingIndex());
        SpotifyIrc.trimTracks(state);

        if (state.getShuffle()) shuffleContent(selector == null || !selector.findMatch());
    }

    void load(@NotNull Remote3Frame frame) throws IOException, MercuryClient.MercuryException, SpotifyContext.UnsupportedContextException {
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

        SpotifyContext context;
        if ((context = SpotifyContext.from(frame.context.uri)) instanceof SearchContext) {
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

        state.setContextUri(frame.context.uri);
        state.clearTrack();

        int pageIndex;
        if (frame.options == null || frame.options.skipTo == null) {
            pageIndex = 0;
        } else {
            pageIndex = frame.options.skipTo.pageIndex;
            if (pageIndex == -1) pageIndex = 0;
        }

        List<Remote3Page> pages = frame.context.pages;
        if (pages == null) pages = getPages(frame.context);

        Remote3Page page = pages.get(pageIndex);
        loadPage(page, new TrackSelector(frame.options == null ? null : frame.options.skipTo));
        loadPlayablesProvider(frame.context.uri);
    }

    void updateContext(@NotNull Remote3Frame.Context context) throws SpotifyContext.UnsupportedContextException {
        Spirc.TrackRef previouslyPlaying = state.getTrack(state.getPlayingTrackIndex());

        state.clearTrack();

        Remote3Page page;
        List<Remote3Track> tracks;
        if (context.pages == null || context.pages.isEmpty() || (page = context.pages.get(0)) == null || (tracks = page.tracks) == null) {
            LOGGER.warn("Did not update context. Malformed request.");
            return;
        }

        PlayableId.removeUnsupported(tracks, -1);

        TrackSelector selector = new TrackSelector(context.uri, previouslyPlaying);
        for (int i = 0; i < tracks.size(); i++) {
            Remote3Track track = tracks.get(i);
            state.addTrack(track.toTrackRef());
            selector.inspect(i, track);
        }

        state.setPlayingTrackIndex(selector.playingIndex());
        SpotifyIrc.trimTracks(state);

        if (page.nextPageUrl != null && playablesProvider instanceof StationProvider)
            ((StationProvider) playablesProvider).knowsNextPageUrl(page.nextPageUrl);
    }

    void setQueue(@NotNull Remote3Frame frame) {
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

    void addToQueue(@NotNull Remote3Frame frame) {
        if (frame.track == null)
            throw new IllegalArgumentException("Missing track object!");

        int index = lastQueuedSongIndex();
        if (index == -1) index = state.getPlayingTrackIndex() + 1;

        state.addTrack(index, frame.track.toTrackRef());
    }

    boolean hasProvider() {
        return playablesProvider != null;
    }

    int getPrevTrackIndex() {
        return playablesProvider.getPrevTrackIndex();
    }

    int getNextTrackIndex(boolean consume) {
        return playablesProvider.getNextTrackIndex(consume);
    }

    @NotNull
    PlayableId getTrackAt(int index) {
        return playablesProvider.getTrackAt(index);
    }

    @NotNull
    PlayableId getCurrentTrack() {
        return playablesProvider.getCurrentTrack();
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

        TrackSelector(@NotNull String context, @NotNull Spirc.TrackRef ref) throws SpotifyContext.UnsupportedContextException {
            trackUid = null;
            trackIndex = -1;

            PlayableId id = SpotifyContext.from(context).createId(ref);
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
    }
}
