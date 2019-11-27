package xyz.gianlu.librespot.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.TextFormat;
import com.spotify.connectstate.model.Connect;
import com.spotify.connectstate.model.Player.*;
import com.spotify.metadata.proto.Metadata;
import com.spotify.playlist4.proto.Playlist4ApiProto;
import com.spotify.playlist4.proto.Playlist4ApiProto.PlaylistModificationInfo;
import okhttp3.*;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spotify.player.proto.ContextPageOuterClass.ContextPage;
import spotify.player.proto.ContextTrackOuterClass.ContextTrack;
import spotify.player.proto.transfer.PlaybackOuterClass.Playback;
import spotify.player.proto.transfer.QueueOuterClass;
import spotify.player.proto.transfer.SessionOuterClass;
import spotify.player.proto.transfer.TransferStateOuterClass;
import xyz.gianlu.librespot.BytesArrayList;
import xyz.gianlu.librespot.common.FisherYatesShuffle;
import xyz.gianlu.librespot.common.ProtoUtils;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.connectstate.DeviceStateHandler;
import xyz.gianlu.librespot.connectstate.DeviceStateHandler.PlayCommandHelper;
import xyz.gianlu.librespot.connectstate.RestrictionsManager;
import xyz.gianlu.librespot.connectstate.RestrictionsManager.Action;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TimeProvider;
import xyz.gianlu.librespot.dealer.DealerClient;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.model.AlbumId;
import xyz.gianlu.librespot.mercury.model.ArtistId;
import xyz.gianlu.librespot.mercury.model.ImageId;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.contexts.AbsSpotifyContext;

import java.io.IOException;
import java.util.*;

import static spotify.player.proto.ContextOuterClass.Context;

/**
 * @author Gianlu
 */
public class StateWrapper implements DeviceStateHandler.Listener, DealerClient.MessageListener {
    private static final Logger LOGGER = Logger.getLogger(StateWrapper.class);
    private static final JsonParser PARSER = new JsonParser();

    static {
        try {
            ProtoUtils.overrideDefaultValue(ContextIndex.getDescriptor().findFieldByName("track"), -1);
            ProtoUtils.overrideDefaultValue(PlayerState.getDescriptor().findFieldByName("position_as_of_timestamp"), -1);
            ProtoUtils.overrideDefaultValue(ContextPlayerOptions.getDescriptor().findFieldByName("shuffling_context"), "");
            ProtoUtils.overrideDefaultValue(ContextPlayerOptions.getDescriptor().findFieldByName("repeating_track"), "");
            ProtoUtils.overrideDefaultValue(ContextPlayerOptions.getDescriptor().findFieldByName("repeating_context"), "");
        } catch (IllegalAccessException | NoSuchFieldException ex) {
            LOGGER.warn("Failed changing default value!", ex);
        }
    }

    private final PlayerState.Builder state;
    private final Session session;
    private final DeviceStateHandler device;
    private AbsSpotifyContext context;
    private PagesLoader pages;
    private TracksKeeper tracksKeeper;

    StateWrapper(@NotNull Session session) {
        this.session = session;
        this.device = new DeviceStateHandler(session);
        this.state = initState(PlayerState.newBuilder());

        device.addListener(this);
        session.dealer().addMessageListener(this, "hm://playlist/", "hm://collection/collection/" + session.username() + "/json");
    }

    @NotNull
    private static PlayerState.Builder initState(@NotNull PlayerState.Builder builder) {
        return builder.setPlaybackSpeed(1.0)
                .clearSessionId().clearPlaybackId()
                .setSuppressions(Suppressions.newBuilder().build())
                .setContextRestrictions(Restrictions.newBuilder().build())
                .setOptions(ContextPlayerOptions.newBuilder()
                        .setRepeatingContext(false)
                        .setShufflingContext(false)
                        .setRepeatingTrack(false))
                .setPositionAsOfTimestamp(0)
                .setPosition(0)
                .setIsPlaying(false);
    }

    private static boolean shouldPlay(@NotNull ContextTrack track) {
        return PlayableId.isSupported(track.getUri()) && PlayableId.shouldPlay(track);
    }

    @NotNull
    private static String generatePlaybackId(@NotNull Random random) {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        bytes[0] = 1;
        return Utils.bytesToHex(bytes).toLowerCase();
    }

