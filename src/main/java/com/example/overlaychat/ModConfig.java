package com.example.overlaychat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class ModConfig {
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("overlaychat.json").toFile();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ConfigData data = new ConfigData();

    public static class ConfigData {
        public boolean enabled = true;
    }

    public static synchronized void load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
                if (loaded != null) data = loaded;
            } catch (IOException e) {
                e.printStackTrace();
                data = new ConfigData();
            }
        } else {
            data = new ConfigData();
            save();
        }
    }

    public static synchronized void save() {
        try {
            File parent = CONFIG_FILE.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean isEnabled() { return data.enabled; }

    public static synchronized void setEnabled(boolean enabled) { data.enabled = enabled; save(); }

    public static synchronized void toggle() { data.enabled = !data.enabled; save(); }
}
