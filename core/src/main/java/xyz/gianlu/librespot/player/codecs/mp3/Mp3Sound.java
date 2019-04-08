package xyz.gianlu.librespot.player.codecs.mp3;

import org.jetbrains.annotations.NotNull;

import javax.sound.sampled.AudioFormat;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * A sound object, that represents an input stream of uncompressed PCM sound data samples, decoded from encoded MPEG data.
 * <p>
 * To create a sound object from encoded MPEG data (MP1/MP2/MP3), simply use {@link #Mp3Sound(InputStream)}. The decoding process will be done as data is read from this stream. You may also, as a convenience, write all the (remaining) decoded data into an {@link OutputStream} using {@link #decodeFullyInto(OutputStream)}.
 * <p>
 * You may use the several metadata functions such as {@link #getSamplingFrequency()} to get data about the sound. You may use {@link #getAudioFormat()} to get the sound audio format, to be used with the {@link javax.sound.sampled} API.
 *
 * @author delthas
 * @author devgianlu
 *
 * @see Mp3Sound#Mp3Sound(InputStream)
 * @see Mp3Sound#decodeFullyInto(OutputStream)
 */
public final class Mp3Sound extends FilterInputStream {
    private Decoder.SoundData soundData;
    private int index;
    private AudioFormat audioFormat;

    /**
     * Creates a new Sound, that will read from the specified encoded MPEG data stream.
     * <p>
     * This method will try to read the very beginning of the MPEG stream (i.e. 1 MPEG frame) to get its sampling frequency and various other metadata. <b>A stream containing no MPEG data frames/a zero duration MPEG data source will be considered as invalid and will throw {@link IOException}.</b>
     * <p>
     * This method will not read or decode the file fully, which means it doesn't block and is very fast (as opposed to {@link #decodeFullyInto(OutputStream)}; you probably don't need to execute this method in a specific background thread.
     * <p>It is only when reading from this stream that the decoding process will take place (as you read from the stream). <b>The decoding process is quite CPU-intensive, though, so you are encouraged to use a background thread/other multithreading techniques to read from the stream without blocking the whole application.</b>
     * <p>
     * The various metadata methods such as {@link #getSamplingFrequency()} and {@link #isStereo()} may be called as soon as this object is instantiated (i.e. may be called at any time during the object lifetime).
     *
     * <b>The data layout is as follows (this is a contract that won't change):</b>
     * <br>The decoded PCM sound data is stored as a contiguous stream of 16-bit little-endian signed samples (2 bytes per sample).
     * <ul>
     * <li>If the sound is in stereo mode, then the samples will be interleaved, e.g. {@code left_sample_0 (2 bytes), right_sample_0 (2 bytes), left_sample_1 (2 bytes), right_sample_1 (2 bytes), ...}
     * <li>If the sound is in mono mode, then the samples will be contiguous, e.g. {@code sample_0 (2 bytes), sample_1 (2 bytes), ...}
     * </ul>
     *
     * @param in The input stream from which to read the encoded MPEG data, must be non-null.
     * @throws IOException If an {@link IOException} is thrown when reading the underlying stream, or if there's an unexpected EOF during an MPEG frame, or if there's an error while decoding the MPEG data, e.g. if there's no MPEG data in the specified stream.
     */
    public Mp3Sound(InputStream in) throws IOException {
        super(Objects.requireNonNull(in, "The specified InputStream must be non-null!"));
        soundData = Decoder.init(in);
        if (soundData == null) {
            throw new IOException("No MPEG data in the specified input stream!");
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Refer to this class documentation for the decoded data layout.
     */
    @Override
    public int read() throws IOException {
        if (index == -1)
            return -1;
        if (index == soundData.samplesBuffer.length) {
            if (!Decoder.decodeFrame(soundData)) {
                index = -1;
                soundData.samplesBuffer = null;
                return -1;
            }
            index = 1;
            return soundData.samplesBuffer[0] & 0xFF;
        }
        return soundData.samplesBuffer[index++] & 0xFF;
    }


    /**
     * {@inheritDoc}
     * <p>
     * Refer to this class documentation for the decoded data layout.
     */
    @Override
    public int read(@NotNull byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Refer to this class documentation for the decoded data layout.
     */
    @Override
    public int read(@NotNull byte[] b, int off, int len) throws IOException {
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }
        if (index == -1)
            return -1;
        int len_ = len;
        while (len > 0) {
            if (index == soundData.samplesBuffer.length) {
                if (!Decoder.decodeFrame(soundData)) {
                    index = -1;
                    soundData.samplesBuffer = null;
                    return len_ == len ? -1 : len_ - len;
                }
                index = 0;
            }
            int remaining = soundData.samplesBuffer.length - index;
            if (remaining > 0) {
                if (remaining >= len) {
                    System.arraycopy(soundData.samplesBuffer, index, b, off, len);
                    index += len;
                    return len_;
                }
                System.arraycopy(soundData.samplesBuffer, index, b, off, remaining);
                off += remaining;
                len -= remaining;
                index = soundData.samplesBuffer.length;
            }
        }
        throw new IllegalStateException("Shouldn't happen (internal error)");
    }

    /**
     * {@inheritDoc}
     *
     * <b>Fast MPEG seeking isn't implemented yet, so calling this method will still compute all frames to be skipped.</b>
     */
    @Override
    public long skip(long n) throws IOException {
        // TODO add MPEG seeking
        return super.skip(n);
    }

    /**
     * {@inheritDoc}
     *
     * <b>This method returns the number of bytes that can be read until a new MPEG frame has to be read and decoded/processed.</b>
     */
    @Override
    public int available() throws IOException {
        if (soundData.samplesBuffer == null)
            return 0;
        return soundData.samplesBuffer.length - index;
    }

    /**
     * Closes the underlying input stream and frees up allocated memory.
     * <p>
     * You may still call metadata-related methods (e.g. {@link #isStereo()}) after calling this method.
     *
     * @throws IOException If an {@link IOException} is thrown when closing the underlying stream.
     */
    @Override
    public void close() throws IOException {
        if (in != null) {
            in.close();
            in = null;
            soundData.samplesBuffer = null;
        }
        index = -1;
    }

    /**
     * Does nothing.
     * <p>
     * Setting a mark and resetting to the mark isn't supported.
     *
     * @param readlimit Ignored.
     */
    @Override
    public synchronized void mark(int readlimit) {
        super.mark(readlimit);
    }

    /**
     * Throws an IOException.
     * <p>
     * Setting a mark and resetting to the mark isn't supported.
     *
     * @throws IOException Always, because setting a mark and resetting to it isn't supported.
     */
    @Override
    public synchronized void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    /**
     * Returns false.
     * <p>
     * Setting a mark and resetting to the mark isn't supported.
     *
     * @return false, always.
     */
    @Override
    public boolean markSupported() {
        return false;
    }


    /**
     * Fully copy the remaining bytes of this (decoded PCM sound data samples) stream into the specified {@link OutputStream}, that is, fully decodes the rest of the sound and copies the decoded data into the {@link OutputStream}.
     * <p>
     * This method is simply a convenience wrapper for the following code: {@code copy(this, os)}, where {@code copy} is a method that would fully copy a stream into another.
     * <p>
     * This method <b>is blocking</b> and the MPEG decoding process <b>might take a long time, e.g. a few seconds for a sample music track</b>. You are encouraged to call this method e.g. from a background thread.
     * <p>
     * The exact layout of the PCM data produced by this stream is described in this class documentation.
     *
     * @param os The output stream in which to put the decoded raw PCM sound samples, must be non-null.
     * @return The number of <b>BYTES</b> that were written into the output steam. <b>This is different from the number of samples that were written.</b>
     * @throws IOException If an {@link IOException} is thrown when reading the underlying stream, or if there's an unexpected EOF during an MPEG frame, or if there's an error while decoding the MPEG data.
     */
    public int decodeFullyInto(OutputStream os) throws IOException {
        Objects.requireNonNull(os);
        if (index == -1)
            return 0;
        int remaining = soundData.samplesBuffer.length - index;
        if (remaining > 0) {
            os.write(soundData.samplesBuffer, index, remaining);
        }
        int read = remaining;
        while (!Decoder.decodeFrame(soundData)) {
            os.write(soundData.samplesBuffer);
            read += soundData.samplesBuffer.length;
        }
        soundData.samplesBuffer = null;
        index = -1;
        return read;
    }

    /**
     * Returns the sampling frequency of this sound, that is its of samples per second, in Hertz (Hz).
     * <p>
     * For example for a 48kHz sound this would return {@code 48000}.
     *
     * @return The sampling frequency of the sound in Hertz.
     */
    public int getSamplingFrequency() {
        return soundData.frequency;
    }

    /**
     * Returns {@code true} if the sound is in stereo mode, that is if it has exactly two channels, and returns false otherwise, that is if it has exactly one channel.
     *
     * @return {@code true} if the sound is in stereo mode.
     */
    public boolean isStereo() {
        return soundData.stereo == 1;
    }

    /**
     * Returns the {@link AudioFormat} of this sound, to be used with the {@link javax.sound.sampled} API.
     * <p>
     * You may refer to the project README on Github for example uses of this method.
     *
     * @return The {@link AudioFormat} of this sound.
     */
    public AudioFormat getAudioFormat() {
        if (audioFormat == null) {
            audioFormat = new AudioFormat(getSamplingFrequency(), 16, isStereo() ? 2 : 1, true, false);
        }
        return audioFormat;
    }

}
