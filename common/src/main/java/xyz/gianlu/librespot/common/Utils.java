package xyz.gianlu.librespot.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.common.proto.Spirc;

import javax.sound.sampled.Mixer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.Permission;
import java.security.PermissionCollection;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

/**
 * @author Gianlu
 */
public class Utils {
    public static final byte[] EOL = new byte[]{'\r', '\n'};
    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    private static final Logger LOGGER = Logger.getLogger(Utils.class);

    @NotNull
    public static String decodeGZip(@NotNull ByteString bytes) throws IOException {
        if (bytes.isEmpty()) return "";

        try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(bytes.toByteArray()));
             ByteArrayOutputStream dataOut = new ByteArrayOutputStream(bytes.size() /* At least */)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = gzis.read(buffer)) != -1)
                dataOut.write(buffer, 0, count);
            return new String(dataOut.toByteArray(), "UTF-8");
        }
    }

    @NotNull
    public static String toBase64(@NotNull ByteString bytes) {
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    public static int indexOf(@NotNull List<Spirc.TrackRef> list, @NotNull Spirc.TrackRef ref) {
        for (int i = 0; i < list.size(); i++) {
            Spirc.TrackRef item = list.get(i);
            if (item.hasGid() && ref.hasGid() && item.getGid().equals(ref.getGid()))
                return i;
            else if (item.hasUri() && ref.hasUri() && item.getUri().equals(ref.getUri()))
                return i;
        }

        return -1;
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

    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    public static <A> A wait(@NotNull AtomicReference<A> ref) throws IOException {
        synchronized (ref) {
            try {
                ref.wait();
                return ref.get();
            } catch (InterruptedException ex) {
                throw new IOException(ex);
            }
        }
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
                split[j] = tmp.substring(i + 1, tmp.length());
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
        return bytesToHex(bytes, 0, bytes.length, false);
    }

    @NotNull
    public static String bytesToHex(byte[] bytes, boolean trim) {
        return bytesToHex(bytes, 0, bytes.length, trim);
    }

    @NotNull
    public static String bytesToHex(byte[] bytes, int offset, int length, boolean trim) {
        if (bytes == null) return "";

        int newOffset = 0;
        boolean trimming = trim;
        char[] hexChars = new char[length * 2];
        for (int j = offset; j < length; j++) {
            int v = bytes[j] & 0xFF;
            if (trimming) {
                if (v == 0) {
                    newOffset = j  + 1;
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
    public static String mixersToString(List<Mixer> list) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Mixer mixer : list) {
            if (!first) builder.append(", ");
            first = false;

            builder.append('\'').append(mixer.getMixerInfo().getName()).append('\'');
        }

        return builder.toString();
    }

    @NotNull
    public static String removeLineBreaks(@NotNull String str) {
        return str.replace("\n", "").replace("\r", "");
    }
}
