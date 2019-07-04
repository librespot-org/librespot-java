package xyz.gianlu.librespot.player.providers;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;

import static spotify.player.proto.ContextPageOuterClass.ContextPage;

/**
 * Provides content for infinite contexts
 *
 * @author Gianlu
 */
public interface ContentProvider {

    @NotNull ContextPage nextPage() throws IOException, MercuryClient.MercuryException;
}
