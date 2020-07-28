package xyz.gianlu.librespot.audio;

/**
 * @author Gianlu
 */
public interface HaltListener {
    void streamReadHalted(int chunk, long time);

    void streamReadResumed(int chunk, long time);
}
