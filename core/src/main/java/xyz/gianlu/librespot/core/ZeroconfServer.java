package xyz.gianlu.librespot.core;

import com.google.gson.JsonObject;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.AbsConfiguration;
import xyz.gianlu.librespot.Version;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Authentication;
import xyz.gianlu.librespot.crypto.DiffieHellman;
import xyz.gianlu.librespot.mercury.MercuryClient;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Gianlu
 */
public class ZeroconfServer implements Closeable {
    public final static int MAX_PORT = 65536;
    public final static int MIN_PORT = 1024;
    private static final Logger LOGGER = Logger.getLogger(ZeroconfServer.class);
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
        DEFAULT_GET_INFO_FIELDS.addProperty("statusString", "ERROR-OK");
        DEFAULT_GET_INFO_FIELDS.addProperty("spotifyError", 0);
        DEFAULT_GET_INFO_FIELDS.addProperty("version", "2.1.0");
        DEFAULT_GET_INFO_FIELDS.addProperty("libraryVersion", "0.1.0");
        DEFAULT_GET_INFO_FIELDS.addProperty("accountReq", "PREMIUM");
        DEFAULT_GET_INFO_FIELDS.addProperty("brandDisplayName", "librespot-java");
        DEFAULT_GET_INFO_FIELDS.addProperty("modelDisplayName", Version.versionString());

        DEFAULT_SUCCESSFUL_ADD_USER.addProperty("status", 101);
        DEFAULT_SUCCESSFUL_ADD_USER.addProperty("spotifyError", 0);
        DEFAULT_SUCCESSFUL_ADD_USER.addProperty("statusString", "ERROR-OK");

