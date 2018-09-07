package org.librespot.spotify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonWriter;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Base64;


/**
 * @author Gianlu
 */
public class Configuration {
    private static final Logger LOGGER = Logger.getLogger(Configuration.class);
    private static Configuration instance;
    private final File file;
    private JsonObject obj;

    private Configuration(@NotNull File file) throws IOException {
        this.file = file;

        if (!file.exists()) {
            if (file.createNewFile()) {
                obj = new JsonObject();
                save();
            } else {
                throw new IOException("Cannot create configuration file!");
            }
        } else {
            obj = new JsonParser().parse(new FileReader(file)).getAsJsonObject();
        }
    }

    @NotNull
    public static Configuration get() {
        if (instance == null) throw new IllegalStateException("Configuration not initialized!");
        return instance;
    }

    public static void init() throws IOException {
        instance = new Configuration(new File("conf.json"));
    }

    private void save() throws IOException {
        try (JsonWriter writer = new JsonWriter(new FileWriter(file))) {
            writer.setLenient(true);
            Streams.write(obj, writer);
        }
    }

    private void safeSave() {
        try {
            save();
        } catch (IOException ex) {
            LOGGER.fatal("Failed saving configuration!", ex);
        }
    }

    public void saveLogin(String username, String password) {
        String base64 = Base64.getEncoder().encodeToString(String.format("%s:%s", username, password).getBytes());
        obj.addProperty("login", base64);
        safeSave();
    }

    @Nullable
    public Login getSavedLogin() {
        JsonElement base64 = obj.get("login");
        if (base64 != null) return new Login(base64.getAsString());
        else return null;
    }

    public static class Login {
        public final String username;
        public final String password;

        private Login(String base64) {
            String decoded = new String(Base64.getDecoder().decode(base64));
            int colon = decoded.indexOf(':');
            if (colon == -1) throw new IllegalArgumentException("Corrupted login data: " + base64);
            username = decoded.substring(0, colon);
            password = decoded.substring(colon + 1);
        }
    }
}
