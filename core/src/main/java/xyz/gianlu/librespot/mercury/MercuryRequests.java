package xyz.gianlu.librespot.mercury;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.ProtocolStringList;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Mercury;
import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.common.proto.Playlist4Changes;
import xyz.gianlu.librespot.common.proto.Playlist4Content;
import xyz.gianlu.librespot.mercury.model.PlaylistId;
import xyz.gianlu.librespot.mercury.model.TrackId;

import java.util.List;

/**
 * @author Gianlu
 */
public final class MercuryRequests {
    private static final ProtoJsonMercuryRequest.JsonConverter<Playlist4Changes.SelectedListContent> SELECTED_LIST_CONTENT_JSON_CONVERTER = proto -> {
        List<Playlist4Content.Item> items = proto.getContents().getItemsList();
        JsonArray array = new JsonArray(items.size());
        for (Playlist4Content.Item item : items) array.add(item.getUri());
        return array;
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Date> DATE_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("year", proto.getYear());
        obj.addProperty("month", proto.getMonth());
        obj.addProperty("day", proto.getDay());
        return obj;
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Restriction> RESTRICTION_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("allowed", proto.getCountriesAllowed());
        obj.addProperty("forbidden", proto.getCountriesForbidden());
        obj.addProperty("type", proto.getTyp().name());
        obj.add("catalogues", makeArray(proto.getCatalogueStrList()));
        return obj;
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.SalePeriod> SALE_PERIOD_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.add("start", DATE_JSON_CONVERTER.convert(proto.getStart()));
        obj.add("end", DATE_JSON_CONVERTER.convert(proto.getEnd()));
        obj.add("restrictions", makeArray(proto.getRestrictionList(), RESTRICTION_JSON_CONVERTER));
        return obj;
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Copyright> COPYRIGHT_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("text", proto.getText());
        obj.addProperty("type", proto.getTyp().name());
        return obj;
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Image> IMAGE_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("width", proto.getWidth());
        obj.addProperty("height", proto.getHeight());
        obj.addProperty("size", proto.getSize().name());
        obj.addProperty("fileId", Utils.toBase64(proto.getFileId()));
        return obj;
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.ImageGroup> IMAGE_GROUP_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.add("images", makeArray(proto.getImageList(), IMAGE_JSON_CONVERTER));
        return obj;
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.ExternalId> EXTERNAL_ID_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", proto.getTyp());
        obj.addProperty("id", proto.getId());
        return obj;
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Biography> BIOGRAPHY_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("text", proto.getText());
        obj.add("portraits", makeArray(proto.getPortraitList(), IMAGE_JSON_CONVERTER));
        obj.add("portraitGroups", makeArray(proto.getPortraitGroupList(), IMAGE_GROUP_JSON_CONVERTER));
        return obj;
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.ActivityPeriod> ACTIVITY_PERIOD_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("startYear", proto.getStartYear());
        obj.addProperty("endYear", proto.getEndYear());
        obj.addProperty("decade", proto.getDecade());
        return obj;
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.AudioFile> AUDIO_FILE_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("fileId", Utils.toBase64(proto.getFileId()));
        obj.addProperty("format", proto.getFormat().name());
        return obj;
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Artist> ARTIST_JSON_CONVERTER = new ProtoJsonMercuryRequest.JsonConverter<Metadata.Artist>() {
        @Override
        public @NotNull JsonElement convert(Metadata.@NotNull Artist proto) {
            JsonObject obj = new JsonObject();
            obj.addProperty("gid", Utils.toBase64(proto.getGid()));
            obj.addProperty("name", proto.getName());
            obj.addProperty("popularity", proto.getPopularity());
            obj.addProperty("isPortraitAlbumCover", proto.getIsPortraitAlbumCover());
            obj.add("portraitGroup", IMAGE_GROUP_JSON_CONVERTER.convert(proto.getPortraitGroup()));
            obj.add("genres", makeArray(proto.getGenreList()));
            obj.add("restrictions", makeArray(proto.getRestrictionList(), RESTRICTION_JSON_CONVERTER));
            obj.add("externalIds", makeArray(proto.getExternalIdList(), EXTERNAL_ID_JSON_CONVERTER));
            obj.add("related", makeArray(proto.getRelatedList(), this));
            obj.add("portraits", makeArray(proto.getPortraitList(), IMAGE_JSON_CONVERTER));
            obj.add("albumGroups", makeArray(proto.getAlbumGroupList(), ALBUM_GROUP_JSON_CONVERTER));
            obj.add("singleGroups", makeArray(proto.getSingleGroupList(), ALBUM_GROUP_JSON_CONVERTER));
            obj.add("compilationGroups", makeArray(proto.getCompilationGroupList(), ALBUM_GROUP_JSON_CONVERTER));
            obj.add("appearsOnGroups", makeArray(proto.getAppearsOnGroupList(), ALBUM_GROUP_JSON_CONVERTER));
            obj.add("biographies", makeArray(proto.getBiographyList(), BIOGRAPHY_JSON_CONVERTER));
            obj.add("topTracks", makeArray(proto.getTopTrackList(), TOP_TRACKS_JSON_CONVERTER));
            obj.add("activityPeriods", makeArray(proto.getActivityPeriodList(), ACTIVITY_PERIOD_JSON_CONVERTER));
            return obj;
        }
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Album> ALBUM_JSON_CONVERTER = new ProtoJsonMercuryRequest.JsonConverter<Metadata.Album>() {
        @Override
        public @NotNull JsonElement convert(Metadata.@NotNull Album proto) {
            JsonObject obj = new JsonObject();
            obj.addProperty("gid", Utils.toBase64(proto.getGid()));
            obj.addProperty("name", proto.getName());
            obj.addProperty("popularity", proto.getPopularity());
            obj.addProperty("label", proto.getLabel());
            obj.add("genres", makeArray(proto.getGenreList()));
            obj.add("reviews", makeArray(proto.getReviewList()));
            obj.add("artists", makeArray(proto.getArtistList(), ARTIST_JSON_CONVERTER));
            obj.add("related", makeArray(proto.getRelatedList(), ALBUM_JSON_CONVERTER));
            obj.addProperty("type", proto.getTyp().name());
            obj.add("date", DATE_JSON_CONVERTER.convert(proto.getDate()));
            obj.add("discs", makeArray(proto.getDiscList(), DISC_JSON_CONVERTER));
            obj.add("salePeriods", makeArray(proto.getSalePeriodList(), SALE_PERIOD_JSON_CONVERTER));
            obj.add("restrictions", makeArray(proto.getRestrictionList(), RESTRICTION_JSON_CONVERTER));
            obj.add("copyrights", makeArray(proto.getCopyrightList(), COPYRIGHT_JSON_CONVERTER));
            obj.add("coverGroup", IMAGE_GROUP_JSON_CONVERTER.convert(proto.getCoverGroup()));
            obj.add("covers", makeArray(proto.getCoverList(), IMAGE_JSON_CONVERTER));
            obj.add("externalIds", makeArray(proto.getExternalIdList(), EXTERNAL_ID_JSON_CONVERTER));
            return obj;
        }
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.AlbumGroup> ALBUM_GROUP_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.add("albums", makeArray(proto.getAlbumList(), ALBUM_JSON_CONVERTER));
        return obj;
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Track> TRACK_JSON_CONVERTER = new ProtoJsonMercuryRequest.JsonConverter<Metadata.Track>() {
        @Override
        public @NotNull JsonElement convert(Metadata.@NotNull Track proto) {
            JsonObject obj = new JsonObject();
            obj.addProperty("gid", Utils.toBase64(proto.getGid()));
            obj.addProperty("name", proto.getName());
            obj.addProperty("number", proto.getNumber());
            obj.addProperty("discNumber", proto.getDiscNumber());
            obj.addProperty("duration", proto.getDuration());
            obj.addProperty("popularity", proto.getPopularity());
            obj.addProperty("explicit", proto.getExplicit());
            obj.add("album", ALBUM_JSON_CONVERTER.convert(proto.getAlbum()));
            obj.add("artists", makeArray(proto.getArtistList(), ARTIST_JSON_CONVERTER));
            obj.add("externalIds", makeArray(proto.getExternalIdList(), EXTERNAL_ID_JSON_CONVERTER));
            obj.add("restrictions", makeArray(proto.getRestrictionList(), RESTRICTION_JSON_CONVERTER));
            obj.add("alternatives", makeArray(proto.getAlternativeList(), this));
            obj.add("salePeriods", makeArray(proto.getSalePeriodList(), SALE_PERIOD_JSON_CONVERTER));
            obj.add("previews", makeArray(proto.getPreviewList(), AUDIO_FILE_JSON_CONVERTER));
            obj.add("files", makeArray(proto.getFileList(), AUDIO_FILE_JSON_CONVERTER));
            return obj;
        }
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.Disc> DISC_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", proto.getName());
        obj.addProperty("number", proto.getNumber());
        obj.add("tracks", makeArray(proto.getTrackList(), TRACK_JSON_CONVERTER));
        return obj;
    };
    private static final ProtoJsonMercuryRequest.JsonConverter<Metadata.TopTracks> TOP_TRACKS_JSON_CONVERTER = proto -> {
        JsonObject obj = new JsonObject();
        obj.addProperty("country", proto.getCountry());
        obj.add("tracks", makeArray(proto.getTrackList(), TRACK_JSON_CONVERTER));
        return obj;
    };


    private MercuryRequests() {
    }

    @NotNull
    private static JsonArray makeArray(@NotNull ProtocolStringList list) {
        JsonArray array = new JsonArray(list.size());
        for (String item : list) array.add(item);
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
}
