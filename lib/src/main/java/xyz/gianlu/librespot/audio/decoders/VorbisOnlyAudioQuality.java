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

import com.spotify.metadata.Metadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.audio.format.AudioQualityPicker;
import xyz.gianlu.librespot.audio.format.SuperAudioFormat;
import xyz.gianlu.librespot.common.Utils;

import java.util.List;

/**
 * @author Gianlu
 */
public final class VorbisOnlyAudioQuality implements AudioQualityPicker {
    private static final Logger LOGGER = LoggerFactory.getLogger(VorbisOnlyAudioQuality.class);
    private final AudioQuality preferred;

    public VorbisOnlyAudioQuality(@NotNull AudioQuality preferred) {
        this.preferred = preferred;
    }

    @Nullable
    public static Metadata.AudioFile getVorbisFile(@NotNull List<Metadata.AudioFile> files) {
        for (Metadata.AudioFile file : files) {
            if (file.hasFormat() && SuperAudioFormat.get(file.getFormat()) == SuperAudioFormat.VORBIS)
                return file;
        }

        return null;
    }

    @Override
    public @Nullable Metadata.AudioFile getFile(@NotNull List<Metadata.AudioFile> files) {
        List<Metadata.AudioFile> matches = preferred.getMatches(files);
        Metadata.AudioFile vorbis = getVorbisFile(matches);
        if (vorbis == null) {
            vorbis = getVorbisFile(files);
            if (vorbis != null)
                LOGGER.warn("Using {} because preferred {} couldn't be found.", vorbis.getFormat(), preferred);
            else
                LOGGER.error("Couldn't find any Vorbis file, available: {}", Utils.formatsToString(files));
        }

        return vorbis;
    }
}
