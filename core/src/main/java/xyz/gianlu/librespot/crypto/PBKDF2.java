package xyz.gianlu.librespot.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@SuppressWarnings("ALL")
public class PBKDF2 {
    private static void XOR(byte[] dst, byte[] src, int len) {
        for (int i = 0; i < len; i++)
            dst[i] ^= src[i];
    }

    private static byte[] INT(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) (value)};
    }

    private static void F(Mac mac, byte[] S, int c, int i, byte[] dst, int offset, int len) throws javax.crypto.ShortBufferException {
        // U_1 = PRF(P, S || INT (i))
        mac.reset();
        mac.update(S);
        mac.update(INT(i));

        byte[] U = mac.doFinal();
        byte[] T = U.clone();

        // U_j = PRF(P, U_{j-1})
        // T_i = F(P, S, c, i) = U_1 \xor U_2 \xor ... \xor U_c
        for (int j = 1; j < c; j++) {
            mac.update(U);
            mac.doFinal(U, 0);
            XOR(T, U, len);
        }
        System.arraycopy(T, 0, dst, offset, len);
    }

    private static byte[] pbkdf2(String algorithm, byte[] P, byte[] S, int c, int dkLen) {
        // dkLen might be long to use "< (2^32 - 1) * hLen" condition
        if (dkLen <= 0 || dkLen > 0x100000)
            return null;
        if (c <= 0 || c > 0x100000)
            return null;

        Mac mac;
        int hLen;
        try {
            mac = Mac.getInstance(algorithm);
            hLen = mac.getMacLength();
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }

        // set Password
        SecretKeySpec sks = new SecretKeySpec(P, mac.getAlgorithm());
        try {
            mac.init(sks);
        } catch (java.security.InvalidKeyException e) {
            return null;
        }

        int l = dkLen / hLen + ((dkLen % hLen == 0) ? 0 : 1);
        int r = dkLen - (l - 1) * hLen;

        try {
            byte[] DK = new byte[dkLen];

            // DK = T_1 || T_2 ||  ...  || T_l<0..r-1>
            for (int i = 1; i <= l; i++) {
                F(mac, S, c, i, DK, (i - 1) * hLen, i == l ? r : hLen);
            }
            return DK;
        } catch (javax.crypto.ShortBufferException e) {
            return null;
        }
    }

    public static byte[] HmacMD5(byte[] P, byte[] S, int c, int dkLen) {
        return pbkdf2("HmacMD5", P, S, c, dkLen);
    }

    public static byte[] HmacSHA1(byte[] P, byte[] S, int c, int dkLen) {
        return pbkdf2("HmacSHA1", P, S, c, dkLen);
    }

    public static byte[] HmacSHA256(byte[] P, byte[] S, int c, int dkLen) {
        return pbkdf2("HmacSHA256", P, S, c, dkLen);
    }

    public static byte[] HmacSHA384(byte[] P, byte[] S, int c, int dkLen) {
        return pbkdf2("HmacSHA384", P, S, c, dkLen);
    }

    public static byte[] HmacSHA512(byte[] P, byte[] S, int c, int dkLen) {
        return pbkdf2("HmacSHA512", P, S, c, dkLen);
    }
}