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

import com.spotify.metadata.Metadata;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.metadata.LocalId;
import xyz.gianlu.librespot.metadata.PlayableId;

/**
 * @author devgianlu
 */
public final class MetadataWrapper {
    public final PlayableId id;
    public final Metadata.Track track;
    public final Metadata.Episode episode;
    private final LocalId localTrack;

    @Contract("null, null, null -> fail")
    public MetadataWrapper(@Nullable Metadata.Track track, @Nullable Metadata.Episode episode, @Nullable LocalId localTrack) {
        if (track == null && episode == null && localTrack == null) throw new IllegalArgumentException();

        this.track = track;
        this.episode = episode;
        this.localTrack = localTrack;

        if (track != null) id = PlayableId.from(track);
        else if (episode != null) id = PlayableId.from(episode);
        else id = localTrack;
    }

    public boolean isTrack() {
        return track != null;
    }

    public boolean isEpisode() {
        return episode != null;
    }

    public boolean isLocalTrack() {
        return localTrack != null;
    }

    /**
     * @return The track/episode duration
     */
    public int duration() {
        if (track != null) return track.getDuration();
        else if (episode != null) return episode.getDuration();
        else return localTrack.duration();
    }

    /**
     * @return The track album cover or episode cover
     */
    @Nullable
    public Metadata.ImageGroup getCoverImage() {
        if (track != null) {
            if (track.hasAlbum() && track.getAlbum().hasCoverGroup())
                return track.getAlbum().getCoverGroup();
        } else if (episode != null) {
            if (episode.hasCoverImage())
                return episode.getCoverImage();
        } else {
            // TODO: Fetch album image from track file
        }

        return null;
    }

    /**
     * @return The track/episode name
     */
    @NotNull
    public String getName() {
        if (track != null) return track.getName();
        else if (episode != null) return episode.getName();
        else return localTrack.fileName();
    }

    /**
     * @return The track album name or episode show name
     */
    @NotNull
    public String getAlbumName() {
        if (track != null) return track.getAlbum().getName();
        else if (episode != null) return episode.getShow().getName();
        else return localTrack.album();
    }

    /**
     * @return The track artists or show publisher
     */
    @NotNull
    public String getArtist() {
        if (track != null) return Utils.artistsToString(track.getArtistList());
        else if (episode != null) return episode.getShow().getPublisher();
        else return localTrack.artist();
    }
}
