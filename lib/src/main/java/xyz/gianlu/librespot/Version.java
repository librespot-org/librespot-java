/*
 * Copyright 2021 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
