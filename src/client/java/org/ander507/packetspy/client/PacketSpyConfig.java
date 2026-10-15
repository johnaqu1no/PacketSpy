package org.ander507.packetspy.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

@Environment(EnvType.CLIENT)
public class PacketSpyConfig {
    private static final Gson GSON = (new GsonBuilder()).setPrettyPrinting().create();
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("packetspy.json");

    // Read on the network thread, changed from the WebSocket thread
    public static final List<String> silencedPackets = new CopyOnWriteArrayList<>();
    public static volatile boolean logPlayerMovementPackets = true;
    /** Serialize nested data (bundles, entity metadata) in full. Off by default because it makes busy servers send far more data. */
    public static volatile boolean deepInspection = false;
    public static int httpServerPort = 8888;
    public static int webSocketPort = 8887;
    /** When false the web UI is only reachable from this PC. Set to true to open it from a phone on the same network. */
    public static boolean allowLanAccess = false;

    public static void load() {
        if (Files.exists(CONFIG_FILE)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_FILE, StandardCharsets.UTF_8)) {
                ConfigData data = GSON.fromJson(reader, ConfigData.class);
                if (data != null) {
                    if (data.silencedPackets != null) {
                        silencedPackets.clear();
                        silencedPackets.addAll(data.silencedPackets);
                    }
                    logPlayerMovementPackets = data.logPlayerMovementPackets;
                    deepInspection = data.deepInspection;
                    httpServerPort = validPort(data.httpServerPort, 8888);
                    webSocketPort = validPort(data.webSocketPort, 8887);
                    allowLanAccess = data.allowLanAccess;
                }
            } catch (Exception e) {
                // Broken JSON must not crash the game; keep the file so the user can fix it
                PacketspyClient.LOGGER.error("Failed to load config/packetspy.json, using defaults", e);
                return;
            }
        }
        // Creates the file on first start and adds options that are new in this version
        save();
    }

    public static synchronized void save() {
        try (Writer writer = Files.newBufferedWriter(CONFIG_FILE, StandardCharsets.UTF_8)) {
            ConfigData data = new ConfigData();
            data.silencedPackets = new ArrayList<>(silencedPackets);
            data.logPlayerMovementPackets = logPlayerMovementPackets;
            data.deepInspection = deepInspection;
            data.httpServerPort = httpServerPort;
            data.webSocketPort = webSocketPort;
            data.allowLanAccess = allowLanAccess;
            GSON.toJson(data, writer);
        } catch (IOException e) {
            PacketspyClient.LOGGER.error("Failed to save config", e);
        }
    }

    private static int validPort(int port, int fallback) {
        return port > 0 && port <= 65535 ? port : fallback;
    }

    private static class ConfigData {
        List<String> silencedPackets = new ArrayList<>();
        boolean logPlayerMovementPackets = true;
        boolean deepInspection = false;
        int httpServerPort = 8888;
        int webSocketPort = 8887;
        boolean allowLanAccess = false;
    }
}