    @NotNull
    private static String generateSessionId(@NotNull Random random) {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    void setState(@Nullable Boolean playing, @Nullable Boolean paused, @Nullable Boolean buffering) {
        setState(playing == null ? state.getIsPlaying() : playing, paused == null ? state.getIsPaused() : paused,
                buffering == null ? state.getIsBuffering() : buffering);
    }

    synchronized void setState(boolean playing, boolean paused, boolean buffering) {
        if (paused && !playing) throw new IllegalStateException();
        else if (buffering && !playing) throw new IllegalStateException();

        state.setIsPlaying(playing).setIsPaused(paused).setIsBuffering(buffering);
    }

    boolean isPlaying() {
        return state.getIsPlaying() && !state.getIsPaused();
    }

    boolean isPaused() {
        return state.getIsPlaying() && state.getIsPaused();
    }

    private boolean isShufflingContext() {
        return state.getOptions().getShufflingContext();
    }

    void setShufflingContext(boolean value) {
        if (context == null) return;

        boolean old = isShufflingContext();
        state.getOptionsBuilder().setShufflingContext(value && context.restrictions.can(Action.SHUFFLE));

        if (old != isShufflingContext()) tracksKeeper.toggleShuffle(isShufflingContext());
    }

    private boolean isRepeatingContext() {
        return state.getOptions().getRepeatingContext();
    }

    void setRepeatingContext(boolean value) {
        if (context == null) return;

        state.getOptionsBuilder().setRepeatingContext(value && context.restrictions.can(Action.REPEAT_CONTEXT));
    }

    private boolean isRepeatingTrack() {
        return state.getOptions().getRepeatingTrack();
    }

    void setRepeatingTrack(boolean value) {
        if (context == null) return;

        state.getOptionsBuilder().setRepeatingTrack(value && context.restrictions.can(Action.REPEAT_TRACK));
    }

    @Nullable
    public String getContextUri() {
        return state.getContextUri();
    }

    private void loadTransforming() {
        if (tracksKeeper == null) throw new IllegalStateException();

        String url = state.getContextMetadataOrDefault("transforming.url", null);
        if (url == null) return;

        boolean shuffle = false;
        if (state.containsContextMetadata("transforming.shuffle"))
            shuffle = Boolean.parseBoolean(state.getContextMetadataOrThrow("transforming.shuffle"));

        boolean willRequest = !tracksKeeper.getCurrentTrack().getMetadataMap().containsKey("audio.fwdbtn.fade_overlap"); // I don't see another way to do this
        LOGGER.info(String.format("Context has transforming! {url: %s, shuffle: %b, willRequest: %b}", url, shuffle, willRequest));

        if (!willRequest) return;
        JsonObject obj = ProtoUtils.craftContextStateCombo(state, tracksKeeper.tracks);
        try (Response resp = session.api().send("POST", HttpUrl.get(url).encodedPath(), null, RequestBody.create(obj.toString(), MediaType.get("application/json")))) {
            ResponseBody body = resp.body();
            if (resp.code() != 200) {
                LOGGER.warn(String.format("Failed loading cuepoints! {code: %d, msg: %s, body: %s}", resp.code(), resp.message(), body == null ? null : body.string()));
                return;
            }

            if (body != null) updateContext(PARSER.parse(body.string()).getAsJsonObject());
            else throw new IllegalArgumentException();

            LOGGER.debug("Updated context with transforming information!");
        } catch (MercuryClient.MercuryException | IOException ex) {
            LOGGER.warn("Failed loading cuepoints!", ex);
        }
    }

    private void setContext(@NotNull String uri) {
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

        renewSessionId();
    }

    private void setContext(@NotNull Context ctx) {
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
        ProtoUtils.copyOverMetadata(ctx, state);

        this.pages = PagesLoader.from(session, ctx);
        this.tracksKeeper = new TracksKeeper();

        this.device.setIsActive(true);

        renewSessionId();
    }

    private void updateRestrictions() {
        if (context == null) return;

        if (isPaused()) context.restrictions.disallow(Action.PAUSE, RestrictionsManager.REASON_ALREADY_PAUSED);
        else context.restrictions.allow(Action.PAUSE);
        if (isPlaying()) context.restrictions.disallow(Action.RESUME, RestrictionsManager.REASON_NOT_PAUSED);
        else context.restrictions.allow(Action.RESUME);

        if (tracksKeeper.isPlayingFirst() && !isRepeatingContext())
            context.restrictions.disallow(Action.SKIP_PREV, RestrictionsManager.REASON_NO_PREV_TRACK);
        else
            context.restrictions.allow(Action.SKIP_PREV);

        if (tracksKeeper.isPlayingLast() && !isRepeatingContext())
            context.restrictions.disallow(Action.SKIP_NEXT, RestrictionsManager.REASON_NO_NEXT_TRACK);
        else
            context.restrictions.allow(Action.SKIP_NEXT);

        state.setRestrictions(context.restrictions.toProto());
        state.setContextRestrictions(context.restrictions.toProto());
    }

    synchronized void updated() {
        updateRestrictions();
        device.updateState(Connect.PutStateReason.PLAYER_STATE_CHANGED, state.build());
    }

    void addListener(@NotNull DeviceStateHandler.Listener listener) {
        device.addListener(listener);
    }

    @Override
    public synchronized void ready() {
        state.setIsSystemInitiated(true);
        device.updateState(Connect.PutStateReason.NEW_DEVICE, state.build());
        LOGGER.info("Notified new device (us)!");
    }

    @Override
    public void command(@NotNull DeviceStateHandler.Endpoint endpoint, @NotNull DeviceStateHandler.CommandBody data) {
        // Not interested
    }

    @Override
    public synchronized void volumeChanged() {
        device.updateState(Connect.PutStateReason.VOLUME_CHANGED, state.build());
    }

    @Override
    public synchronized void notActive() {
        state.clear();
        initState(state);

        device.setIsActive(false);
        device.updateState(Connect.PutStateReason.BECAME_INACTIVE, state.build());
        LOGGER.info("Notified inactivity!");
    }

    synchronized int getVolume() {
        return device.getVolume();
    }

    void setVolume(int val) {
        device.setVolume(val);
    }

    synchronized void enrichWithMetadata(@NotNull Metadata.Track track) {
        if (state.getTrack() == null) throw new IllegalStateException();
        if (!state.getTrack().getUri().equals(PlayableId.from(track).toSpotifyUri())) {
            LOGGER.warn(String.format("Failed updating metadata: tracks do not match. {current: %s, expected: %s}", state.getTrack().getUri(), PlayableId.from(track).toSpotifyUri()));
            return;
        }

        if (track.hasDuration()) tracksKeeper.updateTrackDuration(track.getDuration());

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

            if (track.hasDiscNumber()) {
                for (Metadata.Disc disc : album.getDiscList()) {
                    if (disc.getNumber() != track.getDiscNumber()) continue;

                    for (int i = 0; i < disc.getTrackCount(); i++) {
                        if (disc.getTrack(i).getGid().equals(track.getGid())) {
                            builder.putMetadata("album_track_number", String.valueOf(i + 1));
                            break;
                        }
                    }
                }
            }

            if (album.hasCoverGroup()) ImageId.putAsMetadata(builder, album.getCoverGroup());
        }

        ProtoUtils.putFilesAsMetadata(builder, track.getFileList());
        state.setTrack(builder.build());
    }

