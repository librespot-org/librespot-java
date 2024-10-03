package xyz.gianlu.librespot.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import com.spotify.Authentication;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public class OAuth implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(OAuth.class);
    private static final String SPOTIFY_AUTH = "https://accounts.spotify.com/authorize?response_type=code&client_id=%s&redirect_uri=%s&code_challenge=%s&code_challenge_method=S256&scope=%s";
    private static final String[] SCOPES = new String[]{"app-remote-control", "playlist-modify", "playlist-modify-private", "playlist-modify-public", "playlist-read", "playlist-read-collaborative", "playlist-read-private", "streaming", "ugc-image-upload", "user-follow-modify", "user-follow-read", "user-library-modify", "user-library-read", "user-modify", "user-modify-playback-state", "user-modify-private", "user-personalized", "user-read-birthdate", "user-read-currently-playing", "user-read-email", "user-read-play-history", "user-read-playback-position", "user-read-playback-state", "user-read-private", "user-read-recently-played", "user-top-read"};
    private static final URL SPOTIFY_TOKEN;

    static {
        try {
            SPOTIFY_TOKEN = new URL("https://accounts.spotify.com/api/token");
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final String SPOTIFY_TOKEN_DATA = "grant_type=authorization_code&client_id=%s&redirect_uri=%s&code=%s&code_verifier=%s";

    private final String clientId;
    private final String redirectUrl;
    private final SecureRandom random = new SecureRandom();
    private final Object credentialsLock = new Object();

    private String codeVerifier;
    private String code;
    private String token;
    private HttpServer server;


    public OAuth(String clientId, String redirectUrl) {
        this.clientId = clientId;
        this.redirectUrl = redirectUrl;
    }

    private String generateCodeVerifier() {
        final String possible = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 128; i++) {
            sb.append(possible.charAt(random.nextInt(possible.length())));
        }
        return sb.toString();
    }

    private String generateCodeChallenge(String codeVerifier) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        byte[] hashed = digest.digest(codeVerifier.getBytes(StandardCharsets.UTF_8));
        return new String(Base64.getEncoder().encode(hashed))
                .replace("=", "")
                .replace("+", "-")
                .replace("/", "_");
    }

    public String getAuthUrl() {
        codeVerifier = generateCodeVerifier();
        return String.format(SPOTIFY_AUTH, clientId, redirectUrl, generateCodeChallenge(codeVerifier), String.join("+", SCOPES));
    }

    public void setCode(String code) {
        this.code = code;
    }

    public void requestToken() throws IOException {
        if (code == null) {
            throw new IllegalStateException("You need to provide code before!");
        }
        HttpURLConnection conn = (HttpURLConnection) SPOTIFY_TOKEN.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.getOutputStream().write(String.format(SPOTIFY_TOKEN_DATA, clientId, redirectUrl, code, codeVerifier).getBytes());
        if (conn.getResponseCode() != 200) {
            throw new IllegalStateException(String.format("Received status code %d: %s", conn.getResponseCode(), conn.getErrorStream().toString()));
        }
        try (Reader reader = new InputStreamReader(conn.getInputStream())) {
            conn.connect();
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            token = obj.get("access_token").getAsString();
        } finally {
            conn.disconnect();
        }
    }

    public Authentication.LoginCredentials getCredentials() {
        if (token == null) {
            throw new IllegalStateException("You need to request token before!");
        }
        return Authentication.LoginCredentials.newBuilder()
                .setTyp(Authentication.AuthenticationType.AUTHENTICATION_SPOTIFY_TOKEN)
                .setAuthData(ByteString.copyFromUtf8(token))
                .build();
    }

    public void runCallbackServer() throws IOException {
        URL url = new URL(redirectUrl);
        server = HttpServer.create(new InetSocketAddress(url.getHost(), url.getPort()), 0);
        server.createContext("/login", exchange -> {
            String response = "librespot-java received callback";
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
            String query = exchange.getRequestURI().getQuery();
            setCode(query.substring(query.indexOf('=') + 1));
            synchronized (credentialsLock) {
                credentialsLock.notifyAll();
            }
        });
        server.start();
        LOGGER.info("OAuth: Waiting for callback on {}", server.getAddress());
    }

    public Authentication.LoginCredentials flow() throws IOException, InterruptedException {
        LOGGER.info("OAuth: Visit in your browser and log in: {} ", getAuthUrl());
        runCallbackServer();
        synchronized (credentialsLock) {
            credentialsLock.wait();
        }
        requestToken();
        return getCredentials();
    }

    @Override
    public void close() throws IOException {
        if (server != null)
            server.stop(0);
    }
}