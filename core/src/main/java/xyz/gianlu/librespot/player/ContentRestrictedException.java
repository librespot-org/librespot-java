package xyz.gianlu.librespot.player;

import com.spotify.metadata.Metadata;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @author Gianlu
 */
public class ContentRestrictedException extends Exception {

    public static void checkRestrictions(@NotNull String country, @NotNull List<Metadata.Restriction> restrictions) throws ContentRestrictedException {
        for (Metadata.Restriction restriction : restrictions)
            if (isRestricted(country, restriction))
                throw new ContentRestrictedException();
    }

    private static boolean isInList(@NotNull String list, @NotNull String match) {
        for (int i = 0; i < list.length(); i += 2)
            if (list.substring(i, i + 2).equals(match))
                return true;

        return false;
    }

    private static boolean isRestricted(@NotNull String countryCode, @NotNull Metadata.Restriction restriction) {
        if (restriction.hasCountriesAllowed()) {
            String allowed = restriction.getCountriesAllowed();
            if (allowed.isEmpty()) return true;

            if (!isInList(restriction.getCountriesForbidden(), countryCode))
                return true;
        }

        if (restriction.hasCountriesForbidden())
            return isInList(restriction.getCountriesForbidden(), countryCode);

        return false;
    }
}
