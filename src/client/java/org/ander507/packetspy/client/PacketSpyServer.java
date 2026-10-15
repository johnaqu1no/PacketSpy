package org.ander507.packetspy.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;

/**
 * Streams captured packets to the web UI. Packets are queued by the network thread and turned into
 * JSON here on a separate thread, then sent in batches, so a busy server can't slow the game down.
 */
@Environment(EnvType.CLIENT)
public class PacketSpyServer extends WebSocketServer {
    private static final Gson GSON = new Gson();
    private static final int QUEUE_CAPACITY = 10_000;
    private static final int BATCH_SIZE = 500;

    private final BlockingQueue<CapturedPacket> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicInteger clients = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final Thread sender = new Thread(this::sendLoop, "PacketSpy Sender");
    private final int httpPort;

    record CapturedPacket(String direction, Packet<?> packet, long time) {
    }

    public PacketSpyServer(InetSocketAddress address, int httpPort) {
        super(address);
        this.httpPort = httpPort;
        setReuseAddr(true);
        setDaemon(true);
        sender.setDaemon(true);
    }

    @Override
    public void start() {
        super.start();
        sender.start();
    }

    public boolean hasClients() {
        return clients.get() > 0;
    }

    public void enqueue(CapturedPacket packet) {
        if (!queue.offer(packet)) {
            dropped.incrementAndGet();
        }
    }

    // --- Sending ---

