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

package xyz.gianlu.librespot.player.mixing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.PlayerConfiguration;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.mixing.output.*;

import java.io.Closeable;
import java.io.IOException;

/**
 * @author devgianlu
 */
public final class AudioSink implements Runnable, Closeable {
    private final Object pauseLock = new Object();
    private final SinkOutput output;
    private final MixingLine mixing = new MixingLine();
    private final Thread thread;
    private final Listener listener;
    private volatile boolean closed = false;
    private volatile boolean paused = true;

    /**
     * Creates a new sink from the current {@param conf}. Also sets the initial volume.
     */
    public AudioSink(@NotNull PlayerConfiguration conf, @NotNull Listener listener) {
        this.listener = listener;
        switch (conf.output) {
            case MIXER:
                output = initCustomOutputSink("xyz.gianlu.librespot.player.mixing.output.MixerOutput",
                        conf.mixerSearchKeywords, conf.logAvailableMixers);
                break;
            case PIPE:
                if (conf.outputPipe == null)
                    throw new IllegalArgumentException("Pipe file not configured!");

                output = new PipeOutput(conf.outputPipe);
                break;
            case STDOUT:
                output = new StreamOutput(System.out, false);
                break;
            case CUSTOM:
                if (conf.outputClass == null || conf.outputClass.isEmpty())
                    throw new IllegalArgumentException("Custom output sink class not configured!");

                output = initCustomOutputSink(conf.outputClass, conf.outputClassParams);
                break;
            default:
                throw new IllegalArgumentException("Unknown output: " + conf.output);
        }

        if (conf.bypassSinkVolume) setVolume(Player.VOLUME_MAX);
        else setVolume(conf.initialVolume);

        thread = new Thread(this, "player-audio-sink");
        thread.start();
    }

    @NotNull
    private static SinkOutput initCustomOutputSink(@NotNull String className, Object... params) {
        try {
            Class<?>[] parameterTypes = new Class<?>[params.length];
            for (int i = 0; i < params.length; i++)
                parameterTypes[i] = params[i].getClass();

            Class<?> clazz = Class.forName(className);
            return (SinkOutput) clazz.getConstructor(parameterTypes).newInstance(params);
        } catch (ReflectiveOperationException | ClassCastException ex) {
            throw new IllegalArgumentException("Invalid custom output sink class: " + className, ex);
        }
    }

    public void clearOutputs() {
        mixing.firstOut().clear();
        mixing.secondOut().clear();
    }

    /**
     * @return A free output stream or {@code null} if both are in use.
     */
    @Nullable
    public MixingLine.MixingOutput someOutput() {
        return mixing.someOut();
    }

    /**
     * Resumes the sink.
     */
    public void resume() {
        paused = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    /**
     * Pauses the sink and then releases the underling output if specified by {@param release}.
     */
    public void pause(boolean release) {
        paused = true;
        if (release) output.release();
    }

    /**
     * Flushes the sink.
     */
    public void flush() {
        output.flush();
    }

    /**
     * Sets the volume accordingly.
     *
     * @param volume The volume value from 0 to {@link Player#VOLUME_MAX}, inclusive.
     */
    public void setVolume(int volume) {
        if (volume < 0 || volume > Player.VOLUME_MAX)
            throw new IllegalArgumentException("Invalid volume: " + volume);

        float volumeNorm = ((float) volume) / Player.VOLUME_MAX;
        if (output.setVolume(volumeNorm)) mixing.setGlobalGain(1);
        else mixing.setGlobalGain(volumeNorm);
    }

    @Override
    public void close() {
        closed = true;
        thread.interrupt();

        clearOutputs();
    }

    @Override
    public void run() {
        byte[] buffer = new byte[Codec.BUFFER_SIZE * 2];

        boolean started = false;
        while (!closed) {
            if (paused) {
                output.stop();
                started = false;

                synchronized (pauseLock) {
                    try {
                        pauseLock.wait();
                    } catch (InterruptedException ex) {
                        break;
                    }
                }
            } else {
                try {
                    if (!started || mixing.switchFormat) {
                        OutputAudioFormat format = mixing.getFormat();
                        if (format != null) started = output.start(format);
                        mixing.switchFormat = false;
                    }

                    int count = mixing.read(buffer);
                    output.write(buffer, 0, count);
                } catch (IOException | SinkException ex) {
                    if (closed) break;

                    pause(true);
                    listener.sinkError(ex);
                }
            }
        }

        try {
            output.drain();
            output.close();
        } catch (IOException ignored) {
        }
    }

    public interface Listener {
        void sinkError(@NotNull Exception ex);
    }
}
