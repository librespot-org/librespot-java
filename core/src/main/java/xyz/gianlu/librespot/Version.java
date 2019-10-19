package xyz.gianlu.librespot;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Keyexchange;
import xyz.gianlu.librespot.common.proto.Keyexchange.BuildInfo;

/**
 * @author Gianlu
 */
public class Version {
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
    public static String versionNumber() {
        return VERSION;
    }

    @NotNull
    public static String versionString() {
        return "librespot-java " + VERSION;
    }

    @NotNull
    public static String systemInfoString() {
        return versionString() + "; Java " + System.getProperty("java.version") + "; " + System.getProperty("os.name");
    }

    /**
     * @return A {@link BuildInfo} object identifying a standard client.
     */
    @NotNull
    public static BuildInfo standardBuildInfo() {
        return BuildInfo.newBuilder()
                .setProduct(Keyexchange.Product.PRODUCT_LIBSPOTIFY)
                .setPlatform(Version.platform())
                .setVersion(111700543)
                .build();
    }

    /**
     * @return A {@link BuildInfo} object identifying a mobile client.
     * See https://github.com/librespot-org/librespot-java/issues/140
     */
    @NotNull
    public static BuildInfo mobileBuildInfo() {
        return BuildInfo.newBuilder()
                .setProduct(Keyexchange.Product.PRODUCT_MOBILE)
                .setPlatform(Keyexchange.Platform.PLATFORM_ANDROID_ARM)
                .setVersion(852700957)
                .build();
    }
}
