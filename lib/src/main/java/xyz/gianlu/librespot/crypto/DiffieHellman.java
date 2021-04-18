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

package xyz.gianlu.librespot.crypto;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;

import java.math.BigInteger;
import java.util.Random;

/**
 * @author Gianlu
 */
public class DiffieHellman {
    private static final BigInteger GENERATOR = BigInteger.valueOf(2);
    private static final byte[] PRIME_BYTES = {
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xc9,
            (byte) 0x0f, (byte) 0xda, (byte) 0xa2, (byte) 0x21, (byte) 0x68, (byte) 0xc2, (byte) 0x34, (byte) 0xc4, (byte) 0xc6,
            (byte) 0x62, (byte) 0x8b, (byte) 0x80, (byte) 0xdc, (byte) 0x1c, (byte) 0xd1, (byte) 0x29, (byte) 0x02, (byte) 0x4e,
            (byte) 0x08, (byte) 0x8a, (byte) 0x67, (byte) 0xcc, (byte) 0x74, (byte) 0x02, (byte) 0x0b, (byte) 0xbe, (byte) 0xa6,
            (byte) 0x3b, (byte) 0x13, (byte) 0x9b, (byte) 0x22, (byte) 0x51, (byte) 0x4a, (byte) 0x08, (byte) 0x79, (byte) 0x8e,
            (byte) 0x34, (byte) 0x04, (byte) 0xdd, (byte) 0xef, (byte) 0x95, (byte) 0x19, (byte) 0xb3, (byte) 0xcd, (byte) 0x3a,
            (byte) 0x43, (byte) 0x1b, (byte) 0x30, (byte) 0x2b, (byte) 0x0a, (byte) 0x6d, (byte) 0xf2, (byte) 0x5f, (byte) 0x14,
            (byte) 0x37, (byte) 0x4f, (byte) 0xe1, (byte) 0x35, (byte) 0x6d, (byte) 0x6d, (byte) 0x51, (byte) 0xc2, (byte) 0x45,
            (byte) 0xe4, (byte) 0x85, (byte) 0xb5, (byte) 0x76, (byte) 0x62, (byte) 0x5e, (byte) 0x7e, (byte) 0xc6, (byte) 0xf4,
            (byte) 0x4c, (byte) 0x42, (byte) 0xe9, (byte) 0xa6, (byte) 0x3a, (byte) 0x36, (byte) 0x20, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
    private static final BigInteger PRIME = new BigInteger(1, PRIME_BYTES);
    private final BigInteger privateKey;
    private final BigInteger publicKey;

    public DiffieHellman(Random random) {
        byte[] keyData = new byte[95];
        random.nextBytes(keyData);

        privateKey = new BigInteger(1, keyData);
        publicKey = GENERATOR.modPow(privateKey, PRIME);
    }

    @NotNull
    public BigInteger computeSharedKey(byte[] remoteKeyBytes) {
        BigInteger remoteKey = new BigInteger(1, remoteKeyBytes);
        return remoteKey.modPow(privateKey, PRIME);
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
