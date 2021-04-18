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

package xyz.gianlu.librespot.player;

import com.spotify.metadata.Metadata;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.metadata.PlayableId;

/**
 * @author devgianlu
 */
public final class TrackOrEpisode {
    public final PlayableId id;
    public final Metadata.Track track;
    public final Metadata.Episode episode;

    @Contract("null, null -> fail")
    public TrackOrEpisode(@Nullable Metadata.Track track, @Nullable Metadata.Episode episode) {
        if (track == null && episode == null) throw new IllegalArgumentException();

        this.track = track;
        this.episode = episode;

        if (track != null) id = PlayableId.from(track);
        else id = PlayableId.from(episode);
    }

    public boolean isTrack() {
        return track != null;
    }

    public boolean isEpisode() {
        return episode != null;
    }

    /**
     * @return The track/episode duration
     */
    public int duration() {
        return track != null ? track.getDuration() : episode.getDuration();
    }

    /**
     * @return The track album cover or episode cover
     */
    @Nullable
    public Metadata.ImageGroup getCoverImage() {
        if (track != null) {
            if (track.hasAlbum() && track.getAlbum().hasCoverGroup())
                return track.getAlbum().getCoverGroup();
        } else {
            if (episode.hasCoverImage())
                return episode.getCoverImage();
        }

        return null;
    }

    /**
     * @return The track/episode name
     */
    @NotNull
    public String getName() {
        return track != null ? track.getName() : episode.getName();
    }

    /**
     * @return The track album name or episode show name
     */
    @NotNull
    public String getAlbumName() {
        return track != null ? track.getAlbum().getName() : episode.getShow().getName();
    }

    /**
     * @return The track artists or show publisher
     */
    @NotNull
    public String getArtist() {
        return track != null ? Utils.artistsToString(track.getArtistList()) : episode.getShow().getPublisher();
    }
}