    synchronized void enrichWithMetadata(@NotNull Metadata.Episode episode) {
        if (state.getTrack() == null) throw new IllegalStateException();
        if (!state.getTrack().getUri().equals(PlayableId.from(episode).toSpotifyUri())) {
            LOGGER.warn(String.format("Failed updating metadata: episodes do not match. {current: %s, expected: %s}", state.getTrack().getUri(), PlayableId.from(episode).toSpotifyUri()));
            return;
        }

        if (episode.hasDuration()) tracksKeeper.updateTrackDuration(episode.getDuration());

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
        state.setTimestamp(TimeProvider.currentTimeMillis());
        state.setPositionAsOfTimestamp(pos);
        state.clearPosition();
    }

    void loadContextWithTracks(@NotNull String uri, @NotNull List<ContextTrack> tracks) throws MercuryClient.MercuryException, IOException, AbsSpotifyContext.UnsupportedContextException {
        state.setPlayOrigin(PlayOrigin.newBuilder().build());
        state.setOptions(ContextPlayerOptions.newBuilder().build());

        setContext(uri);
        pages.putFirstPage(tracks);
        tracksKeeper.initializeStart();
        setPosition(0);

        loadTransforming();
    }

    void loadContext(@NotNull String uri) throws MercuryClient.MercuryException, IOException, AbsSpotifyContext.UnsupportedContextException {
        state.setPlayOrigin(PlayOrigin.newBuilder().build());
        state.setOptions(ContextPlayerOptions.newBuilder().build());

        setContext(uri);
        tracksKeeper.initializeStart();
        setPosition(0);

        loadTransforming();
    }

