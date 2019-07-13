package xyz.gianlu.librespot.crypto;

import com.google.protobuf.ByteString;
import xyz.gianlu.librespot.common.proto.Authentication;
import xyz.gianlu.librespot.crypto.PBKDF2;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

public class BlobUtils {

    public static Authentication.LoginCredentials decryptBlob(final String username, final byte[] encryptedBlob, final String deviceId) throws GeneralSecurityException, IOException {
        byte[] decode = Base64.getDecoder().decode(encryptedBlob);

        byte[] secret = MessageDigest.getInstance("SHA-1").digest(deviceId.getBytes());
        byte[] baseKey = PBKDF2.HmacSHA1(secret, username.getBytes(), 0x100, 20);

        byte[] key = ByteBuffer.allocate(24)
                .put(MessageDigest.getInstance("SHA-1").digest(baseKey))
                .putInt(20)
                .array();

        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] decryptedBlob = aes.doFinal(decode);

        int l = decryptedBlob.length;
        for (int i = 0; i < l - 0x10; i++)
            decryptedBlob[l - i - 1] ^= decryptedBlob[l - i - 0x11];

        ByteBuffer blob = ByteBuffer.wrap(decryptedBlob);
        blob.get();
        int len = readBlobInt(blob);
        blob.get(new byte[len]);
        blob.get();

        int typeInt = readBlobInt(blob);
        Authentication.AuthenticationType type = Authentication.AuthenticationType.forNumber(typeInt);
        if (type == null)
            throw new IOException(new IllegalArgumentException("Unknown AuthenticationType: " + typeInt));

        blob.get();

        len = readBlobInt(blob);
        byte[] authData = new byte[len];
        blob.get(authData);

        return Authentication.LoginCredentials.newBuilder()
                .setUsername(username)
                .setTyp(type)
                .setAuthData(ByteString.copyFrom(authData))
                .build();
    }

    private static int readBlobInt(ByteBuffer buffer) {
        int lo = buffer.get();
        if ((lo & 0x80) == 0) return lo;
        int hi = buffer.get();
        return lo & 0x7f | hi << 7;
    }
}
