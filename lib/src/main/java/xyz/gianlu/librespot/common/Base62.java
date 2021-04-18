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

package xyz.gianlu.librespot.common;


import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * A Base62 encoder/decoder.
 *
 * @author Sebastian Ruhleder, sebastian@seruco.io
 */
public final class Base62 {
    private static final int STANDARD_BASE = 256;
    private static final int TARGET_BASE = 62;
    private final byte[] alphabet;
    private byte[] lookup;

    private Base62(byte[] alphabet) {
        this.alphabet = alphabet;
        createLookupTable();
    }

    /**
     * Creates a {@link Base62} instance. Defaults to the GMP-style character set.
     *
     * @return a {@link Base62} instance.
     */
    public static Base62 createInstance() {
        return createInstanceWithGmpCharacterSet();
    }

    /**
     * Creates a {@link Base62} instance using the GMP-style character set.
     *
     * @return a {@link Base62} instance.
     */
    public static Base62 createInstanceWithGmpCharacterSet() {
        return new Base62(CharacterSets.GMP);
    }

    /**
     * Creates a {@link Base62} instance using the inverted character set.
     *
     * @return a {@link Base62} instance.
     */
    public static Base62 createInstanceWithInvertedCharacterSet() {
        return new Base62(CharacterSets.INVERTED);
    }

    /**
     * Encodes a sequence of bytes in Base62 encoding and pads it accordingly.
     *
     * @param message a byte sequence.
     * @param length  the expected length.
     * @return a sequence of Base62-encoded bytes.
     */
    public byte[] encode(byte[] message, int length) {
        byte[] indices = convert(message, STANDARD_BASE, TARGET_BASE, length);
        return translate(indices, alphabet);
    }

    /**
     * Encodes a sequence of bytes in Base62 encoding.
     *
     * @param message a byte sequence.
     * @return a sequence of Base62-encoded bytes.
     */
    public byte[] encode(byte[] message) {
        return encode(message, -1);
    }

    /**
     * Decodes a sequence of Base62-encoded bytes and pads it accordingly.
     *
     * @param encoded a sequence of Base62-encoded bytes.
     * @param length  the expected length.
     * @return a byte sequence.
     */
    public byte[] decode(byte[] encoded, int length) {
        byte[] prepared = translate(encoded, lookup);
        return convert(prepared, TARGET_BASE, STANDARD_BASE, length);
    }

    /**
     * Decodes a sequence of Base62-encoded bytes.
     *
     * @param encoded a sequence of Base62-encoded bytes.
     * @return a byte sequence.
     */
    public byte[] decode(byte[] encoded) {
        return decode(encoded, -1);
    }

    /**
     * Uses the elements of a byte array as indices to a dictionary and returns the corresponding values
     * in form of a byte array.
     */
    private byte[] translate(byte[] indices, byte[] dictionary) {
        byte[] translation = new byte[indices.length];
        for (int i = 0; i < indices.length; i++)
            translation[i] = dictionary[indices[i]];

        return translation;
    }

    /**
     * Converts a byte array from a source base to a target base using the alphabet.
     */
    private byte[] convert(byte[] message, int sourceBase, int targetBase, int length) {
        // This algorithm is inspired by: http://codegolf.stackexchange.com/a/21672

        int estimatedLength = length == -1 ? estimateOutputLength(message.length, sourceBase, targetBase) : length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(estimatedLength);
        byte[] source = message;
        while (source.length > 0) {
            ByteArrayOutputStream quotient = new ByteArrayOutputStream(source.length);
            int remainder = 0;
            for (byte b : source) {
                int accumulator = (b & 0xFF) + remainder * sourceBase;
                int digit = (accumulator - (accumulator % targetBase)) / targetBase;
                remainder = accumulator % targetBase;
                if (quotient.size() > 0 || digit > 0)
                    quotient.write(digit);
            }

            out.write(remainder);
            source = quotient.toByteArray();
        }

        if (out.size() < estimatedLength) {
            int size = out.size();
            for (int i = 0; i < estimatedLength - size; i++)
                out.write(0);

            return reverse(out.toByteArray());
        } else if (out.size() > estimatedLength) {
            return reverse(Arrays.copyOfRange(out.toByteArray(), 0, estimatedLength));
        } else {
            return reverse(out.toByteArray());
        }
    }

