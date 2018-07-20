package org.librespot.spotify.crypto;

import org.librespot.spotify.Utils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;

/**
 * @author Gianlu
 */
public class Keys {
    public final BigInteger primes;
    public final BigInteger publicKey;
    public final BigInteger privateKey;
    public ShannonPair shannonPair;

    private Keys(BigInteger primes, BigInteger publicKey, BigInteger privateKey) {
        this.primes = primes;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    public static Keys generate(Random random) {
        BigInteger G = BigInteger.valueOf(2L);
        BigInteger P = new BigInteger(1, new byte[]{
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xc9, (byte)
                (byte) 0x0f, (byte) 0xda, (byte) 0xa2, (byte) 0x21, (byte) 0x68, (byte) 0xc2, (byte) 0x34, (byte) 0xc4, (byte) 0xc6, (byte)
                (byte) 0x62, (byte) 0x8b, (byte) 0x80, (byte) 0xdc, (byte) 0x1c, (byte) 0xd1, (byte) 0x29, (byte) 0x02, (byte) 0x4e, (byte)
                (byte) 0x08, (byte) 0x8a, (byte) 0x67, (byte) 0xcc, (byte) 0x74, (byte) 0x02, (byte) 0x0b, (byte) 0xbe, (byte) 0xa6, (byte)
                (byte) 0x3b, (byte) 0x13, (byte) 0x9b, (byte) 0x22, (byte) 0x51, (byte) 0x4a, (byte) 0x08, (byte) 0x79, (byte) 0x8e, (byte)
                (byte) 0x34, (byte) 0x04, (byte) 0xdd, (byte) 0xef, (byte) 0x95, (byte) 0x19, (byte) 0xb3, (byte) 0xcd, (byte) 0x3a, (byte)
                (byte) 0x43, (byte) 0x1b, (byte) 0x30, (byte) 0x2b, (byte) 0x0a, (byte) 0x6d, (byte) 0xf2, (byte) 0x5f, (byte) 0x14, (byte)
                (byte) 0x37, (byte) 0x4f, (byte) 0xe1, (byte) 0x35, (byte) 0x6d, (byte) 0x6d, (byte) 0x51, (byte) 0xc2, (byte) 0x45, (byte)
                (byte) 0xe4, (byte) 0x85, (byte) 0xb5, (byte) 0x76, (byte) 0x62, (byte) 0x5e, (byte) 0x7e, (byte) 0xc6, (byte) 0xf4, (byte)
                (byte) 0x4c, (byte) 0x42, (byte) 0xe9, (byte) 0xa6, (byte) 0x3a, (byte) 0x36, (byte) 0x20, (byte) 0xff, (byte) 0xff, (byte)
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
        });

        byte[] keyData = new byte[95];
        random.nextBytes(keyData);
        BigInteger privateKey = new BigInteger(1, keyData);
        BigInteger publicKey = G.modPow(privateKey, P);
        return new Keys(P, publicKey.abs(), privateKey);
    }

    public byte[] publicKeyArray() {
        byte[] bytes = Utils.toByteArray(publicKey);
        if (bytes.length == 97) throw new IllegalArgumentException("Public key is 97 bites!!");
        if (bytes[0] == 0) return Arrays.copyOfRange(bytes, 1, bytes.length);
        else return bytes;
    }

    public byte[] solveChallenge(byte[] remoteKey, byte[] clientPacket, byte[] serverPacket) throws NoSuchAlgorithmException, InvalidKeyException, IOException {
        byte[] sharedSecret = Utils.toByteArray(new BigInteger(1, remoteKey).modPow(privateKey, primes));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(sharedSecret, "HmacSHA1"));

        for (int i = 1; i < 6; i++) {
            mac.update(clientPacket);
            mac.update(serverPacket);
            mac.update(new byte[]{(byte) i});
            baos.write(mac.doFinal());
            mac.reset();
        }

        byte[] data = baos.toByteArray();
        shannonPair = new ShannonPair(Arrays.copyOfRange(data, 20, 52), Arrays.copyOfRange(data, 52, 84));

        mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(Arrays.copyOfRange(data, 0, 20), "HmacSHA1"));
        mac.update(clientPacket);
        mac.update(serverPacket);
        return mac.doFinal();
    }
}
