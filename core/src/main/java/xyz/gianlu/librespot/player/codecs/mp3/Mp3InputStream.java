package xyz.gianlu.librespot.player.codecs.mp3;

import javazoom.jl.decoder.*;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * @author Gianlu
 */
public class Mp3InputStream extends InputStream {
    private final static int MAX_READ_SIZE = 96 * 1024;
    private final static int MP3_BUFFER_SIZE = 128 * 1024;
    private final InputStream in;
    private final boolean bigEndian;
    private final Bitstream bitstream;
    private final ByteBuffer buffer;
    private final MP3Decoder decoder;
    private final int channels;
    private final int sampleRate;
    private final OutputBuffer outputBuffer;
    private boolean eos;
    private int bufferIndex;

    public Mp3InputStream(@NotNull InputStream in, float normalisationPregain) throws BitstreamException {
        this.in = in;

        bigEndian = ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN);
        eos = false;
        bufferIndex = 0;
        bitstream = new Bitstream(in);
        buffer = ByteBuffer.allocateDirect(MP3_BUFFER_SIZE).order(ByteOrder.nativeOrder());
        buffer.limit(0);
        decoder = new MP3Decoder();

        Header header = bitstream.readFrame();
        channels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
        outputBuffer = new OutputBuffer(channels, bigEndian);
        decoder.setOutputBuffer(outputBuffer);
        sampleRate = header.getSampleRate();
        bitstream.unreadFrame();

        outputBuffer.setReplayGainScale(normalisationPregain);
    }

    public boolean isBigEndian() {
        return bigEndian;
    }

    private void readMP3() throws IOException {
        if (eos) return;

        int total = 0;
        while (total < MAX_READ_SIZE) {
            Header header;
            try {
                header = bitstream.readFrame();
            } catch (BitstreamException ex) {
                throw new IOException(ex);
            }

            if (header == null) {
                eos = true;
                break;
            }

            try {
                decoder.decodeFrame(header, bitstream);
            } catch (DecoderException ex) {
                throw new IOException(ex);
            }

            bitstream.closeFrame();
            int bytesRead = outputBuffer.reset();
            buffer.put(outputBuffer.getBuffer(), 0, bytesRead);
            total += bytesRead;
        }

        // Flip the buffer once after reading
        buffer.flip();
    }

    public int getChannels() {
        return channels;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    @Override
    public int available() throws IOException {
        return in.available();
    }

    @Override
    public void close() throws IOException {
        try {
            bitstream.close();
        } catch (BitstreamException ex) {
            throw new IOException(ex);
        }

        in.close();
    }

    @Override
    public int read() throws IOException {
        // Have we read past the limit of the buffer?
        if (bufferIndex >= buffer.limit()) {
            // End of stream when we try to read past the limit
            // since there maybe data in MP3 buffer
            if (eos) return -1;

            buffer.clear();
            bufferIndex = 0;
            readMP3();
        }

        // Get the value from the MP3 buffer
        int value = buffer.get(bufferIndex++);
        if (value < 0) value = 256 + value; // Must be in the range 0 to 255
        return value;
    }
}
