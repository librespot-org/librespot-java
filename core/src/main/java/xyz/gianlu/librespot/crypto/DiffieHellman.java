package xyz.gianlu.librespot.crypto;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.Utils;

import java.math.BigInteger;
import java.util.Random;

/**
 * @author Gianlu
 */
public class DiffieHellman {
    private static final BigInteger GENERATOR = BigInteger.valueOf(2);
    private static final byte PRIME_BYTES[] = {
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
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
    private static final BigInteger PRIME = new BigInteger(1, PRIME_BYTES);
    private static final Logger LOGGER = Logger.getLogger(DiffieHellman.class);
    private final BigInteger privateKey;
    private final BigInteger publicKey;
    private BigInteger sharedKey = null;

    public DiffieHellman(Random random) {
        byte[] keyData = new byte[95];
        random.nextBytes(keyData);

        privateKey = new BigInteger(1, keyData);
        publicKey = GENERATOR.modPow(privateKey, PRIME);
    }

    public void computeSharedKey(byte[] remoteKeyBytes) {
        if (sharedKey != null) throw new IllegalStateException("Cannot reuse object!");

        BigInteger remoteKey = new BigInteger(1, remoteKeyBytes);
        sharedKey = remoteKey.modPow(privateKey, PRIME);

        LOGGER.trace("Computed shared key successfully!");
    }

    @NotNull
    public BigInteger sharedKey() {
        if (sharedKey == null) throw new IllegalStateException("Shared key not initialized!");
        return sharedKey;
    }

    @NotNull
    public byte[] sharedKeyArray() {
        return Utils.toByteArray(sharedKey());
    }

    @NotNull
    public BigInteger privateKey() {
        return privateKey;
    }

    @NotNull
    public BigInteger publicKey() {
        return publicKey;
    }

    @NotNull
    public byte[] publicKeyArray() {
        return Utils.toByteArray(publicKey);
    }
}
