package xyz.gianlu.librespot.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.log4j.Logger;
import xyz.gianlu.librespot.api.server.ApiServer;
import xyz.gianlu.librespot.common.config.Configuration;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;

/**
 * @author Gianlu
 */
public class Main {

    private static final Logger LOGGER = Logger.getLogger(xyz.gianlu.librespot.Main.class);

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, MercuryClient.MercuryException {
        Session session = new Session.Builder(getConfig()).create();

        ApiServer server = new ApiServer(24879);
        server.registerHandler(new PlayerHandler(session));
        server.registerHandler(new MetadataHandler(session));
        server.registerHandler(new MercuryHandler(session));
    }
    private static Configuration getConfig() throws IOException {
        File configFile = Optional.ofNullable(System.getProperty("config.file")).map(File::new).orElseGet(() -> {
            LOGGER.info("No external application.yml file found. Please check if env property 'config.file' is set");
            return new File(ClassLoader.getSystemResource("application.yml").getFile());
        });
        return new ObjectMapper(new YAMLFactory()).readValue(configFile, Configuration.class);
    }
}
