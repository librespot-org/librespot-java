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

package xyz.gianlu.librespot.audio.storage;

import com.google.protobuf.ByteString;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.PacketsReceiver;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.crypto.Packet;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Gianlu
 */
public class ChannelManager implements Closeable, PacketsReceiver {
    public static final int CHUNK_SIZE = 128 * 1024;
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelManager.class);
    private final Map<Short, Channel> channels = new HashMap<>();
    private final AtomicInteger seqHolder = new AtomicInteger(0);
    private final ExecutorService executorService = Executors.newCachedThreadPool(new NameThreadFactory(r -> "channel-queue-" + r.hashCode()));
    private final Session session;

    public ChannelManager(@NotNull Session session) {
        this.session = session;
    }

    void requestChunk(@NotNull ByteString fileId, int index, @NotNull AudioFile file) throws IOException {
        int start = index * CHUNK_SIZE / 4;
        int end = (index + 1) * CHUNK_SIZE / 4;

        Channel channel = new Channel(file, index);
        channels.put(channel.id, channel);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);

        out.writeShort(channel.id);
        out.writeInt(0x00000000);
        out.writeInt(0x00000000);
        out.writeInt(0x00004e20);
        out.writeInt(0x00030d40);
        fileId.writeTo(out);
        out.writeInt(start);
        out.writeInt(end);

        session.send(Packet.Type.StreamChunk, bytes.toByteArray());
    }

    @Override
    public void dispatch(@NotNull Packet packet) {
        ByteBuffer payload = ByteBuffer.wrap(packet.payload);
        if (packet.is(Packet.Type.StreamChunkRes)) {
            short id = payload.getShort();
            Channel channel = channels.get(id);
            if (channel == null) {
                LOGGER.warn("Couldn't find channel, id: {}, received: {}", id, packet.payload.length);
                return;
            }

            channel.addToQueue(payload);
        } else if (packet.is(Packet.Type.ChannelError)) {
            short id = payload.getShort();
            Channel channel = channels.get(id);
            if (channel == null) {
                LOGGER.warn("Dropping channel error, id: {}, code: {}", id, payload.getShort());
                return;
            }

            channel.streamError(payload.getShort());
        } else {
            LOGGER.warn("Couldn't handle packet, cmd: {}, payload: {}", packet.type(), Utils.bytesToHex(packet.payload));
        }
    }

    @Override
    public void close() {
        executorService.shutdown();
    }

    public class Channel {
        public final short id;
        private final BlockingQueue<ByteBuffer> queue = new LinkedBlockingQueue<>();
        private final AudioFile file;
        private final int chunkIndex;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(CHUNK_SIZE);
        private volatile boolean header = true;

        private Channel(@NotNull AudioFile file, int chunkIndex) {
            this.file = file;
            this.chunkIndex = chunkIndex;
            synchronized (seqHolder) {
                id = (short) seqHolder.getAndIncrement();
            }

            executorService.execute(new Handler());
        }

        /**
         * @return Whether the channel can be closed
         */
        private boolean handle(@NotNull ByteBuffer payload) throws IOException {
            if (payload.remaining() == 0) {
                if (!header) {
                    synchronized (buffer) {
                        file.writeChunk(buffer.toByteArray(), chunkIndex, false);
                        return true;
                    }
                }

                LOGGER.trace("Received empty chunk, skipping.");
                return false;
            }

            if (header) {
                short length;
                while (payload.remaining() > 0 && (length = payload.getShort()) > 0) {
                    byte headerId = payload.get();
                    byte[] headerData = new byte[length - 1];
                    payload.get(headerData);
                    file.writeHeader(headerId, headerData, false);
                }

                header = false;
            } else {
                byte[] bytes = new byte[payload.remaining()];
                payload.get(bytes);
                synchronized (buffer) {
                    buffer.write(bytes);
                }
            }

            return false;
        }

        private void addToQueue(@NotNull ByteBuffer payload) {
            queue.add(payload);
        }

        void streamError(short code) {
            file.streamError(chunkIndex, code);
        }

        private class Handler implements Runnable {

            @Override
            public void run() {
                LOGGER.trace("ChannelManager.Handler is starting");

                while (true) {
                    try {
                        if (handle(queue.take())) {
                            channels.remove(id);
                            break;
                        }
                    } catch (IOException ex) {
                        LOGGER.error("Failed handling packet!", ex);
                    } catch (InterruptedException ex) {
                        break;
                    }
                }

                LOGGER.trace("ChannelManager.Handler is shutting down");
            }
        }
    }
}
