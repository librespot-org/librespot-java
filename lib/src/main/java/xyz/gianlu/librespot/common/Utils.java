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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.ByteString;
import com.spotify.metadata.Metadata;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Permission;
import java.security.PermissionCollection;
import java.util.*;

/**
 * @author Gianlu
 */
public final class Utils {
    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    private static final Logger LOGGER = LoggerFactory.getLogger(Utils.class);
    private static final String randomString = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String JAVA_UTIL_BASE_64 = "java.util.Base64";
    private static final String ANDROID_UTIL_BASE_64 = "android.util.Base64";

    private Utils() {
    }

    @NotNull
    public static String randomHexString(@NotNull Random random, int length) {
        byte[] bytes = new byte[length / 2];
        random.nextBytes(bytes);
        return bytesToHex(bytes, 0, bytes.length, false, length);
    }

    @NotNull
    public static String randomString(@NotNull Random random, int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++)
            chars[i] = randomString.charAt(random.nextInt(randomString.length()));
        return new String(chars);
    }

    @NotNull
    public static String truncateMiddle(@NotNull String str, int length) {
        if (length <= 1) throw new IllegalStateException();

        int first = length / 2;
        String result = str.substring(0, first);
        result += "...";
        result += str.substring(str.length() - (length - first));
        return result;
    }

    @NotNull
    public static String readLine(@NotNull InputStream in) throws IOException {
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

    public static void removeCryptographyRestrictions() {
        if (!isRestrictedCryptography()) {
            LOGGER.info("Cryptography restrictions removal not needed.");
            return;
        }

        /*
         * Do the following, but with reflection to bypass access checks:
         *
         * JceSecurity.isRestricted = false;
         * JceSecurity.defaultPolicy.perms.clear();
         * JceSecurity.defaultPolicy.add(CryptoAllPermission.INSTANCE);
         */

        try {
            Class<?> jceSecurity = Class.forName("javax.crypto.JceSecurity");
            Field isRestrictedField = jceSecurity.getDeclaredField("isRestricted");
            isRestrictedField.setAccessible(true);
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(isRestrictedField, isRestrictedField.getModifiers() & ~Modifier.FINAL);
            isRestrictedField.set(null, false);

            Field defaultPolicyField = jceSecurity.getDeclaredField("defaultPolicy");
            defaultPolicyField.setAccessible(true);
            PermissionCollection defaultPolicy = (PermissionCollection) defaultPolicyField.get(null);

            Class<?> cryptoPermissions = Class.forName("javax.crypto.CryptoPermissions");
            Field perms = cryptoPermissions.getDeclaredField("perms");
            perms.setAccessible(true);
            ((Map<?, ?>) perms.get(defaultPolicy)).clear();

            Class<?> cryptoAllPermission = Class.forName("javax.crypto.CryptoAllPermission");
            Field instance = cryptoAllPermission.getDeclaredField("INSTANCE");
            instance.setAccessible(true);
            defaultPolicy.add((Permission) instance.get(null));

            LOGGER.info("Successfully removed cryptography restrictions.");
        } catch (Exception ex) {
            LOGGER.warn("Failed to remove cryptography restrictions!", ex);
        }
    }

    private static boolean isRestrictedCryptography() {
        // This matches Oracle Java 7 and 8, but not Java 9 or OpenJDK.
        String name = System.getProperty("java.runtime.name");
        String ver = System.getProperty("java.version");
        return name != null && name.equals("Java(TM) SE Runtime Environment") && ver != null && (ver.startsWith("1.7") || ver.startsWith("1.8"));
    }

    @NotNull
    public static String[] split(@NotNull String str, char c) {
        if (str.isEmpty()) return new String[0];

        int size = 1;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == c) size++;
        }

        String tmp = str;
        String[] split = new String[size];
        for (int j = size - 1; j >= 0; j--) {
            int i = tmp.lastIndexOf(c);
            if (i == -1) {
                split[j] = tmp;
            } else {
                split[j] = tmp.substring(i + 1);
                tmp = tmp.substring(0, i);
            }
        }

        return split;
    }

    public static byte[] hexToBytes(@NotNull String str) {
        int len = str.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            data[i / 2] = (byte) ((Character.digit(str.charAt(i), 16) << 4) + Character.digit(str.charAt(i + 1), 16));
        return data;
    }

    @NotNull
    public static byte[] toByteArray(@NotNull BigInteger i) {
        byte[] array = i.toByteArray();
        if (array[0] == 0) array = Arrays.copyOfRange(array, 1, array.length);
        return array;
    }

    @NotNull
    public static byte[] toByteArray(int i) {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(i);
        return buffer.array();
    }

    @NotNull
    public static String bytesToHex(@NotNull ByteString bytes) {
        return bytesToHex(bytes.toByteArray());
    }

    @NotNull
    public static String bytesToHex(byte[] bytes) {
        return bytesToHex(bytes, 0, bytes.length, false, -1);
    }

    @NotNull
    public static String bytesToHex(byte[] bytes, int off, int len) {
        return bytesToHex(bytes, off, len, false, -1);
    }

    @NotNull
    public static String bytesToHex(byte[] bytes, int offset, int length, boolean trim, int minLength) {
        if (bytes == null) return "";

        int newOffset = 0;
        boolean trimming = trim;
        char[] hexChars = new char[length * 2];
        for (int j = offset; j < length; j++) {
            int v = bytes[j] & 0xFF;
            if (trimming) {
                if (v == 0) {
                    newOffset = j + 1;

                    if (minLength != -1 && length - newOffset == minLength)
                        trimming = false;

                    continue;
                } else {
                    trimming = false;
                }
            }

            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }

        return new String(hexChars, newOffset * 2, hexChars.length - newOffset * 2);
    }

    @Contract("_, _, !null -> !null")
    public static String optString(@NotNull JsonObject obj, @NotNull String key, @Nullable String fallback) {
        JsonElement elm = obj.get(key);
        if (elm == null || !elm.getAsJsonPrimitive().isString()) return fallback;
        return elm.getAsString();
    }

    public static long optLong(@NotNull JsonObject obj, @NotNull String key, long fallback) {
        JsonElement elm = obj.get(key);
        if (elm == null || !elm.getAsJsonPrimitive().isNumber()) return fallback;
        return elm.getAsLong();
    }

    public static boolean optBoolean(@NotNull JsonObject obj, @NotNull String key, boolean fallback) {
        JsonElement elm = obj.get(key);
        if (elm == null || !elm.getAsJsonPrimitive().isBoolean()) return fallback;
        return elm.getAsBoolean();
    }

    @Nullable
    public static Boolean optBoolean(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement elm = obj.get(key);
        if (elm == null || !elm.getAsJsonPrimitive().isBoolean()) return null;
        return elm.getAsBoolean();
    }

    public static double optDouble(@NotNull JsonObject obj, @NotNull String key, double fallback) {
        JsonElement elm = obj.get(key);
        if (elm == null || !elm.getAsJsonPrimitive().isNumber()) return fallback;
        return elm.getAsDouble();
    }

    @Nullable
    public static String[] optStringArray(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement elm = obj.get(key);
        if (elm == null || !elm.isJsonArray()) return null;

        JsonArray a = elm.getAsJsonArray();
        String[] str = new String[a.size()];
        for (int i = 0; i < a.size(); i++)
            str[i] = a.get(i).getAsString();

        return str;
    }

    @NotNull
    public static String byteToHex(byte b) {
        char[] hexChars = new char[2];
        int v = b & 0xFF;
        hexChars[0] = hexArray[v >>> 4];
        hexChars[1] = hexArray[v & 0x0F];
        return new String(hexChars);
    }

    @NotNull
    public static List<Metadata.AudioFile.Format> formatsToString(@NotNull List<Metadata.AudioFile> files) {
        List<Metadata.AudioFile.Format> list = new ArrayList<>(files.size());
        for (Metadata.AudioFile file : files) list.add(file.getFormat());
        return list;
    }

    @NotNull
    public static String artistsToString(List<Metadata.Artist> artists) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Metadata.Artist artist : artists) {
            if (!first) builder.append(", ");
            first = false;

            builder.append(artist.getName());
        }

        return builder.toString();
    }

    @NotNull
    public static String toBase64(@NotNull byte[] bytes, boolean url, boolean padding) {
        byte[] encodedBytes;
        try {
            Class<?> clazz = Class.forName(JAVA_UTIL_BASE_64);

            Method getEncoder;
            if (url) getEncoder = clazz.getDeclaredMethod("getUrlEncoder");
            else getEncoder = clazz.getDeclaredMethod("getEncoder");

            Class<?> encoderClazz = Class.forName("java.util.Base64$Encoder");
            Object encoder = getEncoder.invoke(null);

            if (!padding) {
                Method withoutPadding = encoderClazz.getDeclaredMethod("withoutPadding");
                encoder = withoutPadding.invoke(encoder);
            }

            final Method encode = encoderClazz.getDeclaredMethod("encode", byte[].class);
            encodedBytes = (byte[]) encode.invoke(encoder, bytes);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            try {
                Class<?> clazz = Class.forName(ANDROID_UTIL_BASE_64);
                final Method encode = clazz.getDeclaredMethod("encode", byte[].class, int.class);
                int flags = 2; // Base64.NO_WRAP
                if (!padding)
                    flags |= 1; // Base64.NO_PADDING
                if (url)
                    flags |= 8; // Base64.URL_SAFE
                encodedBytes = (byte[]) encode.invoke(null, bytes, flags); // Base64.NO_WRAP | Base64.NO_PADDING
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored2) {
                throw new NoClassDefFoundError("Base64 not available");
            }
        }

        return new String(encodedBytes, StandardCharsets.UTF_8);
    }

    @NotNull
    public static String toBase64NoPadding(@NotNull byte[] bytes) {
        return toBase64(bytes, false, false);
    }

    @NotNull
    public static String toBase64(@NotNull byte[] bytes) {
        return toBase64(bytes,false, true);
    }

    @NotNull
    public static byte[] fromBase64(@NotNull String str) {
        return fromBase64(str.getBytes());
    }

    @NotNull
    public static byte[] fromBase64(@NotNull byte[] bytes) {
        byte[] decodedBytes;
        try {
            Class<?> clazz = Class.forName(JAVA_UTIL_BASE_64);
            final Method getDecoder = clazz.getDeclaredMethod("getDecoder");
            final Object decoder = getDecoder.invoke(null);
            Class<?> decoderClazz = Class.forName("java.util.Base64$Decoder");
            final Method decode = decoderClazz.getDeclaredMethod("decode", byte[].class);
            decodedBytes = (byte[]) decode.invoke(decoder, bytes);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            try {
                Class<?> clazz = Class.forName(ANDROID_UTIL_BASE_64);
                final Method decode = clazz.getDeclaredMethod("decode", byte[].class, int.class);
                int flags = 0; // android.util.Base64.DEFAULT
                decodedBytes = (byte[]) decode.invoke(null, bytes, flags);
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored2) {
                throw new NoClassDefFoundError("Base64 not available");
            }
        }

        return decodedBytes;
    }
}
