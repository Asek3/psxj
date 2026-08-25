package nanolive.psxj.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import nanolive.psxj.util.Log;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigManager {

    private final Path configRoot;
    private final Path configFile;
    private final Gson gson;

    public ConfigManager(Path configRoot) {
        this.configRoot = configRoot;
        this.configFile = configRoot.resolve("config.json");
        JsonSerializer<Path> pathSerializer = (src, typeOfSrc, context) -> context.serialize(src.toString());
        JsonDeserializer<Path> pathDeserializer = (json, typeOfT, context) -> Path.of(json.getAsString());
        this.gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .registerTypeHierarchyAdapter(Path.class, pathSerializer)
            .registerTypeHierarchyAdapter(Path.class, pathDeserializer)
            .create();
    }

    public AppConfig load() {
        if (!Files.exists(configFile)) {
            var defaults = AppConfig.defaults();
            save(defaults);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(configFile)) {
            var loaded = gson.fromJson(reader, AppConfig.class);
            var config = loaded == null ? AppConfig.defaults() : loaded;
            config.normalize();
            return config;
        } catch (IOException ex) {
            Log.error("Failed to load config", ex);
            return AppConfig.defaults();
        }
    }

    public void save(AppConfig config) {
        try {
            Files.createDirectories(configRoot);
            config.normalize();
            try (Writer writer = Files.newBufferedWriter(configFile)) {
                gson.toJson(config, writer);
            }
        } catch (IOException ex) {
            Log.error("Failed to save config", ex);
        }
    }

    public Path configFile() {
        return configFile;
    }
}
