package org.librespot.spotify;

import com.google.protobuf.ByteString;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.crypto.Keys;
import org.librespot.spotify.proto.Authentication;
import org.librespot.spotify.proto.Keyexchange;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;
import java.util.logging.Logger;

/**
 * @author Gianlu
 */
public class Session {
    private static final Logger LOGGER = Logger.getLogger(Session.class.getSimpleName());
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Random random;
    private final Keys keys;

    public Session(String ap) throws IOException {
        String host;
        int port;
        int pos = ap.indexOf(':');
        host = ap.substring(0, pos);
        port = Integer.parseInt(ap.substring(pos + 1, ap.length()));

        socket = new Socket(host, port);
        out = new DataOutputStream(socket.getOutputStream());
        in = new DataInputStream(socket.getInputStream());

        random = new SecureRandom();
        keys = Keys.generate(random);

        LOGGER.info("Created connection to " + host + " on port " + port);
    }

    public void connect() throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        ByteArrayOutputStream initClientPacket = new ByteArrayOutputStream();
        clientHello(initClientPacket);

        ByteArrayOutputStream initServerPacket = new ByteArrayOutputStream();
        Keyexchange.APResponseMessage apResponse = readApResponse(initServerPacket);
        byte[] challenge = keys.solveChallenge(apResponse.getChallenge().getLoginCryptoChallenge().getDiffieHellman().getGs().toByteArray(), initClientPacket.toByteArray(), initServerPacket.toByteArray());
        sendClientPlaintext(challenge);

        while (in.available() > 0) { // Shouldn't be any
            int b = in.read();
            System.out.println(b); // FIXME: Testing
        }

        LOGGER.info("Connected successfully!");
    }

    public void authenticate(String username, Authentication.AuthenticationType type, ByteString authData) throws IOException {
        LOGGER.info("Trying to authenticate with " + type);

        Authentication.ClientResponseEncrypted auth = Authentication.ClientResponseEncrypted.newBuilder()
                .setLoginCredentials(Authentication.LoginCredentials.newBuilder()
                        .setUsername(username)
                        .setTyp(type)
                        .setAuthData(authData)
                        .build())
                .setSystemInfo(Authentication.SystemInfo.newBuilder()
                        .setOs(Authentication.Os.OS_WINDOWS)
                        .setCpuFamily(Authentication.CpuFamily.CPU_UNKNOWN)
                        .build())
                .build();

        sendCmd(0xab, auth.toByteArray());

        DataInputStream headerIn = keys.shannonPair.readHeader(in);
        byte cmd = headerIn.readByte();
        int length = headerIn.readShort();
        System.out.println("HEADER: " + cmd + ", " + length);
        byte[] payload = keys.shannonPair.readPayload(length, in);
        System.out.println("PAYLOAD: " + Arrays.toString(payload));

        System.out.println("END");
    }

    private void sendCmd(int cmd, byte[] data) throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(stream);
        packet.writeByte(cmd);
        packet.writeShort(data.length);
        packet.write(data);

        byte[] enc = keys.shannonPair.encode(stream.toByteArray());
        out.write(enc);
    }

    private void sendClientPlaintext(byte[] challenge) throws IOException {
        Keyexchange.ClientResponsePlaintext plaintext = Keyexchange.ClientResponsePlaintext.newBuilder()
                .setLoginCryptoResponse(Keyexchange.LoginCryptoResponseUnion.newBuilder()
                        .setDiffieHellman(Keyexchange.LoginCryptoDiffieHellmanResponse.newBuilder()
                                .setHmac(ByteString.copyFrom(challenge))
                                .build())
                        .build())
                .setPowResponse(Keyexchange.PoWResponseUnion.newBuilder().build())
                .setCryptoResponse(Keyexchange.CryptoResponseUnion.newBuilder().build())
                .build();

        byte[] plaintextBytes = plaintext.toByteArray();
        out.writeInt(plaintextBytes.length + 4);
        out.write(plaintextBytes);
    }

    @NotNull
    private Keyexchange.APResponseMessage readApResponse(@NotNull ByteArrayOutputStream initServerPacket) throws IOException {
        int length = in.readInt();

        byte[] resp = new byte[length - 4];
        Utils.readChecked(in, resp);

        DataOutputStream packet = new DataOutputStream(initServerPacket);
        packet.writeInt(length);
        packet.write(resp);

        return Keyexchange.APResponseMessage.parseFrom(resp);
    }

    private void clientHello(@NotNull ByteArrayOutputStream initClientPacket) throws IOException {
        byte[] clientNonCe = new byte[16];
        random.nextBytes(clientNonCe);

        Keyexchange.ClientHello clientHello = Keyexchange.ClientHello.newBuilder()
                .setBuildInfo(Keyexchange.BuildInfo.newBuilder()
                        .setPlatform(Keyexchange.Platform.PLATFORM_LINUX_X86)
                        .setVersion(0x10800000000L)
                        .setProduct(Keyexchange.Product.PRODUCT_PARTNER).build())
                .addCryptosuitesSupported(Keyexchange.Cryptosuite.CRYPTO_SUITE_SHANNON)
                .setLoginCryptoHello(Keyexchange.LoginCryptoHelloUnion.newBuilder()
                        .setDiffieHellman(Keyexchange.LoginCryptoDiffieHellmanHello.newBuilder()
                                .setServerKeysKnown(1)
                                .setGc(ByteString.copyFrom(keys.publicKeyArray()))
                                .build())
                        .build())
                .setClientNonce(ByteString.copyFrom(clientNonCe))
                .setPadding(ByteString.copyFrom(new byte[]{0x1e}))
                .build();

        byte[] payload = clientHello.toByteArray();
        int length = 6 + payload.length;

        out.writeByte(0);
        out.writeByte(4);
        out.writeInt(length);
        out.write(payload);
        out.flush();

        DataOutputStream packet = new DataOutputStream(initClientPacket);
        packet.writeByte(0);
        packet.writeByte(4);
        packet.writeInt(length);
        packet.write(payload);
    }
}
