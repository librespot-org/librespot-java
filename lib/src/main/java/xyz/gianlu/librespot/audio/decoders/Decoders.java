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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.audio.format.SuperAudioFormat;
import xyz.gianlu.librespot.player.decoders.Decoder;
import xyz.gianlu.librespot.player.decoders.SeekableInputStream;

import java.io.IOException;
import java.util.*;

/**
 * @author devgianlu
 */
public final class Decoders {
    private static final Map<SuperAudioFormat, List<Class<? extends Decoder>>> decoders = new EnumMap<>(SuperAudioFormat.class);
    private static final Logger LOGGER = LoggerFactory.getLogger(Decoders.class);

    static {
        registerDecoder(SuperAudioFormat.VORBIS, VorbisDecoder.class);
        registerDecoder(SuperAudioFormat.MP3, Mp3Decoder.class);
    }

    private Decoders() {
    }

    @NotNull
    public static Iterator<Decoder> initDecoder(@NotNull SuperAudioFormat format, @NotNull SeekableInputStream audioIn, float normalizationFactor, int duration) {
        List<Class<? extends Decoder>> list = decoders.get(format);
        if (list == null) list = Collections.emptyList();

        int seekZero = audioIn.position();
        Iterator<Class<? extends Decoder>> iter = list.listIterator();
        return new Iterator<Decoder>() {
            @Override
            public boolean hasNext() {
                return iter.hasNext();
            }

            @Override
            public @Nullable Decoder next() {
                try {
                    audioIn.seek(seekZero);
                } catch (IOException ex) {
                    LOGGER.error("Failed rewinding SeekableInputStream!", ex);
                    return null;
                }

                try {
                    Class<? extends Decoder> clazz = iter.next();
                    return clazz.getConstructor(SeekableInputStream.class, float.class, int.class).newInstance(audioIn, normalizationFactor, duration);
                } catch (ReflectiveOperationException ex) {
                    LOGGER.error("Failed initializing Codec instance for {}", format, ex.getCause());
                    return null;
                }
            }
        };
    }

    public static void registerDecoder(@NotNull SuperAudioFormat format, int index, @NotNull Class<? extends Decoder> clazz) {
        decoders.computeIfAbsent(format, (key) -> new ArrayList<>(5)).add(index, clazz);
    }

    public static void registerDecoder(@NotNull SuperAudioFormat format, @NotNull Class<? extends Decoder> clazz) {
        decoders.computeIfAbsent(format, (key) -> new ArrayList<>(5)).add(clazz);
    }

    public static void unregisterDecoder(@NotNull Class<? extends Decoder> clazz) {
        for (List<Class<? extends Decoder>> list : decoders.values())
            list.remove(clazz);
    }

    public static void removeDecoders(@NotNull SuperAudioFormat format) {
        List<Class<? extends Decoder>> list = decoders.get(format);
        if (list != null) list.clear();
    }
}
