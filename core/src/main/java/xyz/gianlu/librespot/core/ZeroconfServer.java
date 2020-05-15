package xyz.gianlu.librespot.core;

import com.google.gson.JsonObject;
import com.spotify.Authentication;
import okhttp3.HttpUrl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.AbsConfiguration;
import xyz.gianlu.librespot.Version;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.crypto.DiffieHellman;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.zeroconf.Service;
import xyz.gianlu.zeroconf.Zeroconf;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author Gianlu
 */
public class ZeroconfServer implements Closeable {
    public final static int MAX_PORT = 65536;
    public final static int MIN_PORT = 1024;
    private static final Logger LOGGER = LogManager.getLogger(ZeroconfServer.class);
    private static final byte[] EOL = new byte[]{'\r', '\n'};
    private static final JsonObject DEFAULT_GET_INFO_FIELDS = new JsonObject();
    private static final JsonObject DEFAULT_SUCCESSFUL_ADD_USER = new JsonObject();
    private static final byte[][] VIRTUAL_INTERFACES = new byte[][]{
            new byte[]{(byte) 0x00, (byte) 0x0F, (byte) 0x4B}, // Virtual Iron Software, Inc.
            new byte[]{(byte) 0x00, (byte) 0x13, (byte) 0x07}, // Paravirtual Corporation
            new byte[]{(byte) 0x00, (byte) 0x13, (byte) 0xBE}, // Virtual Conexions
            new byte[]{(byte) 0x00, (byte) 0x21, (byte) 0xF6}, // Virtual Iron Software
            new byte[]{(byte) 0x00, (byte) 0x24, (byte) 0x0B}, // Virtual Computer Inc.
            new byte[]{(byte) 0x00, (byte) 0xA0, (byte) 0xB1}, // First Virtual Corporation
            new byte[]{(byte) 0x00, (byte) 0xE0, (byte) 0xC8}, // Virtual access, ltd.
            new byte[]{(byte) 0x54, (byte) 0x52, (byte) 0x00}, // Linux kernel virtual machine (kvm)
            new byte[]{(byte) 0x00, (byte) 0x21, (byte) 0xF6}, // Oracle Corporation
            new byte[]{(byte) 0x18, (byte) 0x92, (byte) 0x2C}, // Virtual Instruments
            new byte[]{(byte) 0x3C, (byte) 0xF3, (byte) 0x92}, // VirtualTek. Co. Ltd.
            new byte[]{(byte) 0x00, (byte) 0x05, (byte) 0x69}, // VMWare 1
            new byte[]{(byte) 0x00, (byte) 0x0C, (byte) 0x29}, // VMWare 2
            new byte[]{(byte) 0x00, (byte) 0x50, (byte) 0x56}, // VMWare 3
            new byte[]{(byte) 0x00, (byte) 0x1C, (byte) 0x42}, // Parallels
            new byte[]{(byte) 0x00, (byte) 0x03, (byte) 0xFF}, // Microsoft Virtual PC
            new byte[]{(byte) 0x00, (byte) 0x16, (byte) 0x3E}, // Red Hat Xen, Oracle VM, Xen Source, Novell Xen
            new byte[]{(byte) 0x08, (byte) 0x00, (byte) 0x27}, // VirtualBox
            new byte[]{(byte) 0x00, (byte) 0x15, (byte) 0x5D}, // Hyper-V
    };

    static {
        DEFAULT_GET_INFO_FIELDS.addProperty("status", 101);
        DEFAULT_GET_INFO_FIELDS.addProperty("statusString", "OK");
        DEFAULT_GET_INFO_FIELDS.addProperty("spotifyError", 0);
        DEFAULT_GET_INFO_FIELDS.addProperty("version", "2.7.1");
        DEFAULT_GET_INFO_FIELDS.addProperty("libraryVersion", Version.versionNumber());
        DEFAULT_GET_INFO_FIELDS.addProperty("accountReq", "PREMIUM");
        DEFAULT_GET_INFO_FIELDS.addProperty("brandDisplayName", "librespot-org");
        DEFAULT_GET_INFO_FIELDS.addProperty("modelDisplayName", "librespot-java");
        DEFAULT_GET_INFO_FIELDS.addProperty("voiceSupport", "NO");
        DEFAULT_GET_INFO_FIELDS.addProperty("availability", "");
        DEFAULT_GET_INFO_FIELDS.addProperty("productID", 0);
        DEFAULT_GET_INFO_FIELDS.addProperty("tokenType", "default");
        DEFAULT_GET_INFO_FIELDS.addProperty("groupStatus", "NONE");
        DEFAULT_GET_INFO_FIELDS.addProperty("resolverVersion", "0");
        DEFAULT_GET_INFO_FIELDS.addProperty("scope", "streaming,client-authorization-universal");

        DEFAULT_SUCCESSFUL_ADD_USER.addProperty("status", 101);
        DEFAULT_SUCCESSFUL_ADD_USER.addProperty("spotifyError", 0);
        DEFAULT_SUCCESSFUL_ADD_USER.addProperty("statusString", "OK");

        Utils.removeCryptographyRestrictions();
    }

