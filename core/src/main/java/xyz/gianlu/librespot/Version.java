package xyz.gianlu.librespot;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public class Version {
    private static final String VERSION;

    static {
        Package pkg = Package.getPackage("xyz.gianlu.librespot");
        String version = pkg.getImplementationVersion();
        if (version == null) version = pkg.getSpecificationVersion();
        if (version != null) VERSION = version;
        else VERSION = "?.?.?";
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
