package xyz.gianlu.librespot.player.metadata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.PlayerConfiguration;

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
public final class MetadataPipe {
    public static final String TYPE_SSNC = "73736e63";
    public static final String TYPE_CORE = "636f7265";
    public static final String CODE_ASAR = "61736172";
    public static final String CODE_ASAL = "6173616c";
    public static final String CODE_MINM = "6d696e6d";
    public static final String CODE_PVOL = "70766f6c";
    public static final String CODE_PRGR = "70726772";
    public static final String CODE_PICT = "50494354";
    public static final String CODE_PFLS = "70666C73";
    private static final Logger LOGGER = LogManager.getLogger(MetadataPipe.class);
    private final File file;
    private FileOutputStream out;

    public MetadataPipe(@NotNull PlayerConfiguration conf) {
        file = conf.metadataPipe;
    }

    public void safeSend(@NotNull String type, @NotNull String code) {
        safeSend(type, code, (String) null);
    }

    public void safeSend(@NotNull String type, @NotNull String code, @Nullable String payload) {
        safeSend(type, code, payload == null ? null : payload.getBytes(StandardCharsets.UTF_8));
    }

    public void safeSend(@NotNull String type, @NotNull String code, @Nullable byte[] payload) {
        if (!enabled())
            return;

        try {
            send(type, code, payload);
        } catch (IOException ex) {
            LOGGER.error("Failed sending metadata through pipe!", ex);
        }
    }

    private synchronized void send(@NotNull String type, @NotNull String code, @Nullable byte[] payload) throws IOException {
        if (file == null) return;
        if (out == null) out = new FileOutputStream(file);

        if (payload != null && payload.length > 0) {
            out.write(String.format("<item><type>%s</type><code>%s</code><length>%d</length>\n<data encoding=\"base64\">%s</data></item>\n", type, code,
                    payload.length, new String(Base64.getEncoder().encode(payload), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        } else {
            out.write(String.format("<item><type>%s</type><code>%s</code><length>0</length></item>\n", type, code).getBytes(StandardCharsets.UTF_8));
        }
    }

    public boolean enabled() {
        return file != null;
    }
}
