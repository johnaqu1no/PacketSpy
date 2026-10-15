package org.ander507.packetspy.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.protocol.Packet;

/**
 * Turns any packet into JSON by reading its fields with reflection. Works the same on every Minecraft
 * version, so there is no per-packet code that can break when Mojang changes a packet.
 * Output is capped (depth, list length, string length, total size) so huge packets such as
 * registries or tags can't flood the web UI. Deep inspection raises the caps so bundles, entity
 * metadata and other nested data show in full, at the cost of much bigger messages.
 */
@Environment(EnvType.CLIENT)
final class PacketSerializer {
    private static final Limits NORMAL = new Limits(3, 32, 2000, 2000);
    private static final Limits DEEP = new Limits(8, 256, 8000, 20000);

    private record Limits(int depth, int items, int string, int nodes) {
    }

    private static final ClassValue<Field[]> FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class && c != Record.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    try {
                        field.setAccessible(true);
                        fields.add(field);
                    } catch (RuntimeException ignored) {
                        // Not accessible (e.g. JDK internals), skip it
                    }
                }
            }
            return fields.toArray(Field[]::new);
        }
    };

    private static final ClassValue<Boolean> HAS_TO_STRING = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("toString").getDeclaringClass() != Object.class;
            } catch (NoSuchMethodException e) {
                return false;
            }
        }
    };

    private final Limits limits;
    private int nodes;

    private PacketSerializer(Limits limits) {
        this.limits = limits;
    }

    static JsonObject serialize(Object packet) {
        return new PacketSerializer(PacketSpyConfig.deepInspection ? DEEP : NORMAL).object(packet, 0);
    }

    private JsonObject object(Object obj, int depth) {
        JsonObject json = new JsonObject();
        for (Field field : FIELDS.get(obj.getClass())) {
            String key = NameTable.remap(field.getName());
            if (json.has(key)) {
                key = key + " (" + NameTable.simpleClassName(field.getDeclaringClass()) + ")";
            }
            try {
                json.add(key, value(field.get(obj), depth + 1));
            } catch (Throwable t) {
                json.addProperty(key, "<error: " + t.getClass().getSimpleName() + ">");
            }
        }
        return json;
    }

    private JsonElement value(Object value, int depth) {
        if (++nodes > limits.nodes()) return new JsonPrimitive("<truncated>");
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String s) return new JsonPrimitive(truncate(s));
        if (value instanceof Integer i && i == -999) return new JsonPrimitive("-999 (Outside Inventory)");
        if (value instanceof Number n) return new JsonPrimitive(n);
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        if (value instanceof Character c) return new JsonPrimitive(c);
        if (value instanceof Enum<?> e) return new JsonPrimitive(e.name());
        if (value instanceof Optional<?> o) return o.isPresent() ? value(o.get(), depth) : JsonNull.INSTANCE;

        Class<?> type = value.getClass();
        // Packets inside a bundle often have a toString() that is only an identity hash, so read their fields
        if (value instanceof Packet<?> && depth < limits.depth()) return object(value, depth);
        if (type.isArray()) return array(value, depth);
        if (value instanceof Map<?, ?> map) return map(map, depth);
        if (value instanceof Collection<?> c) return items(c.iterator(), c.size(), depth);
        if (value instanceof Iterable<?> it && !HAS_TO_STRING.get(type)) return items(it.iterator(), -1, depth);

        if (type.isHidden() || type.isSynthetic()) {
            return new JsonPrimitive("<lambda>"); // e.g. codecs/serializers, nothing useful to show
        }
        boolean opaque = type.isAnonymousClass() || type.getName().startsWith("java.");
        if (depth < limits.depth() && !opaque && (type.isRecord() || !HAS_TO_STRING.get(type))) {
            return object(value, depth);
        }
        return new JsonPrimitive(text(value));
    }

    private JsonElement array(Object array, int depth) {
        int length = Array.getLength(array);
        Class<?> component = array.getClass().getComponentType();
        if (component == byte.class || depth >= limits.depth() || (component.isPrimitive() && length > limits.items())) {
            return new JsonPrimitive(component.getSimpleName() + "[" + length + "]");
        }
        JsonArray json = new JsonArray();
        for (int i = 0; i < Math.min(length, limits.items()); i++) {
            json.add(value(Array.get(array, i), depth + 1));
        }
        if (length > limits.items()) json.add("... (" + (length - limits.items()) + " more)");
        return json;
    }

    private JsonElement items(Iterator<?> it, int size, int depth) {
        if (depth >= limits.depth()) {
            return new JsonPrimitive("<" + (size >= 0 ? size + " items" : "items") + ">");
        }
        JsonArray json = new JsonArray();
        int count = 0;
        while (count < limits.items() && it.hasNext()) {
            json.add(value(it.next(), depth + 1));
            count++;
        }
        if (size > limits.items()) {
            json.add("... (" + (size - limits.items()) + " more)");
        } else if (size < 0 && it.hasNext()) {
            json.add("... (more)");
        }
        return json;
    }

    private JsonElement map(Map<?, ?> map, int depth) {
        if (depth >= limits.depth()) {
            return new JsonPrimitive("<" + map.size() + " entries>");
        }
        JsonObject json = new JsonObject();
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count++ >= limits.items()) {
                json.addProperty("...", (map.size() - limits.items()) + " more");
                break;
            }
            json.add(text(entry.getKey()), value(entry.getValue(), depth + 1));
        }
        return json;
    }

    private String text(Object value) {
        String s;
        try {
            s = String.valueOf(value);
        } catch (Throwable t) {
            return "<error calling toString(): " + t.getClass().getSimpleName() + ">";
        }
        return NameTable.remap(truncate(s));
    }

    private String truncate(String s) {
        return s.length() <= limits.string() ? s : s.substring(0, limits.string()) + "... (" + s.length() + " chars)";
    }
}
