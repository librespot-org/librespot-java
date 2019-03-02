package xyz.gianlu.librespot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.PlayerRunner;
import xyz.gianlu.librespot.player.StreamFeeder;

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
    public StreamFeeder.AudioQuality preferredQuality() {
        return StreamFeeder.AudioQuality.VORBIS_160;
    }

    @Override
    public float normalisationPregain() {
        return 0;
    }

    @Override
    public boolean defaultUnshuffleBehaviour() {
        return false;
    }

    @Override
    public @NotNull String[] mixerSearchKeywords() {
        return new String[0];
    }

    @Override
    public boolean logAvailableMixers() {
        return true;
    }

    @Override
    public int initialVolume() {
        return PlayerRunner.VOLUME_MAX;
    }

    @Override
    public boolean autoplayEnabled() {
        return true;
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

    @Override
    public @Nullable String authUsername() {
        return null;
    }

    @Override
    public @Nullable String authPassword() {
        return null;
    }

    @Override
    public @Nullable String authBlob() {
        return null;
    }

    @NotNull
    @Override
    public Strategy authStrategy() {
        return Strategy.ZEROCONF;
    }

    @Override
    public boolean zeroconfListenAll() {
        return true;
    }

    @Override
    public int zeroconfListenPort() {
        return -1;
    }

    @Override
    public @NotNull String[] zeroconfInterfaces() {
        return new String[0];
    }
}