    /**
     * Estimates the length of the output in bytes.
     */
    private int estimateOutputLength(int inputLength, int sourceBase, int targetBase) {
        return (int) Math.ceil((Math.log(sourceBase) / Math.log(targetBase)) * inputLength);
    }

    /**
     * Reverses a byte array.
     */
    private byte[] reverse(byte[] arr) {
        int length = arr.length;
        byte[] reversed = new byte[length];
        for (int i = 0; i < length; i++)
            reversed[length - i - 1] = arr[i];

        return reversed;
    }

    /**
     * Creates the lookup table from character to index of character in character set.
     */
    private void createLookupTable() {
        lookup = new byte[256];
        for (int i = 0; i < alphabet.length; i++)
            lookup[alphabet[i]] = (byte) (i & 0xFF);
    }

    private static class CharacterSets {
        private static final byte[] GMP = {
                (byte) '0', (byte) '1', (byte) '2', (byte) '3', (byte) '4', (byte) '5', (byte) '6', (byte) '7',
                (byte) '8', (byte) '9', (byte) 'A', (byte) 'B', (byte) 'C', (byte) 'D', (byte) 'E', (byte) 'F',
                (byte) 'G', (byte) 'H', (byte) 'I', (byte) 'J', (byte) 'K', (byte) 'L', (byte) 'M', (byte) 'N',
                (byte) 'O', (byte) 'P', (byte) 'Q', (byte) 'R', (byte) 'S', (byte) 'T', (byte) 'U', (byte) 'V',
                (byte) 'W', (byte) 'X', (byte) 'Y', (byte) 'Z', (byte) 'a', (byte) 'b', (byte) 'c', (byte) 'd',
                (byte) 'e', (byte) 'f', (byte) 'g', (byte) 'h', (byte) 'i', (byte) 'j', (byte) 'k', (byte) 'l',
                (byte) 'm', (byte) 'n', (byte) 'o', (byte) 'p', (byte) 'q', (byte) 'r', (byte) 's', (byte) 't',
                (byte) 'u', (byte) 'v', (byte) 'w', (byte) 'x', (byte) 'y', (byte) 'z'
        };
        private static final byte[] INVERTED = {
                (byte) '0', (byte) '1', (byte) '2', (byte) '3', (byte) '4', (byte) '5', (byte) '6', (byte) '7',
                (byte) '8', (byte) '9', (byte) 'a', (byte) 'b', (byte) 'c', (byte) 'd', (byte) 'e', (byte) 'f',
                (byte) 'g', (byte) 'h', (byte) 'i', (byte) 'j', (byte) 'k', (byte) 'l', (byte) 'm', (byte) 'n',
                (byte) 'o', (byte) 'p', (byte) 'q', (byte) 'r', (byte) 's', (byte) 't', (byte) 'u', (byte) 'v',
                (byte) 'w', (byte) 'x', (byte) 'y', (byte) 'z', (byte) 'A', (byte) 'B', (byte) 'C', (byte) 'D',
                (byte) 'E', (byte) 'F', (byte) 'G', (byte) 'H', (byte) 'I', (byte) 'J', (byte) 'K', (byte) 'L',
                (byte) 'M', (byte) 'N', (byte) 'O', (byte) 'P', (byte) 'Q', (byte) 'R', (byte) 'S', (byte) 'T',
                (byte) 'U', (byte) 'V', (byte) 'W', (byte) 'X', (byte) 'Y', (byte) 'Z'
        };
    }
}

