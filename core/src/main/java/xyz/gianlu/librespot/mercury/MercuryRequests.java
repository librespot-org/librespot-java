package xyz.gianlu.librespot.mercury;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.ProtocolMessageEnum;
import com.google.protobuf.ProtocolStringList;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.*;
import xyz.gianlu.librespot.mercury.model.*;
import xyz.gianlu.librespot.player.remote.Remote3Page;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Gianlu
 */
public final class MercuryRequests {
    public static final ProtoJsonMercuryRequest.JsonConverter<Playlist4Changes.SelectedListContent> SELECTED_LIST_CONTENT_JSON_CONVERTER = proto -> {
        List<Playlist4Content.Item> items = proto.getContents().getItemsList();
        JsonArray array = new JsonArray(items.size());
        for (Playlist4Content.Item item : items) array.add(item.getUri());
        return array;
    };
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Date> DATE_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("year", proto.getYear());
        obj.addProperty("month", proto.getMonth());
        obj.addProperty("day", proto.getDay());
        return obj;
    };
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Restriction> RESTRICTION_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("allowed", proto.getCountriesAllowed());
        obj.addProperty("forbidden", proto.getCountriesForbidden());
        obj.addProperty("type", proto.getType().name());
        putArray(obj, "catalogueStrings", proto.getCatalogueStrList());
        putArray(obj, "catalogues", proto.getCatalogueList());
        return obj;
    };
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Copyright> COPYRIGHT_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("text", proto.getText());
        obj.addProperty("type", proto.getType().name());
        return obj;
    };
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Image> IMAGE_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("width", proto.getWidth());
        obj.addProperty("height", proto.getHeight());
        obj.addProperty("size", proto.getSize().name());
        obj.addProperty("fileId", Utils.toBase64(proto.getFileId()));
        return obj;
    };
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.ExternalId> EXTERNAL_ID_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", proto.getType());
        obj.addProperty("id", proto.getId());
        return obj;
    };
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.ActivityPeriod> ACTIVITY_PERIOD_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("startYear", proto.getStartYear());
        obj.addProperty("endYear", proto.getEndYear());
        obj.addProperty("decade", proto.getDecade());
        return obj;
    };
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.AudioFile> AUDIO_FILE_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("fileId", Utils.toBase64(proto.getFileId()));
        obj.addProperty("format", proto.getFormat().name());
        return obj;
    };
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.VideoFile> VIDEO_FILE_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("fileId", Utils.toBase64(proto.getFileId()));
        return obj;
    };
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Artist> ARTIST_JSON_CONVERTER;
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Episode> EPISODE_JSON_CONVERTER;
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Show> SHOW_JSON_CONVERTER;
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Album> ALBUM_JSON_CONVERTER;
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.AlbumGroup> ALBUM_GROUP_JSON_CONVERTER;
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Track> TRACK_JSON_CONVERTER;
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Disc> DISC_JSON_CONVERTER;
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.TopTracks> TOP_TRACKS_JSON_CONVERTER;
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.SalePeriod> SALE_PERIOD_JSON_CONVERTER;
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Availability> AVAILABILITY_JSON_CONVERTER;
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.ImageGroup> IMAGE_GROUP_JSON_CONVERTER;
    public static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Biography> BIOGRAPHY_JSON_CONVERTER;
    private static final String KEYMASTER_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd";

    static {
        AVAILABILITY_JSON_CONVERTER = proto -> {
            JsonObject obj = new JsonObject();
            obj.add("start", DATE_JSON_CONVERTER.convert(proto.getStart()));
            putArray(obj, "catalogueStrings", proto.getCatalogueStrList());
            return obj;
        };
        SALE_PERIOD_JSON_CONVERTER = proto -> {
            JsonObject obj = new JsonObject();
            obj.add("start", DATE_JSON_CONVERTER.convert(proto.getStart()));
            obj.add("end", DATE_JSON_CONVERTER.convert(proto.getEnd()));
            putArray(obj, "restrictions", proto.getRestrictionList(), RESTRICTION_JSON_CONVERTER);
            return obj;
        };
        IMAGE_GROUP_JSON_CONVERTER = proto -> {
            JsonObject obj = new JsonObject();
            putArray(obj, "images", proto.getImageList(), IMAGE_JSON_CONVERTER);
            return obj;
        };
        BIOGRAPHY_JSON_CONVERTER = proto -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("text", proto.getText());
            putArray(obj, "portraits", proto.getPortraitList(), IMAGE_JSON_CONVERTER);
            putArray(obj, "portraitGroups", proto.getPortraitGroupList(), IMAGE_GROUP_JSON_CONVERTER);
            return obj;
        };
        ARTIST_JSON_CONVERTER = new ProtoJsonMercuryRequest.JsonConverter<Metadata.Artist>() {
            @Override
            public @NotNull JsonElement convert(Metadata.@NotNull Artist proto) {
                JsonObject obj = new JsonObject();
                obj.addProperty("gid", Utils.bytesToHex(proto.getGid()));
                obj.addProperty("name", proto.getName());
                obj.addProperty("popularity", proto.getPopularity());
                obj.addProperty("isPortraitAlbumCover", proto.getIsPortraitAlbumCover());
                obj.add("portraitGroup", IMAGE_GROUP_JSON_CONVERTER.convert(proto.getPortraitGroup()));
                putArray(obj, "genres", proto.getGenreList());
                putArray(obj, "restrictions", proto.getRestrictionList(), RESTRICTION_JSON_CONVERTER);
                putArray(obj, "externalIds", proto.getExternalIdList(), EXTERNAL_ID_JSON_CONVERTER);
                putArray(obj, "related", proto.getRelatedList(), this);
                putArray(obj, "portraits", proto.getPortraitList(), IMAGE_JSON_CONVERTER);
                putArray(obj, "albumGroups", proto.getAlbumGroupList(), ALBUM_GROUP_JSON_CONVERTER);
                putArray(obj, "singleGroups", proto.getSingleGroupList(), ALBUM_GROUP_JSON_CONVERTER);
                putArray(obj, "compilationGroups", proto.getCompilationGroupList(), ALBUM_GROUP_JSON_CONVERTER);
                putArray(obj, "appearsOnGroups", proto.getAppearsOnGroupList(), ALBUM_GROUP_JSON_CONVERTER);
                putArray(obj, "biographies", proto.getBiographyList(), BIOGRAPHY_JSON_CONVERTER);
                putArray(obj, "topTracks", proto.getTopTrackList(), TOP_TRACKS_JSON_CONVERTER);
                putArray(obj, "activityPeriods", proto.getActivityPeriodList(), ACTIVITY_PERIOD_JSON_CONVERTER);
                putArray(obj, "availabilities", proto.getAvailabilityList(), AVAILABILITY_JSON_CONVERTER);
                return obj;
            }
        };
        ALBUM_JSON_CONVERTER = new ProtoJsonMercuryRequest.JsonConverter<Metadata.Album>() {
            @Override
            public @NotNull JsonElement convert(Metadata.@NotNull Album proto) {
                JsonObject obj = new JsonObject();
                obj.addProperty("gid", Utils.bytesToHex(proto.getGid()));
                obj.addProperty("name", proto.getName());
                obj.addProperty("popularity", proto.getPopularity());
                obj.addProperty("label", proto.getLabel());
                putArray(obj, "genres", proto.getGenreList());
                putArray(obj, "reviews", proto.getReviewList());
                putArray(obj, "artists", proto.getArtistList(), ARTIST_JSON_CONVERTER);
                putArray(obj, "related", proto.getRelatedList(), ALBUM_JSON_CONVERTER);
                obj.addProperty("type", proto.getType().name());
                obj.add("date", DATE_JSON_CONVERTER.convert(proto.getDate()));
                putArray(obj, "discs", proto.getDiscList(), DISC_JSON_CONVERTER);
                putArray(obj, "salePeriods", proto.getSalePeriodList(), SALE_PERIOD_JSON_CONVERTER);
                putArray(obj, "restrictions", proto.getRestrictionList(), RESTRICTION_JSON_CONVERTER);
                putArray(obj, "copyrights", proto.getCopyrightList(), COPYRIGHT_JSON_CONVERTER);
                obj.add("coverGroup", IMAGE_GROUP_JSON_CONVERTER.convert(proto.getCoverGroup()));
                putArray(obj, "covers", proto.getCoverList(), IMAGE_JSON_CONVERTER);
                putArray(obj, "externalIds", proto.getExternalIdList(), EXTERNAL_ID_JSON_CONVERTER);
                putArray(obj, "availabilities", proto.getAvailabilityList(), AVAILABILITY_JSON_CONVERTER);
                return obj;
            }
        };
        ALBUM_GROUP_JSON_CONVERTER = proto -> {
            JsonObject obj = new JsonObject();
            putArray(obj, "albums", proto.getAlbumList(), ALBUM_JSON_CONVERTER);
            return obj;
        };
        TRACK_JSON_CONVERTER = new ProtoJsonMercuryRequest.JsonConverter<Metadata.Track>() {
            @Override
            public @NotNull JsonElement convert(Metadata.@NotNull Track proto) {
                JsonObject obj = new JsonObject();
                obj.addProperty("gid", Utils.bytesToHex(proto.getGid()));
                obj.addProperty("name", proto.getName());
                obj.addProperty("number", proto.getNumber());
                obj.addProperty("discNumber", proto.getDiscNumber());
                obj.addProperty("duration", proto.getDuration());
                obj.addProperty("popularity", proto.getPopularity());
                obj.addProperty("explicit", proto.getExplicit());
                obj.add("album", ALBUM_JSON_CONVERTER.convert(proto.getAlbum()));
                putArray(obj, "artists", proto.getArtistList(), ARTIST_JSON_CONVERTER);
                putArray(obj, "externalIds", proto.getExternalIdList(), EXTERNAL_ID_JSON_CONVERTER);
                putArray(obj, "restrictions", proto.getRestrictionList(), RESTRICTION_JSON_CONVERTER);
                putArray(obj, "alternatives", proto.getAlternativeList(), this);
                putArray(obj, "salePeriods", proto.getSalePeriodList(), SALE_PERIOD_JSON_CONVERTER);
                putArray(obj, "previews", proto.getPreviewList(), AUDIO_FILE_JSON_CONVERTER);
                putArray(obj, "files", proto.getFileList(), AUDIO_FILE_JSON_CONVERTER);
                putArray(obj, "availabilities", proto.getAvailabilityList(), AVAILABILITY_JSON_CONVERTER);
                return obj;
            }
        };
        EPISODE_JSON_CONVERTER = new ProtoJsonMercuryRequest.JsonConverter<Metadata.Episode>() {
            @Override
            public @NotNull JsonElement convert(Metadata.@NotNull Episode proto) {
                JsonObject obj = new JsonObject();
                obj.addProperty("gid", Utils.bytesToHex(proto.getGid()));
                putArray(obj, "audio", proto.getAudioList(), AUDIO_FILE_JSON_CONVERTER);
                obj.addProperty("description", proto.getDescription());
                obj.add("publishTime", DATE_JSON_CONVERTER.convert(proto.getPublishTime()));
                obj.addProperty("language", proto.getLanguage());
                putArray(obj, "keywords", proto.getKeywordList());
                obj.addProperty("allowBackgroundPlayback", proto.getAllowBackgroundPlayback());
                obj.addProperty("externalUrl", proto.getExternalUrl());
                obj.addProperty("name", proto.getName());
                obj.addProperty("number", proto.getNumber());
                obj.addProperty("duration", proto.getDuration());
                obj.addProperty("explicit", proto.getExplicit());
                obj.add("freezeFrame", IMAGE_GROUP_JSON_CONVERTER.convert(proto.getFreezeFrame()));
                obj.add("coverImage", IMAGE_GROUP_JSON_CONVERTER.convert(proto.getCoverImage()));
                putArray(obj, "restrictions", proto.getRestrictionList(), RESTRICTION_JSON_CONVERTER);
                putArray(obj, "audioPreviews", proto.getAudioPreviewList(), AUDIO_FILE_JSON_CONVERTER);
                putArray(obj, "availabilities", proto.getAvailabilityList(), AVAILABILITY_JSON_CONVERTER);
                obj.add("show", SHOW_JSON_CONVERTER.convert(proto.getShow()));
                putArray(obj, "videos", proto.getVideoList(), VIDEO_FILE_JSON_CONVERTER);
                putArray(obj, "videoPreviews", proto.getVideoPreviewList(), VIDEO_FILE_JSON_CONVERTER);
                return obj;
            }
        };
        SHOW_JSON_CONVERTER = new ProtoJsonMercuryRequest.JsonConverter<Metadata.Show>() {
            @Override
            public @NotNull JsonElement convert(Metadata.@NotNull Show proto) {
                JsonObject obj = new JsonObject();
                obj.addProperty("gid", Utils.bytesToHex(proto.getGid()));
                obj.addProperty("description", proto.getDescription());
                obj.addProperty("language", proto.getLanguage());
                putArray(obj, "keywords", proto.getKeywordList());
                obj.addProperty("name", proto.getName());
                obj.addProperty("explicit", proto.getExplicit());
                obj.add("coverImage", IMAGE_GROUP_JSON_CONVERTER.convert(proto.getCoverImage()));
                obj.addProperty("publisher", proto.getPublisher());
                putArray(obj, "restrictions", proto.getRestrictionList(), RESTRICTION_JSON_CONVERTER);
                putArray(obj, "episodes", proto.getEpisodeList(), EPISODE_JSON_CONVERTER);
                putArray(obj, "copyrights", proto.getCopyrightList(), COPYRIGHT_JSON_CONVERTER);
                putArray(obj, "availabilities", proto.getAvailabilityList(), AVAILABILITY_JSON_CONVERTER);
                obj.addProperty("mediaType", proto.getMediaType().name());
                obj.addProperty("consumptionOrder", proto.getConsumptionOrder().name());
                return obj;
            }
        };
        DISC_JSON_CONVERTER = proto -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", proto.getName());
            obj.addProperty("number", proto.getNumber());
            putArray(obj, "tracks", proto.getTrackList(), TRACK_JSON_CONVERTER);
            return obj;
        };
        TOP_TRACKS_JSON_CONVERTER = proto -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("country", proto.getCountry());
            putArray(obj, "tracks", proto.getTrackList(), TRACK_JSON_CONVERTER);
            return obj;
        };
    }

    private MercuryRequests() {
    }

    private static <P extends AbstractMessage> void putArray(@NotNull JsonObject dest, @NotNull String key, @NotNull List<P> list, @NotNull ProtoJsonMercuryRequest.JsonConverter<P> converter) {
        if (!list.isEmpty()) dest.add(key, makeArray(list, converter));
    }

    private static void putArray(@NotNull JsonObject dest, @NotNull String key, @NotNull ProtocolStringList list) {
        if (!list.isEmpty()) dest.add(key, makeArray(list));
    }

    private static void putArray(@NotNull JsonObject dest, @NotNull String key, @NotNull List<? extends Enum<? extends ProtocolMessageEnum>> list) {
        if (!list.isEmpty()) dest.add(key, makeArray(list));
    }

    @NotNull
    private static JsonArray makeArray(@NotNull ProtocolStringList list) {
        JsonArray array = new JsonArray(list.size());
        for (String item : list) array.add(item);
        return array;
    }

    @NotNull
    private static JsonArray makeArray(@NotNull List<? extends Enum<? extends ProtocolMessageEnum>> list) {
        JsonArray array = new JsonArray(list.size());
        for (Enum item : list) array.add(item.name());
        return array;
    }

    @NotNull
    private static <P extends AbstractMessage> JsonArray makeArray(@NotNull List<P> list, @NotNull ProtoJsonMercuryRequest.JsonConverter<P> converter) {
        JsonArray array = new JsonArray(list.size());
        for (P proto : list) array.add(converter.convert(proto));
        return array;
    }

    @NotNull
    public static ProtoJsonMercuryRequest<Playlist4Changes.SelectedListContent> getRootPlaylists(@NotNull String username) {
        return new ProtoJsonMercuryRequest<>(RawMercuryRequest.get(String.format("hm://playlist/user/%s/rootlist", username)),
                Playlist4Changes.SelectedListContent.parser(), SELECTED_LIST_CONTENT_JSON_CONVERTER);
    }

    @NotNull
    public static ProtoJsonMercuryRequest<Playlist4Changes.SelectedListContent> getPlaylist(@NotNull PlaylistId id) {
        return new ProtoJsonMercuryRequest<>(RawMercuryRequest.get(id.toMercuryUri()),
                Playlist4Changes.SelectedListContent.parser(), SELECTED_LIST_CONTENT_JSON_CONVERTER);
    }

    @NotNull
    public static ProtoJsonMercuryRequest<Metadata.Track> getTrack(@NotNull TrackId id) {
        return new ProtoJsonMercuryRequest<>(RawMercuryRequest.get(id.toMercuryUri()), Metadata.Track.parser(), TRACK_JSON_CONVERTER);
    }

    @NotNull
    public static ProtoJsonMercuryRequest<Metadata.Artist> getArtist(@NotNull ArtistId id) {
        return new ProtoJsonMercuryRequest<>(RawMercuryRequest.get(id.toMercuryUri()), Metadata.Artist.parser(), ARTIST_JSON_CONVERTER);
    }

    @NotNull
    public static ProtoJsonMercuryRequest<Metadata.Album> getAlbum(@NotNull AlbumId id) {
        return new ProtoJsonMercuryRequest<>(RawMercuryRequest.get(id.toMercuryUri()), Metadata.Album.parser(), ALBUM_JSON_CONVERTER);
    }

    @NotNull
    public static ProtoJsonMercuryRequest<Metadata.Episode> getEpisode(@NotNull EpisodeId id) {
        return new ProtoJsonMercuryRequest<>(RawMercuryRequest.get(id.toMercuryUri()), Metadata.Episode.parser(), EPISODE_JSON_CONVERTER);
    }

    @NotNull
    public static ProtoJsonMercuryRequest<Metadata.Show> getShow(@NotNull ShowId id) {
        return new ProtoJsonMercuryRequest<>(RawMercuryRequest.get(id.toMercuryUri()), Metadata.Show.parser(), SHOW_JSON_CONVERTER);
    }

    @NotNull
    public static ProtobufMercuryRequest<Mercury.MercuryMultiGetReply> multiGet(@NotNull String uri, Mercury.MercuryRequest... subs) {
        RawMercuryRequest.Builder request = RawMercuryRequest.newBuilder()
                .setContentType("vnd.spotify/mercury-mget-request")
                .setMethod("GET")
                .setUri(uri);

        Mercury.MercuryMultiGetRequest.Builder multi = Mercury.MercuryMultiGetRequest.newBuilder();
        for (Mercury.MercuryRequest sub : subs)
            multi.addRequest(sub);

        request.addProtobufPayload(multi.build());
        return new ProtobufMercuryRequest<>(request.build(), Mercury.MercuryMultiGetReply.parser());
    }

    @NotNull
    public static JsonMercuryRequest<StationsWrapper> getStationFor(@NotNull String context) {
        return new JsonMercuryRequest<>(RawMercuryRequest.get("hm://radio-apollo/v3/stations/" + context), StationsWrapper.class);
    }

    @NotNull
    public static JsonMercuryRequest<ResolvedContextWrapper> resolveContext(@NotNull String uri) {
        return new JsonMercuryRequest<>(RawMercuryRequest.get(String.format("hm://context-resolve/v1/%s", uri)), ResolvedContextWrapper.class);
    }

    @NotNull
    public static JsonMercuryRequest<KeymasterToken> requestToken(@NotNull String deviceId, @NotNull String scope) {
        return new JsonMercuryRequest<>(RawMercuryRequest.get(String.format("hm://keymaster/token/authenticated?scope=%s&client_id=%s&device_id=%s", scope, KEYMASTER_CLIENT_ID, deviceId)), KeymasterToken.class);
    }

    @NotNull
    private static String getAsString(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement elm = obj.get(key);
        if (elm == null) throw new NullPointerException("Unexpected null value for " + key);
        else return elm.getAsString();
    }

    @Contract("_, _, !null -> !null")
    private static String getAsString(@NotNull JsonObject obj, @NotNull String key, @Nullable String fallback) {
        JsonElement elm = obj.get(key);
        if (elm == null) return fallback;
        else return elm.getAsString();
    }

    public static final class StationsWrapper extends JsonWrapper {

        public StationsWrapper(@NotNull JsonElement elm) {
            super(elm);
        }

        @NotNull
        public String uri() {
            return getAsString(obj(), "uri");
        }

        @NotNull
        public List<Spirc.TrackRef> tracks() {
            JsonArray array = obj().getAsJsonArray("tracks");
            List<Spirc.TrackRef> list = new ArrayList<>(array.size());
            for (JsonElement elm : array) {
                JsonObject obj = elm.getAsJsonObject();
                String uri = getAsString(obj, "uri");
                list.add(Spirc.TrackRef.newBuilder()
                        .setUri(uri)
                        .setGid(ByteString.copyFrom(TrackId.fromUri(uri).getGid()))
                        .build());
            }

            return list;
        }
    }

    public static final class ResolvedContextWrapper extends JsonWrapper {

        public ResolvedContextWrapper(@NotNull JsonElement elm) {
            super(elm);
        }

        @NotNull
        public List<Remote3Page> pages() {
            List<Remote3Page> list = Remote3Page.opt(obj().getAsJsonArray("pages"));
            if (list == null) throw new IllegalArgumentException("Invalid context!");
            return list;
        }

        @NotNull
        public JsonObject metadata() {
            return obj().getAsJsonObject("metadata");
        }

        @NotNull
        public String uri() {
            return getAsString(obj(), "uri");
        }

        @NotNull
        public String url() {
            return getAsString(obj(), "url");
        }
    }

    public static final class KeymasterToken extends JsonWrapper {

        public KeymasterToken(@NotNull JsonElement elm) {
            super(elm);
        }
    }
}
