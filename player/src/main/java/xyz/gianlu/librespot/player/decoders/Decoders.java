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

package xyz.gianlu.librespot.player.decoders;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.audio.GeneralAudioStream;
import xyz.gianlu.librespot.audio.format.SuperAudioFormat;

import java.util.*;

/**
 * @author devgianlu
 */
public final class Decoders {
    private static final Map<SuperAudioFormat, HashSet<Class<? extends Decoder>>> decoders = new EnumMap<>(SuperAudioFormat.class);
    private static final Logger LOGGER = LoggerFactory.getLogger(Decoders.class);

    static {
        registerDecoder(SuperAudioFormat.VORBIS, VorbisDecoder.class);
        registerDecoder(SuperAudioFormat.MP3, Mp3Decoder.class);
    }

    private Decoders() {
    }

    @Nullable
    public static Decoder initDecoder(@NotNull SuperAudioFormat format, @NotNull GeneralAudioStream audioFile, float normalizationFactor, int duration) {
        Set<Class<? extends Decoder>> set = decoders.get(format);
        if (set == null) return null;

        Optional<Class<? extends Decoder>> opt = set.stream().findFirst();
        if (!opt.isPresent()) return null;

        try {
            Class<? extends Decoder> clazz = opt.get();
            return clazz.getConstructor(GeneralAudioStream.class, float.class, int.class).newInstance(audioFile, normalizationFactor, duration);
        } catch (ReflectiveOperationException ex) {
            LOGGER.error("Failed initializing Codec instance for {}", format, ex);
            return null;
        }
    }

    public static void registerDecoder(@NotNull SuperAudioFormat format, @NotNull Class<? extends Decoder> clazz) {
        decoders.computeIfAbsent(format, (key) -> new HashSet<>(5)).add(clazz);
    }

    public static void replaceDecoder(@NotNull SuperAudioFormat format, @NotNull Class<? extends Decoder> clazz) {
        Set<Class<? extends Decoder>> set = decoders.get(format);
        if (set != null) set.clear();
        registerDecoder(format, clazz);
    }

    public static void unregisterDecoder(@NotNull Class<? extends Decoder> clazz) {
        for (Set<Class<? extends Decoder>> set : decoders.values())
            set.remove(clazz);
    }
}
