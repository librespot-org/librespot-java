package xyz.gianlu.librespot.player.mixing.output;

/**
 * @author devgianlu
 */
public final class OutputAudioFormat {
    public static final OutputAudioFormat DEFAULT_FORMAT = new OutputAudioFormat(44100, 16, 2, true, false);
    private final String encoding;
    private final float sampleRate;
    private final int sampleSizeInBits;
    private final int channels;
    private final int frameSize;
    private final float frameRate;
    private final boolean bigEndian;

    public OutputAudioFormat(float sampleRate, int sampleSizeInBits, int channels, boolean signed, boolean bigEndian) {
        this.encoding = signed ? "PCM_SIGNED" : "PCM_UNSIGNED";
        this.sampleRate = sampleRate;
        this.sampleSizeInBits = sampleSizeInBits;
        this.channels = channels;
        this.frameSize = (channels == -1 || sampleSizeInBits == -1) ? -1 : ((sampleSizeInBits + 7) / 8) * channels;
        this.frameRate = sampleRate;
        this.bigEndian = bigEndian;
    }

    public int getFrameSize() {
        return frameSize;
    }

    public float getSampleRate() {
        return sampleRate;
    }

    public boolean isBigEndian() {
        return bigEndian;
    }

    public int getSampleSizeInBits() {
        return sampleSizeInBits;
    }

    public int getChannels() {
        return channels;
    }

    public String getEncoding() {
        return encoding;
    }

    public float getFrameRate() {
        return frameRate;
    }

    public boolean matches(OutputAudioFormat format) {
        return format.getEncoding().equals(getEncoding())
                && (format.getChannels() == -1 || format.getChannels() == getChannels())
                && (format.getSampleRate() == (float) -1 || format.getSampleRate() == getSampleRate())
                && (format.getSampleSizeInBits() == -1 || format.getSampleSizeInBits() == getSampleSizeInBits())
                && (format.getFrameRate() == (float) -1 || format.getFrameRate() == getFrameRate())
                && (format.getFrameSize() == -1 || format.getFrameSize() == getFrameSize())
                && (getSampleSizeInBits() <= 8 || format.isBigEndian() == isBigEndian());
    }
}
