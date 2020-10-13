package xyz.gianlu.librespot.api.handlers;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.FileUtils;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.api.SessionWrapper;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TokenProvider;

public final class WebApiHandler extends AbsSessionHandler {
    private static final String[] API_TOKENS_ALL = new String[]{"ugc-image-upload", "playlist-read-collaborative", "playlist-modify-private", "playlist-modify-public", "playlist-read-private", "user-read-playback-position", "user-read-recently-played", "user-top-read", "user-modify-playback-state", "user-read-currently-playing", "user-read-playback-state", "user-read-private", "user-read-email", "user-library-modify", "user-library-read", "user-follow-modify", "user-follow-read", "streaming", "app-remote-control"};
    private static final HttpUrl BASE_API_URL = HttpUrl.get("https://api.spotify.com");
    private static final HttpString HEADER_X_SCOPE = HttpString.tryFromString("X-Spotify-Scope");

    public WebApiHandler(@NotNull SessionWrapper wrapper) {
        super(wrapper);
    }

    @Override
    protected void handleRequest(@NotNull HttpServerExchange exchange, @NotNull Session session) throws Exception {
        exchange.startBlocking();
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        String body = FileUtils.readFile(exchange.getInputStream());
        HeaderValues contentType = exchange.getRequestHeaders().get(Headers.CONTENT_TYPE);

        String[] scopes = API_TOKENS_ALL;
        if (exchange.getRequestHeaders().contains(HEADER_X_SCOPE))
            scopes = exchange.getRequestHeaders().get(HEADER_X_SCOPE).toArray(new String[0]);

        TokenProvider.StoredToken token = session.tokens().getToken(scopes);

        HttpUrl.Builder url = BASE_API_URL.newBuilder()
                .addPathSegments(exchange.getRelativePath().substring(1))
                .query(exchange.getQueryString());

        Request.Builder req = new Request.Builder()
                .url(url.build())
                .addHeader("Authorization", "Bearer " + token.accessToken);

        String method = exchange.getRequestMethod().toString();
        if (!body.isEmpty() && contentType != null)
            req.method(method, RequestBody.create(body, MediaType.get(contentType.getFirst())));
        else
            req.method(method, null);

        try (Response resp = session.client().newCall(req.build()).execute()) {
            exchange.setStatusCode(resp.code());

            String respContentType = resp.header("Content-Type");
            if (respContentType != null) exchange.getResponseHeaders().add(Headers.CONTENT_TYPE, respContentType);

            ResponseBody respBody = resp.body();
            if (respBody != null) exchange.getOutputStream().write(respBody.bytes());
        }
    }
}
