package xyz.gianlu.librespot;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Keyexchange;

/**
 * @author Gianlu
 */
public class Version {
    public static final int SPOTIFY_CLIENT_VERSION = 111400475;
    private static final String VERSION;
    private static final String OS = System.getProperty("os.name").toLowerCase();

    static {
        Package pkg = Package.getPackage("xyz.gianlu.librespot");
        String version = pkg.getImplementationVersion();
        if (version == null) version = pkg.getSpecificationVersion();
        if (version != null) VERSION = version;
        else VERSION = "?.?.?";
    }

    @NotNull
    public static Keyexchange.Platform platform() {
        if (OS.contains("win"))
            return Keyexchange.Platform.PLATFORM_WIN32_X86;
        else if (OS.contains("mac"))
            return Keyexchange.Platform.PLATFORM_OSX_X86;
        else
            return Keyexchange.Platform.PLATFORM_LINUX_X86;
    }

    @NotNull
    public static String versionString() {
        return "librespot-java " + VERSION;
    }

    @NotNull
    public static String systemInfoString() {
        return versionString() + "; Java " + System.getProperty("java.version") + "; " + System.getProperty("os.name");
    }
}
