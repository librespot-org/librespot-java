package xyz.gianlu.librespot.player.events;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import xyz.gianlu.librespot.metadata.PlayableId;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.TrackOrEpisode;
import xyz.gianlu.librespot.player.mixing.output.OutputAudioFormat;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Metadata pipe implementation following the Shairport Sync format (https://github.com/mikebrady/shairport-sync-metadata-reader).
 *
 * @author devgianlu
 */
public final class EventsMetadataPipe implements Player.EventsListener, Closeable {
    private static final String TYPE_SSNC = "73736e63";
    private static final String TYPE_CORE = "636f7265";
    private static final String CODE_ASAR = "61736172";
    private static final String CODE_ASAL = "6173616c";
    private static final String CODE_MINM = "6d696e6d";
    private static final String CODE_PVOL = "70766f6c";
    private static final String CODE_PRGR = "70726772";
    private static final String CODE_PICT = "50494354";
    private static final String CODE_PFLS = "70666C73";
    private static final Logger LOGGER = LogManager.getLogger(EventsMetadataPipe.class);
    private final File file;
    private FileOutputStream out;

    public EventsMetadataPipe(@NotNull File file) {
        this.file = file;
    }

    private void safeSend(@NotNull String type, @NotNull String code) {
        safeSend(type, code, (String) null);
    }

    private void safeSend(@NotNull String type, @NotNull String code, @Nullable String payload) {
        safeSend(type, code, payload == null ? null : payload.getBytes(StandardCharsets.UTF_8));
    }

    private void safeSend(@NotNull String type, @NotNull String code, @Nullable byte[] payload) {
        try {
            send(type, code, payload);
        } catch (IOException ex) {
            LOGGER.error("Failed sending metadata through pipe!", ex);
        }
    }

    private void sendImage(@NotNull Player player) {
        byte[] image;
        try {
            image = player.currentCoverImage();
        } catch (IOException ex) {
            LOGGER.warn("Failed downloading image.", ex);
            return;
        }

        if (image == null) {
            LOGGER.warn("No image found in metadata.");
            return;
        }

        safeSend(EventsMetadataPipe.TYPE_SSNC, EventsMetadataPipe.CODE_PICT, image);
    }

    private synchronized void send(@NotNull String type, @NotNull String code, @Nullable byte[] payload) throws IOException {
        if (out == null) out = new FileOutputStream(file);

        if (payload != null && payload.length > 0) {
            out.write(String.format("<item><type>%s</type><code>%s</code><length>%d</length>\n<data encoding=\"base64\">%s</data></item>\n", type, code,
                    payload.length, new String(Base64.getEncoder().encode(payload), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        } else {
            out.write(String.format("<item><type>%s</type><code>%s</code><length>0</length></item>\n", type, code).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void sendProgress(@NotNull Player player) {
        TrackOrEpisode metadata = player.currentMetadata();
        if (metadata == null) return;

        String data = String.format("1/%.0f/%.0f",
                player.time() * OutputAudioFormat.DEFAULT_FORMAT.getSampleRate() / 1000 + 1,
                metadata.duration() * OutputAudioFormat.DEFAULT_FORMAT.getSampleRate() / 1000 + 1);
        safeSend(EventsMetadataPipe.TYPE_SSNC, EventsMetadataPipe.CODE_PRGR, data);
    }

    private void sendTrackInfo(@NotNull Player player) {
        TrackOrEpisode metadata = player.currentMetadata();
        if (metadata == null) return;

        safeSend(EventsMetadataPipe.TYPE_CORE, EventsMetadataPipe.CODE_MINM, metadata.getName());
        safeSend(EventsMetadataPipe.TYPE_CORE, EventsMetadataPipe.CODE_ASAL, metadata.getAlbumName());
        safeSend(EventsMetadataPipe.TYPE_CORE, EventsMetadataPipe.CODE_ASAR, metadata.getArtist());
    }

    private void sendVolume(int value) {
        float xmlValue;
        if (value == 0) xmlValue = -144.0f;
        else xmlValue = (value - Player.VOLUME_MAX) * 30.0f / (Player.VOLUME_MAX - 1);
        String volData = String.format("%.2f,0.00,0.00,0.00", xmlValue);
        safeSend(EventsMetadataPipe.TYPE_SSNC, EventsMetadataPipe.CODE_PVOL, volData);
    }

    private void sendPipeFlush() {
        safeSend(EventsMetadataPipe.TYPE_CORE, EventsMetadataPipe.CODE_PFLS);
    }

    @Override
    public void onContextChanged(@NotNull Player player, @NotNull String newUri) {
    }

    @Override
    public void onTrackChanged(@NotNull Player player, @NotNull PlayableId id, @Nullable TrackOrEpisode metadata) {
        sendPipeFlush();
    }

    @Override
    public void onPlaybackEnded(@NotNull Player player) {
    }

    @Override
    public void onPlaybackPaused(@NotNull Player player, long trackTime) {
        sendPipeFlush();
    }

    @Override
    public void onPlaybackResumed(@NotNull Player player, long trackTime) {
        sendTrackInfo(player);
        sendProgress(player);
        sendImage(player);
    }

    @Override
    public void onTrackSeeked(@NotNull Player player, long trackTime) {
        sendPipeFlush();
        sendProgress(player);
    }

    @Override
    public void onMetadataAvailable(@NotNull Player player, @NotNull TrackOrEpisode metadata) {
        sendTrackInfo(player);
        sendProgress(player);
        sendImage(player);
    }

    @Override
    public void onPlaybackHaltStateChanged(@NotNull Player player, boolean halted, long trackTime) {
    }

    @Override
    public void onInactiveSession(@NotNull Player player, boolean timeout) {
    }

    @Override
    public void onVolumeChanged(@NotNull Player player, @Range(from = 0, to = 1) float volume) {
        sendVolume((int) (volume * Player.VOLUME_MAX));
    }

    @Override
    public void onPanicState(@NotNull Player player) {
    }

    @Override
    public void close() throws IOException {
        if (out != null) out.close();
    }
}