    private final HttpRunner runner;
    private final Session.Inner inner;
    private final DiffieHellman keys;
    private final List<SessionListener> sessionListeners;
    private final Zeroconf zeroconf;
    private final Object connectionLock = new Object();
    private volatile Session session;
    private String connectingUsername = null;

    private ZeroconfServer(Session.Inner inner, Configuration conf) throws IOException {
        this.inner = inner;
        this.keys = new DiffieHellman(inner.random);
        this.sessionListeners = new ArrayList<>();

        int port = conf.zeroconfListenPort();
        if (port == -1)
            port = inner.random.nextInt((MAX_PORT - MIN_PORT) + 1) + MIN_PORT;

        new Thread(this.runner = new HttpRunner(port), "zeroconf-http-server").start();

        List<NetworkInterface> nics;
        if (conf.zeroconfListenAll()) {
            nics = getAllInterfaces();
        } else {
            String[] interfaces = conf.zeroconfInterfaces();
            if (interfaces == null || interfaces.length == 0) {
                nics = getAllInterfaces();
            } else {
                nics = new ArrayList<>();
                for (String str : interfaces) {
                    NetworkInterface nif = NetworkInterface.getByName(str);
                    if (nif == null) {
                        LOGGER.warn("Interface {} doesn't exists.", str);
                        continue;
                    }

                    checkInterface(nics, nif, true);
                }
            }
        }

        zeroconf = new Zeroconf();
        zeroconf.setLocalHostName(getUsefulHostname());
        zeroconf.setUseIpv4(true).setUseIpv6(false);
        zeroconf.addNetworkInterfaces(nics);

        Map<String, String> txt = new HashMap<>();
        txt.put("CPath", "/");
        txt.put("VERSION", "1.0");
        txt.put("Stack", "SP");
        Service service = new Service(inner.deviceName, "spotify-connect", port);
        service.setText(txt);

        zeroconf.announce(service);
    }

    @NotNull
    public static String getUsefulHostname() throws UnknownHostException {
        String host = InetAddress.getLocalHost().getHostName();
        if (Objects.equals(host, "localhost")) {
            host = Base64.getEncoder().encodeToString(BigInteger.valueOf(ThreadLocalRandom.current().nextLong()).toByteArray()) + ".local";
            LOGGER.warn("Hostname cannot be `localhost`, temporary hostname: " + host);
            return host;
        }

        return host;
    }

    @NotNull
    public static ZeroconfServer create(@NotNull AbsConfiguration conf) throws IOException {
        ApResolver.fillPool();
        return new ZeroconfServer(Session.Inner.from(conf), conf);
    }

    private static boolean isVirtual(@NotNull NetworkInterface nif) throws SocketException {
        byte[] mac = nif.getHardwareAddress();
        if (mac == null) return true;

        outer:
        for (byte[] virtual : VIRTUAL_INTERFACES) {
            for (int i = 0; i < Math.min(virtual.length, mac.length); i++) {
                if (virtual[i] != mac[i])
                    continue outer;
            }

            return true;
        }

        return false;
    }

    private static void checkInterface(List<NetworkInterface> list, @NotNull NetworkInterface nif, boolean checkVirtual) throws SocketException {
        if (nif.isLoopback()) return;

        if (isVirtual(nif)) {
            if (checkVirtual)
                return;
            else
                LOGGER.warn("Interface {} is suspected to be virtual, mac: {}", nif.getName(), Utils.bytesToHex(nif.getHardwareAddress()));
        }

        list.add(nif);
    }

    @NotNull
    private static List<NetworkInterface> getAllInterfaces() throws SocketException {
        List<NetworkInterface> list = new ArrayList<>();
        Enumeration<NetworkInterface> is = NetworkInterface.getNetworkInterfaces();
        while (is.hasMoreElements()) checkInterface(list, is.nextElement(), true);
        return list;
    }

    @NotNull
    private static Map<String, String> parsePath(@NotNull String path) {
        HttpUrl url = HttpUrl.get("http://host" + path);
        Map<String, String> map = new HashMap<>();
        for (String name : url.queryParameterNames()) map.put(name, url.queryParameter(name));
        return map;
    }

