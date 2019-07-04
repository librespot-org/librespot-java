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
    private List<ContextTrack> resolvePage(@NotNull ContextPage page) throws IOException {
        if (page.getTracksCount() > 0) {
            return page.getTracksList();
        } else {
            if (page.hasPageUrl()) {
                MercuryClient.Response resp = session.mercury().sendSync(RawMercuryRequest.newBuilder()
                        .setUri(page.getPageUrl()).setMethod("GET").build());

                JsonObject obj = PARSER.parse(new InputStreamReader(resp.payload.stream())).getAsJsonObject();
                return ProtoUtils.jsonToContextTracks(obj.getAsJsonArray("tracks"));
            } else if (page.hasLoading() && page.getLoading()) {
                throw new UnsupportedOperationException("What does loading even mean?");
            } else {
                throw new IllegalStateException("Cannot load page, not enough information!");
            }
        }
    }

    @NotNull
    public List<ContextTrack> getPage(int index) throws IOException {
        if (index < pages.size()) {
            ContextPage page = pages.get(index);
            return resolvePage(page);
        } else {
            if (index > pages.size()) throw new IndexOutOfBoundsException();

            throw new UnsupportedOperationException(); // TODO: Try to load it by looking at the previous page
        }
    }

    @NotNull
    public List<ContextTrack> currentPage() throws IOException {
        return getPage(currentPage);
    }

    @NotNull
    public List<ContextTrack> nextPage() throws IOException {
        return getPage(++currentPage);
    }
}