        Utils.removeCryptographyRestrictions();
    }

    private final HttpRunner runner;
    private final Session.Inner inner;
    private final DiffieHellman keys;
    private final JmDNS[] instances;
    private volatile Session session;

    private ZeroconfServer(Session.Inner inner, Configuration conf) throws IOException {
        this.inner = inner;
        this.keys = new DiffieHellman(inner.random);

        int port = conf.zeroconfListenPort();
        if (port == -1)
            port = inner.random.nextInt((MAX_PORT - MIN_PORT) + 1) + MIN_PORT;

        new Thread(this.runner = new HttpRunner(port), "zeroconf-http-server").start();

        InetAddress[] bound;
        if (conf.zeroconfListenAll()) {
            bound = getAllInterfacesAddresses();
        } else {
            String[] interfaces = conf.zeroconfInterfaces();
            if (interfaces == null || interfaces.length == 0) {
                bound = new InetAddress[]{InetAddress.getLoopbackAddress()};
            } else {
                List<InetAddress> list = new ArrayList<>();
                for (String str : interfaces) addAddressForInterfaceName(list, str);
                bound = list.toArray(new InetAddress[0]);
            }
        }

        LOGGER.debug("Registering service on " + Arrays.toString(bound));

        Map<String, String> txt = new HashMap<>();
        txt.put("CPath", "/");
        txt.put("VERSION", "1.0");

        boolean atLeastOne = false;
        instances = new JmDNS[bound.length];
        for (int i = 0; i < instances.length; i++) {
            try {
                instances[i] = JmDNS.create(bound[i], bound[i].getHostName());
                ServiceInfo serviceInfo = ServiceInfo.create("_spotify-connect._tcp.local.", "librespot-java-" + i, port, 0, 0, txt);
                instances[i].registerService(serviceInfo);
                atLeastOne = true;
            } catch (SocketException ex) {
                LOGGER.warn("Failed creating socket for " + bound[i], ex);
            }
        }

        if (atLeastOne) LOGGER.info("SpotifyConnect service registered successfully!");
        else throw new IllegalStateException("Could not register the service anywhere!");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                close();
            } catch (IOException ignored) {
            }
        }));
    }

    @NotNull
    public static ZeroconfServer create(@NotNull AbsConfiguration conf) throws IOException {
        ApResolver.fillPool();
        return new ZeroconfServer(Session.Inner.from(conf), conf);
    }

    private static void addAddressForInterfaceName(List<InetAddress> list, @NotNull String name) throws SocketException {
        NetworkInterface nif = NetworkInterface.getByName(name);
        if (nif == null) {
            LOGGER.warn(String.format("Interface %s doesn't exists.", name));
            return;
        }

        addAddressOfInterface(list, nif, false);
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

    private static void addAddressOfInterface(List<InetAddress> list, @NotNull NetworkInterface nif, boolean checkVirtual) throws SocketException {
        if (nif.isLoopback()) return;

        if (isVirtual(nif)) {
            if (checkVirtual)
                return;
            else
                LOGGER.warn(String.format("Interface %s is suspected to be virtual, mac: %s", nif.getName(), Utils.bytesToHex(nif.getHardwareAddress())));
        }

        LOGGER.trace(String.format("Adding addresses of %s {displayName: %s, mac: %s}", nif.getName(), nif.getDisplayName(), Utils.bytesToHex(nif.getHardwareAddress())));
        Enumeration<InetAddress> ias = nif.getInetAddresses();
        while (ias.hasMoreElements())
            list.add(ias.nextElement());
    }

    @NotNull
    private static InetAddress[] getAllInterfacesAddresses() throws SocketException {
        List<InetAddress> list = new ArrayList<>();
        Enumeration<NetworkInterface> is = NetworkInterface.getNetworkInterfaces();
        while (is.hasMoreElements()) addAddressOfInterface(list, is.nextElement(), true);
        return list.toArray(new InetAddress[0]);
    }

    @Override
    public void close() throws IOException {
        for (JmDNS instance : instances) {
            if (instance != null) instance.unregisterAllServices();
        }

        LOGGER.trace("SpotifyConnect service unregistered successfully.");
        runner.close();
    }

    public boolean hasValidSession() {
        boolean valid = session != null && session.valid();
        if (!valid) session = null;
        return valid;
    }

    private void handleGetInfo(OutputStream out, String httpVersion) throws IOException {
        JsonObject info = DEFAULT_GET_INFO_FIELDS.deepCopy();
        info.addProperty("activeUser", hasValidSession() ? session.apWelcome().getCanonicalUsername() : "");
        info.addProperty("deviceID", inner.deviceId);
        info.addProperty("remoteName", inner.deviceName);
        info.addProperty("publicKey", Base64.getEncoder().encodeToString(keys.publicKeyArray()));
        info.addProperty("deviceType", inner.deviceType.name().toUpperCase());

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
        if (username == null) {
            LOGGER.fatal("Missing userName!");
            return;
        }

        String blobStr = params.get("blob");
        if (blobStr == null) {
            LOGGER.fatal("Missing blob!");
            return;
        }

        String clientKeyStr = params.get("clientKey");
        if (clientKeyStr == null) {
            LOGGER.fatal("Missing clientKey!");
            return;
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
            return;
        }

        Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(encryptionKey, 0, 16), "AES"), new IvParameterSpec(iv));
        byte[] decrypted = aes.doFinal(encrypted);


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


        try {
            Authentication.LoginCredentials credentials = inner.decryptBlob(username, decrypted);
            if (hasValidSession()) {
                session.close();
                LOGGER.trace(String.format("Closed previous session to accept new. {deviceId: %s}", session.deviceId()));
            }

            session = Session.from(inner);
            LOGGER.info(String.format("Accepted new user from %s. {deviceId: %s}", params.get("deviceName"), session.deviceId()));

            session.connect();
            session.authenticate(credentials);
        } catch (Session.SpotifyAuthenticationException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed handling connection! Going away.", ex);
            close();
        }
    }

    public interface Configuration {
        boolean zeroconfListenAll();

        int zeroconfListenPort();

        @Nullable
        String[] zeroconfInterfaces();
    }

    private class HttpRunner implements Runnable, Closeable {
        private final ServerSocket serverSocket;
        private final ExecutorService executorService = Executors.newCachedThreadPool(new NameThreadFactory((r) -> "zeroconf-client-" + r.hashCode()));
        private volatile boolean shouldStop = false;

        HttpRunner(int port) throws IOException {
            serverSocket = new ServerSocket(port);
            LOGGER.info(String.format("Zeroconf HTTP server started successfully on port %d!", port));
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
                LOGGER.trace(String.format("Handling request: %s %s %s, headers: %s", method, path, httpVersion, headers));

            if (method.equals("POST") && path.equals("/")) {
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
                Map<String, String> params = new HashMap<>(pairs.length);
                for (String pair : pairs) {
                    String[] split = Utils.split(pair, '=');
                    params.put(URLDecoder.decode(split[0], "UTF-8"), URLDecoder.decode(split[1], "UTF-8"));
                }

                String action = params.get("action");
                if (Objects.equals(action, "addUser")) {
                    try {
                        handleAddUser(out, params, httpVersion);
                    } catch (GeneralSecurityException | IOException ex) {
                        LOGGER.fatal("Failed handling addUser!", ex);
                    }
                } else {
                    LOGGER.warn("Unknown action: " + action);
                }
            } else if (path.startsWith("/?action=")) {
                String action = path.substring(9);
                if (action.equals("getInfo")) {
                    try {
                        handleGetInfo(out, httpVersion);
                    } catch (IOException ex) {
                        LOGGER.fatal("Failed handling getInfo!", ex);
                    }
                } else {
                    LOGGER.warn("Unknown action: " + action);
                }
            } else {
                LOGGER.warn(String.format("Couldn't handle request: %s %s %s", method, path, httpVersion));
            }
        }

        @Override
        public void close() throws IOException {
            shouldStop = true;
            serverSocket.close();
        }
    }
}
