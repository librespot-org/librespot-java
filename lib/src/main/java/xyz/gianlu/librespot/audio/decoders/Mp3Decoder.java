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

package xyz.gianlu.librespot.audio.decoders;

import javazoom.jl.decoder.*;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.player.decoders.Decoder;
import xyz.gianlu.librespot.player.decoders.SeekableInputStream;
import xyz.gianlu.librespot.player.mixing.output.OutputAudioFormat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * @author Gianlu
 */
public final class Mp3Decoder extends Decoder {
    private final byte[] buffer = new byte[2 * Decoder.BUFFER_SIZE];
    private final Mp3InputStream in;

    public Mp3Decoder(@NotNull SeekableInputStream audioIn, float normalizationFactor, int duration) throws IOException, BitstreamException {
        super(audioIn, normalizationFactor, duration);

        skipMp3Tags(audioIn);
        this.in = new Mp3InputStream(audioIn, normalizationFactor);

        audioIn.mark(-1);
        setAudioFormat(new OutputAudioFormat(in.getSampleRate(), 16, in.getChannels(), true, false));
    }

    private static void skipMp3Tags(@NotNull InputStream in) throws IOException {
        byte[] buffer = new byte[3];
        if (in.read(buffer) != 3)
            throw new IOException();

        if (!new String(buffer).equals("ID3")) {
            in.reset();
            return;
        }

        if (in.skip(3) != 3)
            throw new IOException();

        buffer = new byte[4];
        if (in.read(buffer) != 4)
            throw new IOException();

        int tagSize = (buffer[0] << 21) + (buffer[1] << 14) + (buffer[2] << 7) + buffer[3];
        tagSize -= 10;
        if (in.skip(tagSize) != tagSize)
            throw new IOException();
    }

    @Override
    public int readInternal(@NotNull OutputStream out) throws IOException {
        if (closed) return -1;

        int count = in.read(buffer);
        if (count == -1) return -1;
        out.write(buffer, 0, count);
        out.flush();
        return count;
    }

    @Override
    public int time() throws CannotGetTimeException {
        throw new CannotGetTimeException("No way to get time on MP3 stream");
    }

    @Override
    public void close() throws IOException {
        in.close();
        super.close();
    }

    private static class Mp3InputStream extends InputStream {
        private final static int MAX_READ_SIZE = 96 * 1024;
        private final static int MP3_BUFFER_SIZE = 128 * 1024;
        private final InputStream in;
        private final Bitstream bitstream;
        private final ByteBuffer buffer;
        private final MP3Decoder decoder;
        private final int channels;
        private final int sampleRate;
        private final OutputBuffer outputBuffer;
        private boolean eos;
        private int bufferIndex;

        /**
         * Initializes the stream, reads the first header, retrieves important stream information and unreads the header
         *
         * @param in                   The MP3 stream
         * @param normalisationPregain The normalisation pregain applied to the raw PCM
         */
        Mp3InputStream(@NotNull InputStream in, float normalisationPregain) throws BitstreamException {
            this.in = in;

            eos = false;
            bufferIndex = 0;
            bitstream = new Bitstream(in);
            buffer = ByteBuffer.allocateDirect(MP3_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            buffer.limit(0);
            decoder = new MP3Decoder();

            Header header = bitstream.readFrame();
            channels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
            outputBuffer = new OutputBuffer(channels, false);
            decoder.setOutputBuffer(outputBuffer);
            sampleRate = header.getSampleRate();
            bitstream.unreadFrame();

            outputBuffer.setReplayGainScale(normalisationPregain);
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
                } catch (javazoom.jl.decoder.DecoderException ex) {
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

        int getChannels() {
            return channels;
        }

        int getSampleRate() {
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
            if (value < 0) value = 256 + value;
            return value;
        }
    }
}
