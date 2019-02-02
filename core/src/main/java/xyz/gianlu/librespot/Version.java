package xyz.gianlu.librespot;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public class Version {
    public static final String VERSION = "0.2.0";

    @NotNull
    public static String versionString() {
        return "librespot-java " + VERSION;
    }

    @NotNull
    public static String systemInfoString() {
        return versionString() + "; Java " + System.getProperty("java.version") + "; " + System.getProperty("os.name");
    }
}
