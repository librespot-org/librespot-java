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

package xyz.gianlu.librespot.audio;

import com.google.protobuf.ByteString;
import com.spotify.metadata.Metadata;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;

/**
 * @author Gianlu
 */
public final class StreamId {
    private final ByteString fileId;
    private final ByteString episodeGid;

    public StreamId(@NotNull Metadata.AudioFile file) {
        this.fileId = file.getFileId();
        this.episodeGid = null;
    }

    public StreamId(@NotNull Metadata.Episode episode) {
        this.fileId = null;
        this.episodeGid = episode.getGid();
    }

    @NotNull
    public String getFileId() {
        if (fileId == null) throw new IllegalStateException("Not a file!");
        return Utils.bytesToHex(fileId);
    }

    public boolean isEpisode() {
        return episodeGid != null;
    }

    @NotNull
    public String getEpisodeGid() {
        if (episodeGid == null) throw new IllegalStateException("Not an episode!");
        return Utils.bytesToHex(episodeGid);
    }
}
