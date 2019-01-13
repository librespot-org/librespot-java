package xyz.gianlu.librespot;

import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.core.AuthConfiguration;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.ZeroconfAuthenticator;
import xyz.gianlu.librespot.player.CacheManager;
import xyz.gianlu.librespot.player.Player;

/**
 * @author Gianlu
 */
public abstract class AbsConfiguration implements Player.Configuration, CacheManager.CacheConfiguration, AuthConfiguration, ZeroconfAuthenticator.Configuration {

    @Nullable
    public abstract String deviceName();

    @Nullable
    public abstract Session.DeviceType deviceType();
}
