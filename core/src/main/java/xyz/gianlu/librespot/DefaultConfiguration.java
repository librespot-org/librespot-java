package xyz.gianlu.librespot;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.TrackHandler;

import java.io.File;

/**
 * @author Gianlu
 */
public final class DefaultConfiguration extends AbsConfiguration {

    //****************//
    //---- PLAYER ----//
    //****************//

    @NotNull
    @Override
    public TrackHandler.AudioQuality preferredQuality() {
        return TrackHandler.AudioQuality.VORBIS_320;
    }

    @Override
    public float normalisationPregain() {
        return 0;
    }

    @Override
    public boolean preloadEnabled() {
        return true;
    }

    //****************//
    //---- CACHE -----//
    //****************//

    @Override
    public boolean cacheEnabled() {
        return true;
    }

    @Override
    public @NotNull File cacheDir() {
        return new File("./cache/");
    }

    @Override
    public boolean doCleanUp() {
        return true;
    }

    @NotNull
    @Override
    public String deviceName() {
        return "librespot-java";
    }

    @NotNull
    @Override
    public Session.DeviceType deviceType() {
        return Session.DeviceType.Computer;
    }
}
