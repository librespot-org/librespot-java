package xyz.gianlu.librespot;

import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.cache.CacheManager;
import xyz.gianlu.librespot.core.AuthConfiguration;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.ZeroconfServer;
import xyz.gianlu.librespot.player.Player;

/**
 * @author Gianlu
 */
public abstract class AbsConfiguration implements Player.Configuration, CacheManager.Configuration, AuthConfiguration, ZeroconfServer.Configuration {

    @Nullable
    public abstract String deviceName();

    @Nullable
    public abstract Session.DeviceType deviceType();
}
