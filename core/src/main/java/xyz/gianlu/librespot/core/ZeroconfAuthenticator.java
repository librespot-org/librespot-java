package xyz.gianlu.librespot.core;

import com.google.gson.JsonObject;
import net.posick.mdns.MulticastDNSService;
import net.posick.mdns.ServiceInstance;
import net.posick.mdns.ServiceName;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.xbill.DNS.Name;
import xyz.gianlu.librespot.Version;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Authentication;
import xyz.gianlu.librespot.crypto.DiffieHellman;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Gianlu
 */
public class ZeroconfAuthenticator implements Closeable {
    private final static int MAX_PORT = 65536;
    private final static int MIN_PORT = 1024;
    private static final Logger LOGGER = Logger.getLogger(ZeroconfAuthenticator.class);
    private static final byte[] EOL = new byte[]{'\r', '\n'};
    private static final JsonObject DEFAULT_GET_INFO_FIELDS = new JsonObject();
    private static final JsonObject DEFAULT_SUCCESSFUL_ADD_USER = new JsonObject();

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

        DEFAULT_SUCCESSFUL_ADD_USER.addProperty("status", 101);
        DEFAULT_SUCCESSFUL_ADD_USER.addProperty("spotifyError", 0);
        DEFAULT_SUCCESSFUL_ADD_USER.addProperty("statusString", "ERROR-OK");

        Utils.removeCryptographyRestrictions();
    }

    private final HttpRunner runner;
    private final MulticastDNSService mDnsService;
    private final ServiceInstance spotifyConnectService;
    private final AtomicReference<Authentication.LoginCredentials> authenticationLock = new AtomicReference<>(null);
    private final Session.Inner session;
    private final DiffieHellman keys;

    ZeroconfAuthenticator(Session.Inner session, Configuration conf) throws IOException {
        this.session = session;
        this.keys = new DiffieHellman(session.random);
        this.mDnsService = new MulticastDNSService();

        int port = session.random.nextInt((MAX_PORT - MIN_PORT) + 1) + MIN_PORT;
        new Thread(this.runner = new HttpRunner(port)).start();

        InetAddress[] bound;
        if (conf.zeroconfListenAll()) {
            bound = getAllInterfacesAddresses();
        } else {
            String[] interfaces = conf.zeroconfInterfaces();
            if (interfaces.length == 0) {
                bound = new InetAddress[]{InetAddress.getLoopbackAddress()};
            } else {
                List<InetAddress> list = new ArrayList<>();
                for (String str : interfaces) addAddressForInterfaceName(list, str);
                bound = list.toArray(new InetAddress[0]);
            }
        }

        LOGGER.debug("Registering service on " + Arrays.toString(bound));

        ServiceInstance service = new ServiceInstance(new ServiceName("librespot._spotify-connect._tcp.local."), 0, 0, port, Name.fromString("local."), bound, "VERSION=1.0", "CPath=/");
        spotifyConnectService = mDnsService.register(service);
        if (spotifyConnectService == null)
            throw new IOException("Failed registering SpotifyConnect service!");
        LOGGER.info("SpotifyConnect service registered successfully!");
    }

    private static void addAddressForInterfaceName(List<InetAddress> list, @NotNull String name) throws SocketException {
        NetworkInterface nif = NetworkInterface.getByName(name);
        if (nif == null) {
            LOGGER.warn(String.format("Interface %s doesn't exists.", name));
            return;
        }

        addAddressOfInterface(list, nif);
    }

    private static void addAddressOfInterface(List<InetAddress> list, @NotNull NetworkInterface nif) {
        LOGGER.trace(String.format("Adding addresses of %s (displayName: %s)", nif.getName(), nif.getDisplayName()));
        Enumeration<InetAddress> ias = nif.getInetAddresses();
        while (ias.hasMoreElements())
            list.add(ias.nextElement());
    }

    @NotNull
    private static InetAddress[] getAllInterfacesAddresses() throws SocketException {
        List<InetAddress> list = new ArrayList<>();
        Enumeration<NetworkInterface> is = NetworkInterface.getNetworkInterfaces();
        while (is.hasMoreElements()) addAddressOfInterface(list, is.nextElement());
        return list.toArray(new InetAddress[0]);
    }

    @Override
    public void close() throws IOException {
        mDnsService.unregister(spotifyConnectService);
        LOGGER.trace("SpotifyConnect service unregistered successfully.");

        mDnsService.close();
        runner.close();
    }

    private void handleGetInfo(OutputStream out, String httpVersion) throws IOException {
        JsonObject info = DEFAULT_GET_INFO_FIELDS.deepCopy();
        info.addProperty("deviceID", session.deviceId);
        info.addProperty("remoteName", session.deviceName);
        info.addProperty("publicKey", Base64.getEncoder().encodeToString(keys.publicKeyArray()));
        info.addProperty("deviceType", session.deviceType.name.toUpperCase());

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
            LOGGER.fatal("Missing username!");
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

        keys.computeSharedKey(Base64.getDecoder().decode(clientKeyStr));

        byte[] blobBytes = Base64.getDecoder().decode(blobStr);
        byte[] iv = Arrays.copyOfRange(blobBytes, 0, 16);
        byte[] encrypted = Arrays.copyOfRange(blobBytes, 16, blobBytes.length - 20);
        byte[] checksum = Arrays.copyOfRange(blobBytes, blobBytes.length - 20, blobBytes.length);

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(keys.sharedKeyArray());
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

        Authentication.LoginCredentials credentials = session.decryptBlob(username, decrypted);
        synchronized (authenticationLock) {
            authenticationLock.set(credentials);
            authenticationLock.notifyAll();
        }
    }

    @NotNull
    Authentication.LoginCredentials lockUntilCredentials() throws InterruptedException {
        synchronized (authenticationLock) {
            authenticationLock.wait();
            return authenticationLock.get();
        }
    }

    public interface Configuration {
        boolean zeroconfListenAll();

        @NotNull
        String[] zeroconfInterfaces();
    }

    private class HttpRunner implements Runnable, Closeable {
        private final ServerSocket serverSocket;
        private volatile boolean shouldStop = false;

        HttpRunner(int port) throws IOException {
            serverSocket = new ServerSocket(port);
            LOGGER.info(String.format("Zeroconf HTTP server started successfully on port %d!", port));
        }

        @Override
        public void run() {
            while (!shouldStop) {
                try (Socket socket = serverSocket.accept()) { // We don't need this to be async
                    handle(socket);
                } catch (IOException ex) {
                    LOGGER.fatal("Failed handling request!", ex);
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