    void transfer(@NotNull TransferStateOuterClass.TransferState cmd) throws AbsSpotifyContext.UnsupportedContextException, IOException, MercuryClient.MercuryException {
        SessionOuterClass.Session ps = cmd.getCurrentSession();

        state.setPlayOrigin(ProtoUtils.convertPlayOrigin(ps.getPlayOrigin()));
        state.setOptions(ProtoUtils.convertPlayerOptions(cmd.getOptions()));
        setContext(ps.getContext());

        Playback pb = cmd.getPlayback();
        tracksKeeper.initializeFrom(tracks -> ProtoUtils.indexOfTrackByUid(tracks, ps.getCurrentUid()), pb.getCurrentTrack(), cmd.getQueue());

        state.setPositionAsOfTimestamp(pb.getPositionAsOfTimestamp());
        if (pb.getIsPaused()) state.setTimestamp(TimeProvider.currentTimeMillis());
        else state.setTimestamp(pb.getTimestamp());

        loadTransforming();
    }

    void load(@NotNull JsonObject obj) throws AbsSpotifyContext.UnsupportedContextException, IOException, MercuryClient.MercuryException {
        state.setPlayOrigin(ProtoUtils.jsonToPlayOrigin(PlayCommandHelper.getPlayOrigin(obj)));
        state.setOptions(ProtoUtils.jsonToPlayerOptions(PlayCommandHelper.getPlayerOptionsOverride(obj), state.getOptions()));
        setContext(ProtoUtils.jsonToContext(PlayCommandHelper.getContext(obj)));

        String trackUid = PlayCommandHelper.getSkipToUid(obj);
        String trackUri = PlayCommandHelper.getSkipToUri(obj);
        Integer trackIndex = PlayCommandHelper.getSkipToIndex(obj);

        if (trackUri != null) {
            tracksKeeper.initializeFrom(tracks -> ProtoUtils.indexOfTrackByUri(tracks, trackUri), null, null);
        } else if (trackUid != null) {
            tracksKeeper.initializeFrom(tracks -> ProtoUtils.indexOfTrackByUid(tracks, trackUid), null, null);
        } else if (trackIndex != null) {
            tracksKeeper.initializeFrom(tracks -> {
                if (trackIndex < tracks.size()) return trackIndex;
                else return -1;
            }, null, null);
        } else {
            tracksKeeper.initializeStart();
        }

        Integer seekTo = PlayCommandHelper.getSeekTo(obj);
        if (seekTo != null) setPosition(seekTo);
        else setPosition(0);

        loadTransforming();
    }

    synchronized void updateContext(@NotNull JsonObject obj) {
        String uri = obj.get("uri").getAsString();
        if (!context.uri().equals(uri)) {
            LOGGER.warn(String.format("Received update for the wrong context! {context: %s, newUri: %s}", context, uri));
            return;
        }

        ProtoUtils.copyOverMetadata(obj.getAsJsonObject("metadata"), state);
        tracksKeeper.updateContext(ProtoUtils.jsonToContextPages(obj.getAsJsonArray("pages")));
    }

    void skipTo(@NotNull ContextTrack track) {
        tracksKeeper.skipTo(track);
        setPosition(0);
    }

    @Nullable
    PlayableId nextPlayableDoNotSet() {
        try {
            PlayableIdWithIndex id = tracksKeeper.nextPlayableDoNotSet();
            return id == null ? null : id.id;
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.error("Failed fetching next playable.", ex);
            return null;
        }
    }

    @Nullable
    PlayableId getCurrentPlayable() {
        return tracksKeeper == null ? null : PlayableId.from(tracksKeeper.getCurrentTrack());
    }

    @NotNull
    PlayableId getCurrentPlayableOrThrow() {
        PlayableId id = getCurrentPlayable();
        if (id == null) throw new IllegalStateException();
        return id;
    }

