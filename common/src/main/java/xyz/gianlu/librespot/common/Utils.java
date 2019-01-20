package xyz.gianlu.librespot.common;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.common.proto.Spirc;

import javax.sound.sampled.Mixer;
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

/**
 * @author Gianlu
 */
public class Utils {
    public static final byte[] EOL = new byte[]{'\r', '\n'};
    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    private static final Logger LOGGER = Logger.getLogger(Utils.class);

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
        if (bytes == null) return "";

        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }

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
}