    private void sendLoop() {
        List<CapturedPacket> batch = new ArrayList<>(BATCH_SIZE);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                CapturedPacket first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, BATCH_SIZE - 1);
                }
                long lost = dropped.getAndSet(0);
                if (batch.isEmpty() && lost == 0) {
                    continue;
                }

                JsonArray messages = new JsonArray();
                for (CapturedPacket packet : batch) {
                    JsonObject json = format(packet);
                    if (json != null) {
                        messages.add(json);
                    }
                }
                batch.clear();
                if (lost > 0) {
                    JsonObject notice = new JsonObject();
                    notice.addProperty("type", "dropped");
                    notice.addProperty("count", lost);
                    messages.add(notice);
                }

                if (!messages.isEmpty() && hasClients()) {
                    broadcast(GSON.toJson(messages));
                }
                // Let the next batch build up instead of sending thousands of tiny messages
                Thread.sleep(25);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                batch.clear();
                PacketspyClient.LOGGER.error("PacketSpy failed to send packets", t);
            }
        }
    }

    private static JsonObject format(CapturedPacket captured) {
        Packet<?> packet = captured.packet();
        Class<?> type = packet.getClass();
        String className = NameTable.className(type);
        String name = packetName(packet, className);

        List<String> silenced = PacketSpyConfig.silencedPackets;
        if (silenced.contains(name) || silenced.contains(className) || silenced.contains(NameTable.simpleClassName(type))
                || silenced.contains(type.getName()) || silenced.contains(type.getSimpleName())) {
            return null;
        }

        JsonObject json = new JsonObject();
        json.addProperty("type", "packet");
        json.addProperty("direction", captured.direction());
        json.addProperty("name", name);
        json.addProperty("class", className);
        json.addProperty("time", captured.time());
        try {
            json.add("data", PacketSerializer.serialize(packet));
        } catch (Throwable t) {
            json.addProperty("data", "Error serializing: " + t);
        }
        return json;
    }

    /**
     * Packet.type() got a new obfuscated name in 1.21.4 (method_55846 -> method_65080), so calling it
     * directly would only work on one side of that change. Finding it by its return type works on all.
     */
    private static final ClassValue<Method> TYPE_METHOD = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() == PacketType.class) {
                    return method;
                }
            }
            return null;
        }
    };

    private static String packetName(Packet<?> packet, String className) {
        try {
            // e.g. "minecraft:move_player_pos" - the same id on every version, obfuscated or not
            Method typeMethod = TYPE_METHOD.get(packet.getClass());
            if (typeMethod != null) {
                String id = ((PacketType<?>) typeMethod.invoke(packet)).id().toString();
                return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
            }
        } catch (Throwable ignored) {
            // fall back to the class name below
        }
        return className.substring(Math.max(className.lastIndexOf('.'), className.lastIndexOf('$')) + 1);
    }

    // --- WebSocket events ---

    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft, ClientHandshake request) throws InvalidDataException {
        // Browsers send the page's origin. Without this check any website you visit could connect
        // to ws://localhost and read your packets.
        if (!isAllowedOrigin(request.getFieldValue("Origin"))) {
            throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Origin not allowed");
        }
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
    }

    private boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isEmpty()) {
            return true; // not a browser
        }
        try {
            URI uri = new URI(origin);
            String host = uri.getHost();
            if (!"http".equals(uri.getScheme()) || uri.getPort() != httpPort || host == null) {
                return false;
            }
            if (host.equals("localhost")) {
                return true;
            }
            // Only IP literals, never resolve names (that would allow DNS rebinding)
            String literal = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
            if (!literal.matches("[0-9.]+") && !literal.contains(":")) {
                return false;
            }
            InetAddress address = InetAddress.getByName(literal);
            return address.isLoopbackAddress() || (PacketSpyConfig.allowLanAccess && java.net.NetworkInterface.getByInetAddress(address) != null);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.incrementAndGet();
        PacketspyClient.LOGGER.info("Web UI connected: {}", conn.getRemoteSocketAddress());
        conn.send(configMessage());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        if (clients.decrementAndGet() <= 0) {
            clients.set(0);
            queue.clear();
        }
        PacketspyClient.LOGGER.info("Web UI disconnected: {}", conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String action = json.has("action") ? json.get("action").getAsString() : "";
            boolean changed = false;
            switch (action) {
                case "toggleMovement" -> {
                    if (json.has("value")) {
                        PacketSpyConfig.logPlayerMovementPackets = json.get("value").getAsBoolean();
                        changed = true;
                    }
                }
                case "toggleDeepInspection" -> {
                    if (json.has("value")) {
                        PacketSpyConfig.deepInspection = json.get("value").getAsBoolean();
                        changed = true;
                    }
                }
                case "silencePacket" -> {
                    if (json.has("packetName")) {
                        String packetName = json.get("packetName").getAsString().trim();
                        if (!packetName.isEmpty() && !PacketSpyConfig.silencedPackets.contains(packetName)) {
                            PacketSpyConfig.silencedPackets.add(packetName);
                            changed = true;
                        }
                    }
                }
                case "unsilencePacket" -> {
                    if (json.has("packetName")) {
                        changed = PacketSpyConfig.silencedPackets.remove(json.get("packetName").getAsString());
                    }
                }
                default -> PacketspyClient.LOGGER.warn("Unknown message from web UI: {}", message);
            }
            if (changed) {
                PacketSpyConfig.save();
                broadcast(configMessage());
            }
        } catch (Exception e) {
            PacketspyClient.LOGGER.error("Failed to process message: " + message, e);
        }
    }

    private static String configMessage() {
        JsonObject state = new JsonObject();
        state.addProperty("type", "config");
        state.addProperty("logPlayerMovementPackets", PacketSpyConfig.logPlayerMovementPackets);
        state.addProperty("deepInspection", PacketSpyConfig.deepInspection);
        state.add("silencedPackets", GSON.toJsonTree(PacketSpyConfig.silencedPackets));
        return GSON.toJson(state);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn == null) {
            PacketspyClient.LOGGER.error("PacketSpy WebSocket server failed on port {} (is another Minecraft running?)", getPort(), ex);
        } else {
            PacketspyClient.LOGGER.error("WebSocket error", ex);
        }
    }

    @Override
    public void onStart() {
        PacketspyClient.LOGGER.info("WebSocket server started on port: {}", getPort());
    }
}
