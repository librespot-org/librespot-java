/*
 * Copyright 2021 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.gianlu.librespot.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import com.spotify.Authentication;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.mercury.MercuryRequests;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author devgianlu
 */
public final class FacebookAuthenticator implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(FacebookAuthenticator.class);
    private static final int REDIRECT_PORT = 4381;
    private static final String REDIRECT_URI = "http://127.0.0.1:4381/login";
    private final Object credentialsLock = new Object();
    private final HttpServer httpServer;
    private final String codeChallenge;
    private final OkHttpClient client = new OkHttpClient();
    private Authentication.LoginCredentials credentials = null;

    FacebookAuthenticator() throws IOException, NoSuchAlgorithmException {
        codeChallenge = Utils.randomString(ThreadLocalRandom.current(), 48);

        HttpUrl authUrl = HttpUrl.get("https://accounts.spotify.com/authorize").newBuilder()
                .addQueryParameter("client_id", MercuryRequests.KEYMASTER_CLIENT_ID)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", REDIRECT_URI)
                .addQueryParameter("scope", "app-remote-control,playlist-modify,playlist-modify-private,playlist-modify-public,playlist-read,playlist-read-collaborative,playlist-read-private,streaming,ugc-image-upload,user-follow-modify,user-follow-read,user-library-modify,user-library-read,user-modify,user-modify-playback-state,user-modify-private,user-personalized,user-read-birthdate,user-read-currently-playing,user-read-email,user-read-play-history,user-read-playback-position,user-read-playback-state,user-read-private,user-read-recently-played,user-top-read")
                .addQueryParameter("code_challenge", Utils.toBase64(MessageDigest.getInstance("SHA-256").digest(codeChallenge.getBytes(StandardCharsets.UTF_8)), true, false))
                .addQueryParameter("code_challenge_method", "S256")
                .build();

        HttpUrl url = HttpUrl.get("https://accounts.spotify.com/login").newBuilder()
                .addQueryParameter("continue", authUrl.toString())
                .addQueryParameter("method", "facebook")
                .addQueryParameter("utm_source", "librespot-java")
                .addQueryParameter("utm_medium", "desktop")
                .build();

        LOGGER.info("Visit {} in your browser.", url);

        httpServer = new HttpServer();
        new Thread(httpServer, "facebook-auth-server").start();
    }

    @NotNull
    Authentication.LoginCredentials lockUntilCredentials() throws InterruptedException {
        synchronized (credentialsLock) {
            credentialsLock.wait();
            return credentials;
        }
    }

    @Override
    public void close() throws IOException {
        if (httpServer != null) httpServer.stop();
    }

    private class HttpServer implements Runnable {
        private final ServerSocket serverSocket;
        private volatile boolean shouldStop = false;
        private volatile Socket currentClient = null;

        HttpServer() throws IOException {
            serverSocket = new ServerSocket(REDIRECT_PORT);
        }

        private void stop() throws IOException {
            shouldStop = true;
            if (currentClient != null) currentClient.close();
        }

        @Override
        public void run() {
            while (!shouldStop) {
                try {
                    currentClient = serverSocket.accept();
                    handle(currentClient);
                    currentClient.close();
                } catch (IOException ex) {
                    if (shouldStop) break;

                    LOGGER.error("Failed handling incoming connection.", ex);
                }
            }
        }

        private void handle(@NotNull Socket socket) throws IOException {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            String[] requestLine = Utils.split(Utils.readLine(in), ' ');
            if (requestLine.length != 3) {
                LOGGER.warn("Unexpected request line: " + Arrays.toString(requestLine));
                return;
            }

            String method = requestLine[0];
            String path = requestLine[1];
            String httpVersion = requestLine[2];

            //noinspection StatementWithEmptyBody
            while (!Utils.readLine(in).isEmpty()) ;

            if (method.equals("GET") && path.startsWith("/login")) {
                String[] split = path.split("\\?code=");
                if (split.length != 2) {
                    LOGGER.warn("Missing code parameter in request: {}", path);
                    return;
                }

                handleLogin(httpVersion, out, split[1]);
            } else if (!path.equals("/favicon.ico")) {
                LOGGER.warn("Received unknown request: {} {}", method, path);
                out.write(String.format("%s 404 Not Found\r\n\r\n", httpVersion).getBytes(StandardCharsets.UTF_8));
            }
        }

        private void handleLogin(String httpVersion, OutputStream out, String code) throws IOException {
            JsonObject credentialsJson;
            try (Response resp = client.newCall(new Request.Builder().url("https://accounts.spotify.com/api/token")
                    .post(new FormBody.Builder()
                            .add("grant_type", "authorization_code")
                            .add("client_id", MercuryRequests.KEYMASTER_CLIENT_ID)
                            .add("redirect_uri", REDIRECT_URI)
                            .add("code_verifier", codeChallenge)
                            .add("code", code)
                            .build()).build()).execute()) {
                if (resp.code() != 200) {
                    LOGGER.error("Bad response code from token endpoint: {}", resp.code());
                    return;
                }

                ResponseBody body = resp.body();
                if (body == null) throw new IOException("Empty body!");

                credentialsJson = JsonParser.parseString(body.string()).getAsJsonObject();
            } catch (IOException ex) {
                LOGGER.error("Token endpoint request failed.", ex);
                out.write(String.format("%s 500 Internal Server Error\r\n\r\n", httpVersion).getBytes(StandardCharsets.UTF_8));
                return;
            }

            credentials = Authentication.LoginCredentials.newBuilder()
                    .setTyp(Authentication.AuthenticationType.AUTHENTICATION_SPOTIFY_TOKEN)
                    .setAuthData(ByteString.copyFrom(credentialsJson.get("access_token").getAsString(), StandardCharsets.UTF_8))
                    .build();

            synchronized (credentialsLock) {
                credentialsLock.notifyAll();
            }

            out.write(String.format("%s 302 Found\r\nLocation: https://open.spotify.com/desktop/auth/success\r\n\r\n", httpVersion).getBytes(StandardCharsets.UTF_8));
        }
    }
}
