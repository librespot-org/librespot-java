package org.librespot.spotify;

import com.google.gson.JsonObject;
import net.posick.mDNS.MulticastDNSService;
import net.posick.mDNS.ServiceInstance;
import net.posick.mDNS.ServiceName;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.xbill.DNS.Name;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Base64;

/**
 * @author Gianlu
 */
public class ZeroconfAuthenticator implements Closeable {
    private final static int MAX_PORT = 65536;
    private final static int MIN_PORT = 1024;
    private static final Logger LOGGER = Logger.getLogger(ZeroconfAuthenticator.class);
    private static final byte[] EOL = new byte[]{'\r', '\n'};
    private static final JsonObject DEFAULT_GET_INFO_FIELDS = new JsonObject();

    static {
        DEFAULT_GET_INFO_FIELDS.addProperty("status", 101);
        DEFAULT_GET_INFO_FIELDS.addProperty("statusString", "ERROR-OK");
        DEFAULT_GET_INFO_FIELDS.addProperty("spotifyError", 0);
        DEFAULT_GET_INFO_FIELDS.addProperty("version", "2.1.0");
        DEFAULT_GET_INFO_FIELDS.addProperty("activeUser", "");
        DEFAULT_GET_INFO_FIELDS.addProperty("libraryVersion", "0.1.0");
        DEFAULT_GET_INFO_FIELDS.addProperty("accountReq", "PREMIUM");
        DEFAULT_GET_INFO_FIELDS.addProperty("brandDisplayName", "librespot-java");
        DEFAULT_GET_INFO_FIELDS.addProperty("modelDisplayName", Version.versionString());
    }

    private final HttpRunner runner;
    private final Session session;
    private final MulticastDNSService mDnsService;
    private final ServiceInstance spotifyConnectService;

    public ZeroconfAuthenticator(Session session) throws IOException {
        this.session = session;
        this.mDnsService = new MulticastDNSService();

        int port = session.random().nextInt((MAX_PORT - MIN_PORT) + 1) + MIN_PORT;
        this.runner = new HttpRunner(port);
        new Thread(runner).start();

        ServiceInstance service = new ServiceInstance(new ServiceName("librespot._spotify-connect._tcp.local."), 0, 0, port, Name.fromString("local."), new InetAddress[]{InetAddress.getLocalHost()}, "VERSION=1.0", "CPath=/");
        spotifyConnectService = mDnsService.register(service);
        if (spotifyConnectService == null)
            throw new IOException("Failed registering SpotifyConnect service!");

        LOGGER.info("SpotifyConnect service registered successfully!");
    }

    @NotNull
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean lastWasR = false;
        int read;
        while ((read = in.read()) != -1) {
            if (read == '\r') {
                lastWasR = true;
                continue;
            } else if (read == '\n' && lastWasR) {
                break;
            }

            buffer.write(read);
        }

        return buffer.toString();
    }

    @Override
    public void close() throws IOException {
        mDnsService.unregister(spotifyConnectService);
        mDnsService.close();
        runner.close();
    }

    private void handleGetInfo(OutputStream out, String httpVersion) throws IOException {
        JsonObject info = DEFAULT_GET_INFO_FIELDS.deepCopy();
        info.addProperty("deviceID", session.deviceId());
        info.addProperty("remoteName", session.deviceName());
        info.addProperty("publicKey", Base64.getEncoder().encodeToString(session.keys().publicKeyArray()));
        info.addProperty("deviceType", session.deviceType().name.toUpperCase());

        out.write(httpVersion.getBytes());
        out.write(" 200 OK".getBytes());
        out.write(EOL);
        out.flush();

        out.write("Content-Type: application/json".getBytes());
        out.write(EOL);
        out.flush();

        out.write(EOL);
        out.write(info.toString().getBytes());
        out.flush();
        out.close();
    }

    private void handleAddUser() {
        // TODO
    }

    private class HttpRunner implements Runnable, Closeable {
        private final ServerSocket serverSocket;
        private volatile boolean shouldStop = false;

        HttpRunner(int port) throws IOException {
            serverSocket = new ServerSocket(port);
            LOGGER.info(String.format("HTTP server started successfully on port %d!", port));
        }

        @Override
        public void run() {
            while (!shouldStop) {
                try {
                    handle(serverSocket.accept());
                } catch (IOException ex) {
                    LOGGER.fatal("Failed handling request!", ex);
                }
            }
        }

        private void handle(@NotNull Socket socket) throws IOException {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String[] requestLine = Utils.split(readLine(in), ' ');
            if (requestLine.length != 3) {
                LOGGER.warn("Unexpected request line: " + Arrays.toString(requestLine));
                socket.close();
                return;
            }

            String method = requestLine[0];
            String path = requestLine[1];
            String httpVersion = requestLine[2];
            LOGGER.trace(String.format("Handling request: %s %s %s", method, path, httpVersion));

            if (path.startsWith("/?action=")) {
                String action = path.substring(9);

                switch (action) {
                    case "getInfo":
                        handleGetInfo(out, httpVersion);
                        break;
                    case "addUser":
                        handleAddUser();
                        break;
                    default:
                        LOGGER.warn("Unknown action: " + action);
                        socket.close();
                        break;
                }
            } else {
                LOGGER.warn("Unknown path: " + path);
                socket.close();
            }
        }

        @Override
        public void close() throws IOException {
            shouldStop = true;
            serverSocket.close();
        }
    }
}
