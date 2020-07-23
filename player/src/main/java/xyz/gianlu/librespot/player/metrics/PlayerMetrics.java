package xyz.gianlu.librespot.player.metrics;

import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.audio.PlayableContentFeeder;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.codecs.Mp3Codec;
import xyz.gianlu.librespot.player.codecs.VorbisCodec;
import xyz.gianlu.librespot.player.crossfade.CrossfadeController;

import javax.sound.sampled.AudioFormat;

/**
 * @author devgianlu
 */
public final class PlayerMetrics {
    public final PlayableContentFeeder.Metrics contentMetrics;
    public int decodedLength = 0;
    public int size = 0;
    public int bitrate = 0;
    public int duration = 0;
    public String encoding = null;
    public int fadeOverlap = 0;
    public String transition = "none";
    public int decryptTime = 0;

    public PlayerMetrics(@Nullable PlayableContentFeeder.Metrics contentMetrics, @Nullable CrossfadeController crossfade, @Nullable Codec codec) {
        this.contentMetrics = contentMetrics;

        if (codec != null) {
            size = codec.size();
            duration = codec.duration();
            decodedLength = codec.decodedLength();
            decryptTime = codec.decryptTimeMs();

            AudioFormat format = codec.getAudioFormat();
            bitrate = (int) (format.getFrameRate() * format.getFrameSize());

            if (codec instanceof VorbisCodec) encoding = "vorbis";
            else if (codec instanceof Mp3Codec) encoding = "mp3";
        }

        if (crossfade != null) {
            transition = "crossfade";
            fadeOverlap = crossfade.fadeOverlap();
        }
    }
}
