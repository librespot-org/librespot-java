package xyz.gianlu.librespot.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.Utils;
import xyz.gianlu.librespot.Version;
import xyz.gianlu.librespot.proto.Authentication;

import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.util.Base64;

/**
 * @author Gianlu
 */
public class FacebookAuthenticator implements Closeable {
    private static final URL LOGIN_SPOTIFY;
    private static final JsonParser PARSER = new JsonParser();
    private static final Logger LOGGER = Logger.getLogger(FacebookAuthenticator.class);
    private static final byte[] EOL = new byte[]{'\r', '\n'};

    static {
        try {
            LOGIN_SPOTIFY = new URL("https://login2.spotify.com/v1/config");
        } catch (MalformedURLException ex) {
            throw new RuntimeException(ex);
        }
    }

    private final String credentialsUrl;
    private final Object credentialsLock = new Object();
    private HttpPolling polling;
    private Authentication.LoginCredentials credentials = null;

    FacebookAuthenticator() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) LOGIN_SPOTIFY.openConnection();
        try {
            conn.connect();
            JsonObject obj = PARSER.parse(new InputStreamReader(conn.getInputStream())).getAsJsonObject();
            credentialsUrl = obj.get("credentials_url").getAsString();
            String loginUrl = obj.get("login_url").getAsString();
            LOGGER.info(String.format("Visit %s in your browser.", loginUrl));
            startPolling();
        } finally {
            conn.disconnect();
        }
    }

    private void startPolling() throws IOException {
        polling = new HttpPolling();
        new Thread(polling).start();
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
        if (polling != null) polling.stop();
    }

    private void authData(@NotNull String json) {
        JsonObject obj = PARSER.parse(json).getAsJsonObject();
        if (!obj.get("error").isJsonNull()) {
            LOGGER.fatal("Error during authentication: " + obj.get("error"));
            return;
        }

        JsonObject data = obj.getAsJsonObject("credentials");
        credentials = Authentication.LoginCredentials.newBuilder()
                .setUsername(data.get("username").getAsString())
                .setTyp(Authentication.AuthenticationType.forNumber(data.get("auth_type").getAsInt()))
                .setAuthData(ByteString.copyFrom(Base64.getDecoder().decode(data.get("encoded_auth_blob").getAsString())))
                .build();

        synchronized (credentialsLock) {
            credentialsLock.notifyAll();
        }
    }

    private class HttpPolling implements Runnable {
        private final String host;
        private final String path;
        private final Socket socket;
        private volatile boolean shouldStop = false;

        HttpPolling() throws IOException {
            URL url = new URL(credentialsUrl);
            path = url.getPath() + "?" + url.getQuery();
            host = url.getHost();

            socket = SSLSocketFactory.getDefault().createSocket(host, url.getDefaultPort());
        }

        private void stop() throws IOException {
            shouldStop = true;
            socket.close();
        }

        @Override
        public void run() {
            try {
                OutputStream out = socket.getOutputStream();
                DataInputStream in = new DataInputStream(socket.getInputStream());

                while (!shouldStop) {
                    out.write("GET ".getBytes());
                    out.write(path.getBytes());
                    out.write(" HTTP/1.1".getBytes());
                    out.write(EOL);
                    out.write("Host: ".getBytes());
                    out.write(host.getBytes());
                    out.write(EOL);
                    out.write("User-Agent: ".getBytes());
                    out.write(Version.versionString().getBytes());
                    out.write(EOL);
                    out.write("Accept: */*".getBytes());
                    out.write(EOL);
                    out.write(EOL);
                    out.flush();

                    String[] status = Utils.split(Utils.readLine(in), ' ');
                    int code = Integer.parseInt(status[1]);

                    int length = 0;
                    String header;
                    while (!(header = Utils.readLine(in)).isEmpty()) {
                        if (header.startsWith("Content-Length") && code == 200)
                            length = Integer.parseInt(header.substring(16));
                    }

                    if (code == 200) {
                        String json;
                        if (length != 0) {
                            byte[] buffer = new byte[length];
                            in.readFully(buffer);
                            json = new String(buffer);
                        } else {
                            json = Utils.readLine(in);
                        }

                        LOGGER.trace("Received authentication data: " + json);
                        authData(json);
                        break;
                    }
                }
            } catch (IOException ex) {
                LOGGER.fatal("Failed polling Spotify credentials URL!", ex);
            }
        }
    }
}
