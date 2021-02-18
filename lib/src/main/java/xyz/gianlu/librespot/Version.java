package xyz.gianlu.librespot;

import org.jetbrains.annotations.NotNull;

import static com.spotify.Keyexchange.*;

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
    public static Platform platform() {
        if (OS.contains("win"))
            return Platform.PLATFORM_WIN32_X86;
        else if (OS.contains("mac"))
            return Platform.PLATFORM_OSX_X86;
        else
            return Platform.PLATFORM_LINUX_X86;
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
                .setProduct(Product.PRODUCT_CLIENT)
                .addProductFlags(ProductFlags.PRODUCT_FLAG_NONE)
                .setPlatform(Version.platform())
                .setVersion(115100382)
                .build();
    }
}
