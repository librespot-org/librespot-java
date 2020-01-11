package xyz.gianlu.librespot.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * @author Gianlu
 */
public class SearchManager {
    private static final String BASE_URL = "hm://searchview/km/v4/search/";
    private final Session session;

    public SearchManager(@NotNull Session session) {
        this.session = session;
    }

    @NotNull
    public JsonObject request(@NotNull SearchRequest req) throws IOException {
        if (req.username.isEmpty()) req.username = session.username();
        if (req.country.isEmpty()) req.country = session.countryCode();
        if (req.locale.isEmpty()) req.locale = session.conf().preferredLocale();

        MercuryClient.Response resp = session.mercury().sendSync(RawMercuryRequest.newBuilder()
                .setMethod("GET").setUri(req.buildUrl()).build());

        if (resp.statusCode != 200) throw new SearchException(resp.statusCode);

        return JsonParser.parseReader(new InputStreamReader(resp.payload.stream())).getAsJsonObject();
    }

    public static class SearchException extends IOException {

        SearchException(int statusCode) {
            super(String.format("Search failed with code %d.", statusCode));
        }
    }

    public static class SearchRequest {
        private final String query;
        private int limit = 10;
        private String imageSize = "";
        private String catalogue = "";
        private String country = "";
        private String locale = "";
        private String username = "";

        public SearchRequest(@NotNull String query) {
            this.query = query.trim();

            if (this.query.isEmpty())
                throw new IllegalArgumentException();
        }

        @NotNull
        private String buildUrl() throws UnsupportedEncodingException {
            String url = BASE_URL + URLEncoder.encode(query, "UTF-8");
            url += "?entityVersion=2";
            url += "&limit=" + limit;
            url += "&imageSize=" + URLEncoder.encode(imageSize, "UTF-8");
            url += "&catalogue=" + URLEncoder.encode(catalogue, "UTF-8");
            url += "&country=" + URLEncoder.encode(country, "UTF-8");
            url += "&locale=" + URLEncoder.encode(locale, "UTF-8");
            url += "&username=" + URLEncoder.encode(username, "UTF-8");
            return url;
        }

        @NotNull
        public SearchRequest imageSize(@NotNull String imageSize) {
            this.imageSize = imageSize;
            return this;
        }

        @NotNull
        public SearchRequest catalogue(@NotNull String catalogue) {
            this.catalogue = catalogue;
            return this;
        }

        @NotNull
        public SearchRequest country(@NotNull String country) {
            this.country = country;
            return this;
        }

        @NotNull
        public SearchRequest locale(@NotNull String locale) {
            this.locale = locale;
            return this;
        }

        @NotNull
        public SearchRequest username(@NotNull String username) {
            this.username = username;
            return this;
        }

        @NotNull
        public SearchRequest limit(int limit) {
            this.limit = limit;
            return this;
        }
    }
}
