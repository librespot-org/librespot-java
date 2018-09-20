package org.librespot.spotify.player;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.proto.Metadata;

import java.io.IOException;

/**
 * @author Gianlu
 */
public class AudioFile {
    private static final Logger LOGGER = Logger.getLogger(AudioFile.class);
    private final ByteString gid;
    private final Session session;
    private ChannelManager.Channel channel;

    public AudioFile(@NotNull Session session, Metadata.Track track) {
        this.session = session;
        this.gid = track.getGid();
    }

    public void open() throws IOException {
        channel = session.channel().requestChunk(gid, 0);
    }
}
