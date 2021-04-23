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

package xyz.gianlu.librespot.player.codecs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.audio.GeneralAudioStream;
import xyz.gianlu.librespot.audio.NormalizationData;
import xyz.gianlu.librespot.audio.format.SuperAudioFormat;
import xyz.gianlu.librespot.player.PlayerConfiguration;

import java.util.*;

/**
 * @author devgianlu
 */
public final class Codecs {
    private static final Map<SuperAudioFormat, HashSet<Class<? extends Codec>>> codecs = new EnumMap<>(SuperAudioFormat.class);
    private static final Logger LOGGER = LoggerFactory.getLogger(Codecs.class);

    static {
        registerCodec(SuperAudioFormat.VORBIS, VorbisCodec.class);
        registerCodec(SuperAudioFormat.MP3, Mp3Codec.class);
    }

    private Codecs() {
    }

    @Nullable
    public static Codec initCodec(@NotNull SuperAudioFormat format, @NotNull GeneralAudioStream audioFile, @Nullable NormalizationData normalizationData, @NotNull PlayerConfiguration conf, int duration) {
        Set<Class<? extends Codec>> set = codecs.get(format);
        if (set == null) return null;

        Optional<Class<? extends Codec>> opt = set.stream().findFirst();
        if (!opt.isPresent()) return null;

        try {
            Class<? extends Codec> clazz = opt.get();
            return clazz.getConstructor(GeneralAudioStream.class, NormalizationData.class, PlayerConfiguration.class, int.class).newInstance(audioFile, normalizationData, conf, duration);
        } catch (ReflectiveOperationException ex) {
            LOGGER.error("Failed initializing Codec instance for {}", format, ex);
            return null;
        }
    }

    public static void registerCodec(@NotNull SuperAudioFormat format, @NotNull Class<? extends Codec> clazz) {
        codecs.computeIfAbsent(format, (key) -> new HashSet<>(5)).add(clazz);
    }

    public static void replaceCodecs(@NotNull SuperAudioFormat format, @NotNull Class<? extends Codec> clazz) {
        Set<Class<? extends Codec>> set = codecs.get(format);
        if (set != null) set.clear();
        registerCodec(format, clazz);
    }

    public static void unregisterCodec(@NotNull Class<? extends Codec> clazz) {
        for (Set<Class<? extends Codec>> set : codecs.values())
            set.remove(clazz);
    }
}
