package xyz.gianlu.librespot.player;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spotify.context.ContextOuterClass.Context;
import com.spotify.context.ContextTrackOuterClass.ContextTrack;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.ProtoUtils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import static com.spotify.context.ContextPageOuterClass.ContextPage;

/**
 * @author Gianlu
 */
public final class PagesLoader {
    private final List<ContextPage> pages;
    private final Session session;
    private String resolveUrl = null;
    private int currentPage = -1;

    private PagesLoader(@NotNull Session session) {
        this.session = session;
        this.pages = new ArrayList<>();
    }

    @NotNull
    public static PagesLoader from(@NotNull Session session, @NotNull String context) {
        PagesLoader loader = new PagesLoader(session);
        loader.resolveUrl = context;
        return loader;
    }

    @NotNull
    public static PagesLoader from(@NotNull Session session, @NotNull Context context) {
        List<ContextPage> pages = context.getPagesList();
        if (pages.isEmpty()) return from(session, context.getUri());

        PagesLoader loader = new PagesLoader(session);
        loader.pages.addAll(pages);
        return loader;
    }

    @NotNull
    private List<ContextTrack> fetchTracks(@NotNull String url) throws IOException {
        MercuryClient.Response resp = session.mercury().sendSync(RawMercuryRequest.newBuilder()
                .setUri(url).setMethod("GET").build());

        JsonObject obj = JsonParser.parseReader(new InputStreamReader(resp.payload.stream())).getAsJsonObject();
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
    private List<ContextTrack> getPage(int index) throws IOException, IllegalStateException, MercuryClient.MercuryException {
        if (index == -1) throw new IllegalStateException("You must call nextPage() first!");

        if (index == 0 && pages.isEmpty() && resolveUrl != null)
            pages.addAll(session.mercury().sendSync(MercuryRequests.resolveContext(resolveUrl)).pages());

        resolveUrl = null;

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
    List<ContextTrack> currentPage() throws IOException, MercuryClient.MercuryException {
        return getPage(currentPage);
    }

    boolean nextPage() throws IOException, MercuryClient.MercuryException {
        try {
            getPage(currentPage + 1);
            currentPage++;
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    void putFirstPages(@NotNull List<ContextPage> pages) {
        if (currentPage != -1 || !this.pages.isEmpty()) throw new IllegalStateException();
        this.pages.addAll(pages);
    }

    void putFirstPage(@NotNull List<ContextTrack> tracks) {
        if (currentPage != -1 || !pages.isEmpty()) throw new IllegalStateException();
        pages.add(ContextPage.newBuilder().addAllTracks(tracks).build());
    }
}
