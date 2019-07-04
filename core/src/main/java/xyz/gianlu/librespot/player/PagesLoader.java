package xyz.gianlu.librespot.player;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import spotify.player.proto.ContextPageOuterClass.ContextPage;
import xyz.gianlu.librespot.common.ProtoUtils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import static spotify.player.proto.ContextOuterClass.Context;
import static spotify.player.proto.ContextTrackOuterClass.ContextTrack;

/**
 * @author Gianlu
 */
public final class PagesLoader {
    private static final JsonParser PARSER = new JsonParser();
    private final List<ContextPage> pages;
    private final Session session;
    private int currentPage = 0;

    private PagesLoader(@NotNull Session session) {
        this.session = session;
        this.pages = new ArrayList<>();
    }

    @NotNull
    public static PagesLoader from(@NotNull Session session, @NotNull Context context) {
        List<ContextPage> pages = context.getPagesList();
        if (pages.isEmpty()) throw new UnsupportedOperationException("There are no pages here!"); // TODO

        PagesLoader loader = new PagesLoader(session);
        loader.pages.addAll(pages);
        return loader;
    }

    @NotNull
    private List<ContextTrack> fetchTracks(@NotNull String url) throws IOException {
        MercuryClient.Response resp = session.mercury().sendSync(RawMercuryRequest.newBuilder()
                .setUri(url).setMethod("GET").build());

        JsonObject obj = PARSER.parse(new InputStreamReader(resp.payload.stream())).getAsJsonObject();
        return ProtoUtils.jsonToContextTracks(obj.getAsJsonArray("tracks"));
    }

    @NotNull
    private List<ContextTrack> resolvePage(@NotNull ContextPage page) throws IOException {
        if (page.getTracksCount() > 0) {
            return page.getTracksList();
        } else {
            if (page.hasPageUrl()) {
                return fetchTracks(page.getPageUrl());
            } else if (page.hasLoading() && page.getLoading()) {
                throw new UnsupportedOperationException("What does loading even mean?");
            } else {
                throw new IllegalStateException("Cannot load page, not enough information!");
            }
        }
    }

    @NotNull
    private List<ContextTrack> getPage(int index) throws IOException, IllegalStateException {
        if (index < pages.size()) {
            ContextPage page = pages.get(index);
            List<ContextTrack> tracks = resolvePage(page);
            pages.set(index, page.toBuilder().clearPageUrl().clearTracks().addAllTracks(tracks).build());
            return tracks;
        } else {
            if (index > pages.size()) throw new IndexOutOfBoundsException();

            ContextPage prev = pages.get(index - 1);
            if (!prev.hasNextPageUrl()) throw new IllegalStateException();

            String nextPageUrl = prev.getNextPageUrl();
            pages.set(index - 1, prev.toBuilder().clearNextPageUrl().build());

            List<ContextTrack> tracks = fetchTracks(nextPageUrl);
            pages.add(ContextPage.newBuilder()
                    .addAllTracks(tracks)
                    .build());

            return tracks;
        }
    }

    @NotNull
    public List<ContextTrack> currentPage() throws IOException {
        return getPage(currentPage);
    }

    public boolean nextPage() throws IOException {
        try {
            getPage(++currentPage);
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }
}
