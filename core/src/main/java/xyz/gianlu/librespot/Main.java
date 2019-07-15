package xyz.gianlu.librespot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.log4j.Logger;
import xyz.gianlu.librespot.common.config.Configuration;
import xyz.gianlu.librespot.common.enums.Strategy;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.ZeroconfServer;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;

/**
 * @author Gianlu
 */
public class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class);

    public static void main(String[] args) throws IOException, GeneralSecurityException, Session.SpotifyAuthenticationException, MercuryClient.MercuryException {
        Configuration conf = getConfig();
        if (conf.getAuth().getStrategy() == Strategy.ZEROCONF) {
            new ZeroconfServer(conf);
        } else {
            new Session.Builder(conf).create();
        }
    }

    private static Configuration getConfig() throws IOException {
        File configFile = Optional.ofNullable(System.getProperty("config.file")).map(File::new).orElseGet(() -> {
            LOGGER.info("No external application.yml file found. Please check if env property 'config.file' is set");
            return new File(ClassLoader.getSystemResource("application.yml").getFile());
        });

        return new ObjectMapper(new YAMLFactory()).readValue(configFile, Configuration.class);
    }
}
