package xyz.gianlu.librespot.player.providers;

import com.google.gson.JsonParser;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import spotify.player.proto.ContextPageOuterClass.ContextPage;
import xyz.gianlu.librespot.common.ProtoUtils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;

import java.io.IOException;
import java.io.InputStreamReader;

/**
 * @author Gianlu
 */
public class StationProvider implements ContentProvider {
    private static final Logger LOGGER = Logger.getLogger(StationProvider.class);
    private static final JsonParser PARSER = new JsonParser();
    private final String context;
    private final MercuryClient mercury;
    private String nextPageUrl;

    public StationProvider(@NotNull String context, @NotNull Session session) {
        this.context = context;
        this.mercury = session.mercury();
    }

    @NotNull
    private ContextPage loadPage() throws IOException {
        MercuryClient.Response resp = mercury.sendSync(RawMercuryRequest.newBuilder()
                .setUri(nextPageUrl).setMethod("GET").build());

        return ProtoUtils.jsonToContextPage(PARSER.parse(new InputStreamReader(resp.payload.stream())).getAsJsonObject());
    }

    private void resolveContext() throws IOException, MercuryClient.MercuryException {
        MercuryRequests.ResolvedContextWrapper json = mercury.sendSync(MercuryRequests.resolveContext(context));
        knowsNextPageUrl(json.pages().get(0).getNextPageUrl());
    }

    private void knowsNextPageUrl(@NotNull String url) {
        if (nextPageUrl == null) {
            nextPageUrl = url;
            LOGGER.trace("Loaded next page url: " + nextPageUrl);
        }
    }

    public void updateNextPageUrl(@NotNull String url) {
        nextPageUrl = url;
    }

    @Override
    public @NotNull ContextPage nextPage() throws IOException, MercuryClient.MercuryException {
        if (nextPageUrl == null) resolveContext();

        ContextPage page = loadPage();
        nextPageUrl = null;
        if (page.hasNextPageUrl()) knowsNextPageUrl(page.getNextPageUrl());
        return page;
    }
}
