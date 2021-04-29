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

import com.spotify.metadata.Metadata.AudioFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Gianlu
 */
public enum AudioQuality {
    NORMAL, HIGH, VERY_HIGH;

    @NotNull
    private static AudioQuality getQuality(@NotNull AudioFile.Format format) {
        switch (format) {
            case MP3_96:
            case OGG_VORBIS_96:
            case AAC_24_NORM:
                return NORMAL;
            case MP3_160:
            case MP3_160_ENC:
            case OGG_VORBIS_160:
            case AAC_24:
                return HIGH;
            case MP3_320:
            case MP3_256:
            case OGG_VORBIS_320:
            case AAC_48:
                return VERY_HIGH;
            default:
                throw new IllegalArgumentException("Unknown format: " + format);
        }
    }

    public @NotNull List<AudioFile> getMatches(@NotNull List<AudioFile> files) {
        List<AudioFile> list = new ArrayList<>(files.size());
        for (AudioFile file : files) {
            if (file.hasFormat() && getQuality(file.getFormat()) == this)
                list.add(file);
        }

        return list;
    }
}
