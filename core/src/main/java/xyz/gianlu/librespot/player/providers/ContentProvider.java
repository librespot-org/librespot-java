package xyz.gianlu.librespot.player.providers;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.player.remote.Remote3Page;

import java.io.IOException;

/**
 * Provides content for infinite contexts
 *
 * @author Gianlu
 */
public interface ContentProvider {

    @NotNull Remote3Page nextPage() throws IOException, MercuryClient.MercuryException;
}