    @NotNull
    NextPlayable nextPlayable(@NotNull Player.Configuration conf) {
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

    @NotNull
    Map<String, String> metadataFor(@NotNull PlayableId id) throws IllegalArgumentException {
        if (tracksKeeper == null) throw new IllegalStateException();

        int index = ProtoUtils.indexOfTrackByUri(tracksKeeper.tracks, id.toSpotifyUri());
        if (index == -1) {
            index = ProtoUtils.indexOfTrackByUri(tracksKeeper.queue, id.toSpotifyUri());
            if (index == -1) throw new IllegalArgumentException(id.toString());
        }

        return tracksKeeper.tracks.get(index).getMetadataMap();
    }

    /**
     * Performs the given add operation. This also makes sure that {@link TracksKeeper#tracks} is in a non-shuffled state,
     * even if {@link StateWrapper#isShufflingContext()} may return {@code true}. Context will be therefore reshuffled.
     */
    private synchronized void performAdd(@NotNull Playlist4ApiProto.Add add) {
        boolean wasShuffled = false;
        if (isShufflingContext()) {
            wasShuffled = true;
            tracksKeeper.toggleShuffle(false);
        }

        try {
            if (add.hasAddFirst() && add.getAddFirst())
                tracksKeeper.addToTracks(0, add.getItemsList());
            else if (add.hasAddLast() && add.getAddLast())
                tracksKeeper.addToTracks(tracksKeeper.length(), add.getItemsList());
            else if (add.hasFromIndex())
                tracksKeeper.addToTracks(add.getFromIndex(), add.getItemsList());
            else
                throw new IllegalArgumentException(TextFormat.shortDebugString(add));
        } finally {
            if (wasShuffled) tracksKeeper.toggleShuffle(true);
        }
    }

    /**
     * Performs the given remove operation. This also makes sure that {@link TracksKeeper#tracks} is in a non-shuffled state,
     * even if {@link StateWrapper#isShufflingContext()} may return {@code true}. Context will be therefore reshuffled.
     */
    private synchronized void performRemove(@NotNull Playlist4ApiProto.Rem rem) {
        boolean wasShuffled = false;
        if (isShufflingContext()) {
            wasShuffled = true;
            tracksKeeper.toggleShuffle(false);
        }

        try {
            if (rem.hasFromIndex() && rem.hasLength()) tracksKeeper.removeTracks(rem.getFromIndex(), rem.getLength());
            else throw new IllegalArgumentException(TextFormat.shortDebugString(rem));
        } finally {
            if (wasShuffled) tracksKeeper.toggleShuffle(true);
        }
    }

    @Override
    public void onMessage(@NotNull String uri, @NotNull Map<String, String> headers, @NotNull String[] payloads) throws IOException {
        if (uri.startsWith("hm://playlist/")) {
            PlaylistModificationInfo mod = PlaylistModificationInfo.parseFrom(BytesArrayList.streamBase64(payloads));
            String modUri = mod.getUri().toStringUtf8();
            if (context != null && Objects.equals(modUri, context.uri())) {
                for (Playlist4ApiProto.Op op : mod.getOpsList()) {
                    switch (op.getKind()) {
                        case ADD:
                            performAdd(op.getAdd());
                            break;
                        case REM:
                            performRemove(op.getRem());
                            break;
                        case MOV:
                        case UPDATE_ITEM_ATTRIBUTES:
                        case UPDATE_LIST_ATTRIBUTES:
                            LOGGER.warn("Unsupported operation: " + TextFormat.shortDebugString(op));
                            break;
                        default:
                        case KIND_UNKNOWN:
                            LOGGER.warn("Received unknown op: " + op.getKind());
                            break;
                    }
                }

                LOGGER.info(String.format("Received update for current context! {uri: %s, ops: %s}", modUri, ProtoUtils.opsKindList(mod.getOpsList())));
                updated();
            } else if (context != null && AbsSpotifyContext.isCollection(session, modUri)) {
                for (Playlist4ApiProto.Op op : mod.getOpsList()) {
                    List<String> uris = new ArrayList<>();
                    for (Playlist4ApiProto.Item item : op.getAdd().getItemsList())
                        uris.add(item.getUri());

                    if (op.getKind() == Playlist4ApiProto.Op.Kind.ADD)
                        performCollectionUpdate(uris, true);
                    else if (op.getKind() == Playlist4ApiProto.Op.Kind.REM)
                        performCollectionUpdate(uris, false);
                }

                LOGGER.info(String.format("Updated tracks in collection! {uri: %s, ops: %s}", modUri, ProtoUtils.opsKindList(mod.getOpsList())));
                updated();
            }
        } else if (context != null && uri.equals("hm://collection/collection/" + session.username() + "/json")) {
            List<String> added = null;
            List<String> removed = null;

            JsonArray items = PARSER.parse(payloads[0]).getAsJsonObject().getAsJsonArray("items");
            for (JsonElement elm : items) {
                JsonObject obj = elm.getAsJsonObject();
                String itemUri = "spotify:" + obj.get("type").getAsString() + ":" + obj.get("identifier").getAsString();
                if (obj.get("removed").getAsBoolean()) {
                    if (removed == null) removed = new ArrayList<>();
                    removed.add(itemUri);
                } else {
                    if (added == null) added = new ArrayList<>();
                    added.add(itemUri);
                }
            }

            if (added != null) performCollectionUpdate(added, true);
            if (removed != null) performCollectionUpdate(removed, false);

            LOGGER.info(String.format("Updated tracks in collection! {added: %b, removed: %b}", added != null, removed != null));
            updated();
        }
    }

    private synchronized void performCollectionUpdate(@NotNull List<String> uris, boolean inCollection) {
        for (String uri : uris)
            tracksKeeper.updateMetadataFor(uri, "collection.in_collection", String.valueOf(inCollection));
    }

    @Nullable
    public String getContextMetadata(@NotNull String key) {
        return state.getContextMetadataOrDefault(key, null);
    }

    public void setContextMetadata(@NotNull String key, @Nullable String value) {
        if (value == null) state.removeContextMetadata(key);
        else state.putContextMetadata(key, value);
    }

    @Nullable
    public String getPlaybackId() {
        return state.getPlaybackId();
    }

    @Nullable
    public String getSessionId() {
        return state.getSessionId();
    }

    public void renewSessionId() {
        state.setSessionId(generateSessionId(session.random()));
    }

    public void renewPlaybackId() {
        state.setPlaybackId(generatePlaybackId(session.random()));
    }

    public enum PreviousPlayable {
        MISSING_TRACKS, OK;

        public boolean isOk() {
            return this == OK;
        }
    }

    public enum NextPlayable {
        MISSING_TRACKS, AUTOPLAY,
        OK_PLAY, OK_PAUSE, OK_REPEAT;

        public boolean isOk() {
            return this == OK_PLAY || this == OK_PAUSE || this == OK_REPEAT;
        }
    }

    private interface TrackFinder {
        int find(@NotNull List<ContextTrack> tracks);
    }

    private static class PlayableIdWithIndex {
        private final PlayableId id;
        private final int index;

        PlayableIdWithIndex(@NotNull PlayableId id, int index) {
            this.id = id;
            this.index = index;
        }
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
            else
                state.removeContextMetadata("track_count");
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

        /**
         * Sets the current playing track index and updates the state.
         *
         * @param index The index of the track inside {@link TracksKeeper#tracks}
         * @throws IllegalStateException if the queue is playing
         */
        private void setCurrentTrackIndex(int index) {
            if (isPlayingQueue) throw new IllegalStateException();
            state.setIndex(ContextIndex.newBuilder().setTrack(index).build());
            updateState();
        }

        private void shiftCurrentTrackIndex(int delta) {
            state.getIndexBuilder().setTrack(state.getIndex().getTrack() + delta);
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

        void updateTrackDuration(int duration) {
            state.setDuration(duration);
            state.getTrackBuilder().putMetadata("duration", String.valueOf(duration));
            updateMetadataFor(getCurrentTrackIndex(), "duration", String.valueOf(duration));
        }

        private void updateTrackDuration() {
            ProvidedTrack current = getCurrentTrack();
            if (current.containsMetadata("duration"))
                state.setDuration(Long.parseLong(current.getMetadataOrThrow("duration")));
            else
                state.clearDuration();
        }

        private void updateLikeDislike() {
            if (Objects.equals(state.getContextMetadataOrDefault("like-feedback-enabled", "0"), "1")) {
                state.putContextMetadata("like-feedback-selected",
                        state.getTrack().getMetadataOrDefault("like-feedback-selected", "0"));
            } else {
                state.removeContextMetadata("like-feedback-selected");
            }

            if (Objects.equals(state.getContextMetadataOrDefault("dislike-feedback-enabled", "0"), "1")) {
                state.putContextMetadata("dislike-feedback-selected",
                        state.getTrack().getMetadataOrDefault("dislike-feedback-selected", "0"));
            } else {
                state.removeContextMetadata("dislike-feedback-selected");
            }
        }

        /**
         * Updates the currently playing track (not index), recomputes the prev/next tracks and sets the duration field.
         *
         * <b>This will also REMOVE a track from the queue if needed. Calling this twice will break the queue.</b>
         */
        private void updateState() {
            if (isPlayingQueue) state.setTrack(ProtoUtils.convertToProvidedTrack(queue.remove()));
            else state.setTrack(ProtoUtils.convertToProvidedTrack(tracks.get(getCurrentTrackIndex())));

            updateLikeDislike();

            updateTrackDuration();
            updatePrevNextTracks();
        }

        /**
         * Adds a track to the end of the queue, recomputes the prev/next tracks and sets the duration field.
         *
         * <b>Calling {@link TracksKeeper#updateState()} would break it.</b>
         *
         * @param track The track to add to queue
         */
        synchronized void addToQueue(@NotNull ContextTrack track) {
            queue.add(track.toBuilder().putMetadata("is_queued", "true").build());
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

        synchronized void updateContext(@NotNull List<ContextPage> updatedPages) {
            List<ContextTrack> updatedTracks = ProtoUtils.join(updatedPages);
            for (ContextTrack track : updatedTracks) {
                int index = ProtoUtils.indexOfTrackByUri(tracks, track.getUri());
                if (index == -1) continue;

                ContextTrack.Builder builder = tracks.get(index).toBuilder();
                ProtoUtils.copyOverMetadata(track, builder);
                tracks.set(index, builder.build());

                if (index == getCurrentTrackIndex()) {
                    ProtoUtils.copyOverMetadata(track, state.getTrackBuilder());
                    tracksKeeper.updateLikeDislike();
                }
            }
        }

        synchronized void initializeStart() throws IOException, MercuryClient.MercuryException, AbsSpotifyContext.UnsupportedContextException {
            if (!pages.nextPage()) throw new IllegalStateException();

            tracks.clear();
            tracks.addAll(pages.currentPage());

            checkComplete();
            if (!PlayableId.canPlaySomething(tracks))
                throw AbsSpotifyContext.UnsupportedContextException.cannotPlayAnything();

            if (context.isFinite() && isShufflingContext())
                shuffleEntirely();

            setCurrentTrackIndex(0);
        }

        synchronized void initializeFrom(@NotNull TrackFinder finder, @Nullable ContextTrack track, @Nullable QueueOuterClass.Queue contextQueue) throws IOException, MercuryClient.MercuryException, AbsSpotifyContext.UnsupportedContextException {
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
            if (!PlayableId.canPlaySomething(tracks))
                throw AbsSpotifyContext.UnsupportedContextException.cannotPlayAnything();

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
         * This will NOT return {@link xyz.gianlu.librespot.mercury.model.UnsupportedId}.
         *
         * @return The next {@link PlayableId} or {@code null} if there are no more tracks or if repeating the current track
         */
        @Nullable
        synchronized PlayableIdWithIndex nextPlayableDoNotSet() throws IOException, MercuryClient.MercuryException {
            if (isRepeatingTrack())
                return null;

            if (!queue.isEmpty())
                return new PlayableIdWithIndex(PlayableId.from(queue.peek()), -1);

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

            int add = 1;
            while (true) {
                ContextTrack track = tracks.get(current + add);
                if (shouldPlay(track)) break;
                else add++;
            }

            return new PlayableIdWithIndex(PlayableId.from(tracks.get(current + add)), current + add);
        }

        @NotNull
        synchronized NextPlayable nextPlayable(@NotNull Player.Configuration conf) throws IOException, MercuryClient.MercuryException {
            if (isRepeatingTrack()) {
                setRepeatingTrack(false);
                return NextPlayable.OK_REPEAT;
            }

            if (!queue.isEmpty()) {
                isPlayingQueue = true;
                updateState();

                if (!shouldPlay(tracks.get(getCurrentTrackIndex())))
                    return nextPlayable(conf);

                return NextPlayable.OK_PLAY;
            }

            isPlayingQueue = false;

            boolean play = true;
            PlayableIdWithIndex next = nextPlayableDoNotSet();
            if (next == null || next.index == -1) {
                if (!context.isFinite()) return NextPlayable.MISSING_TRACKS;

                if (isRepeatingContext()) {
                    setCurrentTrackIndex(0);
                } else {
                    if (conf.autoplayEnabled()) {
                        return NextPlayable.AUTOPLAY;
                    } else {
                        setCurrentTrackIndex(0);
                        play = false;
                    }
                }
            } else {
                setCurrentTrackIndex(next.index);
            }

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

            if (!shouldPlay(tracks.get(getCurrentTrackIndex())))
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

                PlayableId currentlyPlaying = getCurrentPlayableOrThrow();
                shuffle.shuffle(tracks, true);
                shuffleKeepIndex = ProtoUtils.indexOfTrackByUri(tracks, currentlyPlaying.toSpotifyUri());
                Collections.swap(tracks, 0, shuffleKeepIndex);
                setCurrentTrackIndex(0);

                LOGGER.trace(String.format("Shuffled context! {keepIndex: %d}", shuffleKeepIndex));
            } else {
                if (shuffle.canUnshuffle(tracks.size())) {
                    PlayableId currentlyPlaying = getCurrentPlayableOrThrow();
                    if (shuffleKeepIndex != -1) Collections.swap(tracks, 0, shuffleKeepIndex);

                    shuffle.unshuffle(tracks);
                    setCurrentTrackIndex(ProtoUtils.indexOfTrackByUri(tracks, currentlyPlaying.toSpotifyUri()));

                    LOGGER.trace("Unshuffled using Fisher-Yates.");
                } else {
                    PlayableId id = getCurrentPlayableOrThrow();

                    tracks.clear();
                    pages = PagesLoader.from(session, context.uri());
                    loadAllTracks();

                    setCurrentTrackIndex(ProtoUtils.indexOfTrackByUri(tracks, id.toSpotifyUri()));
                    LOGGER.trace("Unshuffled by reloading context.");
                }
            }
        }

        public int length() {
            return tracks.size();
        }

        /**
         * Adds tracks to the current state. {@link TracksKeeper#tracks} MUST be in a non-shuffled state.
         */
        void addToTracks(int from, @NotNull List<Playlist4ApiProto.Item> items) {
            if (!cannotLoadMore) {
                if (loadAllTracks()) {
                    LOGGER.trace("Loaded all tracks before adding new ones.");
                } else {
                    LOGGER.warn("Cannot add new tracks!");
                    return;
                }
            }

            for (int i = 0; i < items.size(); i++) {
                Playlist4ApiProto.Item item = items.get(i);
                tracks.add(i + from, ContextTrack.newBuilder()
                        .setUri(item.getUri())
                        .build());
            }

            if (!isPlayingQueue && from <= getCurrentTrackIndex())
                shiftCurrentTrackIndex(items.size());

            updatePrevNextTracks();
        }

        /**
         * Removes tracks from the current state. {@link TracksKeeper#tracks} MUST be in a non-shuffled state.
         */
        void removeTracks(int from, int length) {
            if (!cannotLoadMore) {
                if (loadAllTracks()) {
                    LOGGER.trace("Loaded all tracks before removing some.");
                } else {
                    LOGGER.warn("Cannot remove tracks!");
                    return;
                }
            }

            boolean removeCurrent = false;
            int curr = getCurrentTrackIndex();
            if (from <= curr && length + from > curr)
                removeCurrent = true;

            ContextTrack current = tracks.get(curr);
            for (int i = 0; i < length; i++)
                tracks.remove(from);

            if (!removeCurrent && from <= curr)
                shiftCurrentTrackIndex(-length);

            if (removeCurrent) {
                shiftCurrentTrackIndex(-1);

                queue.addFirst(current);
                isPlayingQueue = true;
                updateState();
            } else {
                updatePrevNextTracks();
            }
        }

        synchronized void updateMetadataFor(int index, @NotNull String key, @NotNull String value) {
            ContextTrack.Builder builder = tracks.get(index).toBuilder();
            builder.putMetadata(key, value);
            tracks.set(index, builder.build());
        }

        synchronized void updateMetadataFor(@NotNull String uri, @NotNull String key, @NotNull String value) {
            int index = ProtoUtils.indexOfTrackByUri(tracks, uri);
            if (index == -1) return;

            updateMetadataFor(index, key, value);
        }
    }
}