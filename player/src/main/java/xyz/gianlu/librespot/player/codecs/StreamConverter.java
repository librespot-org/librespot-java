package xyz.gianlu.librespot.player.codecs;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import javax.sound.sampled.AudioFormat;
import java.io.OutputStream;

public final class StreamConverter extends OutputStream {
    private final boolean monoToStereo;
    private final int sampleSizeFrom;
    private final int sampleSizeTo;
    private byte[] buffer;

    private StreamConverter(@NotNull AudioFormat from, @NotNull AudioFormat to) {
        monoToStereo = from.getChannels() == 1 && to.getChannels() == 2;
        sampleSizeFrom = from.getSampleSizeInBits();
        sampleSizeTo = to.getSampleSizeInBits();
    }

    public static boolean canConvert(@NotNull AudioFormat from, @NotNull AudioFormat to) {
        if (from.isBigEndian() || to.isBigEndian()) return false;

        if (from.matches(to)) return true;
        if (from.getEncoding() != to.getEncoding()) return false;
        return from.getSampleRate() == to.getSampleRate();
        // It is possible to convert the sample size
        // It is possible to convert the number of channels
    }

    @NotNull
    public static StreamConverter converter(@NotNull AudioFormat from, @NotNull AudioFormat to) {
        if (!canConvert(from, to))
            throw new UnsupportedOperationException(String.format("From '%s' to '%s'", from, to));

        return new StreamConverter(from, to);
    }

    private static byte[] monoToStereo(@NotNull byte[] src, int sampleSizeBits) {
        if (sampleSizeBits != 16) throw new UnsupportedOperationException();

        byte[] result = new byte[src.length * 2];
        for (int i = 0; i < src.length - 1; i += 2) {
            result[i * 2] = src[i];
            result[i * 2 + 1] = src[i + 1];
            result[i * 2 + 2] = src[i];
            result[i * 2 + 3] = src[i + 1];
        }

        return result;
    }

    private static byte[] sampleSizeConversion(byte[] src, int fromSampleSize, int toSampleSize) {
        int sampleConversionRatio = toSampleSize / fromSampleSize;
        if (sampleConversionRatio != 1) {
            int fromSampleSizeByte = fromSampleSize / 8;
            int toSampleSizeByte = toSampleSize / 8;

            byte[] result = new byte[src.length * sampleConversionRatio];
            for (int i = 0, j = 0; i < src.length; i += fromSampleSizeByte, j += toSampleSizeByte) {
                float val;
                if (fromSampleSize == 8) {
                    val = src[i];
                    val /= 128f;
                } else if (fromSampleSize == 16) {
                    val = (src[i] & 0xFF) | ((src[i + 1] & 0xFF) << 8);
                    val /= 32768f;
                } else {
                    throw new UnsupportedOperationException("Sample size: " + fromSampleSize);
                }

                if (toSampleSize == 8) {
                    byte s = (byte) (val * 128);
                    if (s < 0) s |= 128;
                    result[j] = s;
                } else if (toSampleSize == 16) {
                    short s = (short) (val * 32768);
                    if (s < 0) s |= 32768;
                    result[j] = (byte) s;
                    result[j + 1] = (byte) (s >>> 8);
                } else {
                    throw new UnsupportedOperationException("Sample size: " + toSampleSize);
                }
            }

            return result;
        } else {
            return src;
        }
    }

    public byte[] convert() {
        byte[] result = sampleSizeConversion(buffer, sampleSizeFrom, sampleSizeTo);
        if (monoToStereo) result = monoToStereo(result, sampleSizeTo);
        return result;
    }

    @Override
    public void write(@NotNull byte[] b, int off, int len) {
        if (buffer == null || buffer.length != len) buffer = new byte[len];
        System.arraycopy(b, off, buffer, 0, len);
    }

    @Override
    @Contract("_ -> fail")
    public void write(int i) {
        throw new UnsupportedOperationException();
    }
}
