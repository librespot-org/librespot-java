package xyz.gianlu.librespot.player.feeders;

/**
 * @author Gianlu
 */
public interface HaltListener {
    void streamReadHalted(int chunk, long time);

    void streamReadResumed(int chunk, long time);
}