    @Override
    public void close() throws IOException {
        zeroconf.close();
        runner.close();
    }

    public void closeSession() throws IOException {
        if (session != null) session.close();
        session = null;
    }

    private boolean hasValidSession() {
        try {
            boolean valid = session != null && session.isValid();
            if (!valid) session = null;
            return valid;
        } catch (IllegalStateException ex) {
            session = null;
            return false;
        }
    }

    private void handleGetInfo(OutputStream out, String httpVersion) throws IOException {
        JsonObject info = DEFAULT_GET_INFO_FIELDS.deepCopy();
        info.addProperty("deviceID", inner.deviceId);
        info.addProperty("remoteName", inner.deviceName);
        info.addProperty("publicKey", Base64.getEncoder().encodeToString(keys.publicKeyArray()));
        info.addProperty("deviceType", inner.deviceType.name().toUpperCase());

        synchronized (connectionLock) {
            info.addProperty("activeUser", connectingUsername != null ? connectingUsername : (hasValidSession() ? session.username() : ""));
        }

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
    }

    private void handleAddUser(OutputStream out, Map<String, String> params, String httpVersion) throws GeneralSecurityException, IOException {
        String username = params.get("userName");
        if (username == null || username.isEmpty()) {
            LOGGER.fatal("Missing userName!");
            return;
        }

        String blobStr = params.get("blob");
        if (blobStr == null || blobStr.isEmpty()) {
            LOGGER.fatal("Missing blob!");
            return;
        }

        String clientKeyStr = params.get("clientKey");
        if (clientKeyStr == null || clientKeyStr.isEmpty()) {
            LOGGER.fatal("Missing clientKey!");
            return;
        }

        synchronized (connectionLock) {
            if (username.equals(connectingUsername)) {
                LOGGER.info("{} is already trying to connect.", username);

                out.write(httpVersion.getBytes());
                out.write(" 403 Forbidden".getBytes()); // I don't think this is the Spotify way
                out.write(EOL);
                out.write(EOL);
                out.flush();
                return;
            }
        }

        byte[] sharedKey = Utils.toByteArray(keys.computeSharedKey(Base64.getDecoder().decode(clientKeyStr)));
        byte[] blobBytes = Base64.getDecoder().decode(blobStr);
        byte[] iv = Arrays.copyOfRange(blobBytes, 0, 16);
        byte[] encrypted = Arrays.copyOfRange(blobBytes, 16, blobBytes.length - 20);
        byte[] checksum = Arrays.copyOfRange(blobBytes, blobBytes.length - 20, blobBytes.length);

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(sharedKey);
        byte[] baseKey = Arrays.copyOfRange(sha1.digest(), 0, 16);

        Mac hmac = Mac.getInstance("HmacSHA1");
        hmac.init(new SecretKeySpec(baseKey, "HmacSHA1"));
        hmac.update("checksum".getBytes());
        byte[] checksumKey = hmac.doFinal();

        hmac = Mac.getInstance("HmacSHA1");
        hmac.init(new SecretKeySpec(baseKey, "HmacSHA1"));
        hmac.update("encryption".getBytes());
        byte[] encryptionKey = hmac.doFinal();

        hmac = Mac.getInstance("HmacSHA1");
        hmac.init(new SecretKeySpec(checksumKey, "HmacSHA1"));
        hmac.update(encrypted);
        byte[] mac = hmac.doFinal();

        if (!Arrays.equals(mac, checksum)) {
            LOGGER.fatal("Mac and checksum don't match!");

            out.write(httpVersion.getBytes());
            out.write(" 400 Bad Request".getBytes()); // I don't think this is the Spotify way
            out.write(EOL);
            out.write(EOL);
            out.flush();
            return;
        }

        Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(encryptionKey, 0, 16), "AES"), new IvParameterSpec(iv));
        byte[] decrypted = aes.doFinal(encrypted);

        try {
            closeSession();
        } catch (IOException ex) {
            LOGGER.warn("Failed closing previous session.", ex);
        }

        try {
            synchronized (connectionLock) {
                connectingUsername = username;
            }

            Authentication.LoginCredentials credentials = inner.decryptBlob(username, decrypted);

            session = Session.from(inner);
            LOGGER.info("Accepted new user from {}. {deviceId: {}}", params.get("deviceName"), session.deviceId());

            // Sending response
            String resp = DEFAULT_SUCCESSFUL_ADD_USER.toString();
            out.write(httpVersion.getBytes());
            out.write(" 200 OK".getBytes());
            out.write(EOL);
            out.write("Content-Length: ".getBytes());
            out.write(String.valueOf(resp.length()).getBytes());
            out.write(EOL);
            out.flush();

            out.write(EOL);
            out.write(resp.getBytes());
            out.flush();


            session.connect();
            session.authenticate(credentials);

            synchronized (connectionLock) {
                connectingUsername = null;
            }

            sessionListeners.forEach(l -> l.sessionChanged(session));
        } catch (Session.SpotifyAuthenticationException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Couldn't establish a new session.", ex);

            synchronized (connectionLock) {
                connectingUsername = null;
            }

            out.write(httpVersion.getBytes());
            out.write(" 500 Internal Server Error".getBytes()); // I don't think this is the Spotify way
            out.write(EOL);
            out.write(EOL);
            out.flush();
        }
    }

    public void addSessionListener(@NotNull SessionListener listener) {
        sessionListeners.add(listener);
    }

    public void removeSessionListener(@NotNull SessionListener listener) {
        sessionListeners.remove(listener);
    }

    public interface Configuration {
        boolean zeroconfListenAll();

        int zeroconfListenPort();

        @Nullable
        String[] zeroconfInterfaces();
    }

    public interface SessionListener {
        void sessionChanged(@NotNull Session session);
    }

    private class HttpRunner implements Runnable, Closeable {
        private final ServerSocket serverSocket;
        private final ExecutorService executorService = Executors.newCachedThreadPool(new NameThreadFactory((r) -> "zeroconf-client-" + r.hashCode()));
        private volatile boolean shouldStop = false;

        HttpRunner(int port) throws IOException {
            serverSocket = new ServerSocket(port);
            LOGGER.info("Zeroconf HTTP server started successfully on port {}!", port);
        }

        @Override
        public void run() {
            while (!shouldStop) {
                try {
                    Socket socket = serverSocket.accept();
                    executorService.execute(() -> {
                        try {
                            handle(socket);
                            socket.close();
                        } catch (IOException ex) {
                            LOGGER.fatal("Failed handling request!", ex);
                        }
                    });
                } catch (IOException ex) {
                    if (!shouldStop) LOGGER.fatal("Failed handling connection!", ex);
                }
            }
        }

        private void handleRequest(@NotNull OutputStream out, @NotNull String httpVersion, @NotNull String action, @Nullable Map<String, String> params) {
            if (Objects.equals(action, "addUser")) {
                if (params == null) throw new IllegalArgumentException();

                try {
                    handleAddUser(out, params, httpVersion);
                } catch (GeneralSecurityException | IOException ex) {
                    LOGGER.fatal("Failed handling addUser!", ex);
                }
            } else if (Objects.equals(action, "getInfo")) {
                try {
                    handleGetInfo(out, httpVersion);
                } catch (IOException ex) {
                    LOGGER.fatal("Failed handling getInfo!", ex);
                }
            } else {
                LOGGER.warn("Unknown action: " + action);
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

            Map<String, String> headers = new HashMap<>();
            String header;
            while (!(header = Utils.readLine(in)).isEmpty()) {
                String[] split = Utils.split(header, ':');
                headers.put(split[0], split[1].trim());
            }

            if (!hasValidSession())
                LOGGER.trace("Handling request: {} {} {}, headers: {}", method, path, httpVersion, headers);

            Map<String, String> params;
            if (Objects.equals(method, "POST")) {
                String contentType = headers.get("Content-Type");
                if (!Objects.equals(contentType, "application/x-www-form-urlencoded")) {
                    LOGGER.fatal("Bad Content-Type: " + contentType);
                    return;
                }

                String contentLengthStr = headers.get("Content-Length");
                if (contentLengthStr == null) {
                    LOGGER.fatal("Missing Content-Length header!");
                    return;
                }

                int contentLength = Integer.parseInt(contentLengthStr);
                byte[] body = new byte[contentLength];
                in.readFully(body);
                String bodyStr = new String(body);

                String[] pairs = Utils.split(bodyStr, '&');
                params = new HashMap<>(pairs.length);
                for (String pair : pairs) {
                    String[] split = Utils.split(pair, '=');
                    params.put(URLDecoder.decode(split[0], "UTF-8"),
                            URLDecoder.decode(split[1], "UTF-8"));
                }
            } else {
                params = parsePath(path);
            }

            String action = params.get("action");
            if (action == null) {
                LOGGER.debug("Request is missing action.");
                return;
            }

            handleRequest(out, httpVersion, action, params);
        }

        @Override
        public void close() throws IOException {
            shouldStop = true;
            serverSocket.close();
            executorService.shutdown();
        }
    }
}
