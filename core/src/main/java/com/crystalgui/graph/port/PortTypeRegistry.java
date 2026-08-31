package com.crystalgui.graph.port;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * id → {@link PortType}, so a graph loaded from disk or off the wire can resolve the types it names.
 *
 * <p>Registration is explicit and manual, matching every other registry here ({@code ElementRegistry},
 * {@code CgUiSpriteRegistry}, {@code CgMaterialRegistry}). {@code ElementRegistry}'s own javadoc
 * records why: a registry populated as a class-loading side effect decodes differently depending on
 * which classes a given JVM happened to touch, which is harmless locally and silently wrong for
 * anything serialized.</p>
 *
 * <p>Unlike {@code ElementRegistry} there is no {@code bootstrapBuiltins()}, because there are no
 * builtins — CrystalGUI has no opinion about what a port carries. An empty registry is the correct
 * state for a host that has not registered any types yet, and {@link #require} says so by name rather
 * than returning null into someone's decode loop.</p>
 */
public final class PortTypeRegistry {

    private static final Map<String, PortType> TYPES = new ConcurrentHashMap<>();

    private PortTypeRegistry() {
    }

    /**
     * Registers {@code type} under its own id.
     *
     * <p>Throws on a duplicate id, matching {@code ElementRegistry.register} and {@code CgMeshRegistry}
     * — a silent overwrite hides two consumers fighting over one id far more often than it is a
     * deliberate re-registration. Re-registering the <em>identical</em> type is a no-op rather than a
     * throw, so a host that registers from more than one entry point is not punished for it.</p>
     */
    public static void register(PortType type) {
        PortType previous = TYPES.putIfAbsent(type.id(), type);
        if (previous != null && !previous.equals(type)) {
            throw new IllegalArgumentException("Port type id already registered: " + type.id()
                    + " (as " + previous + ")");
        }
    }

    @Nullable
    public static PortType get(String id) {
        return TYPES.get(id);
    }

    /** As {@link #get}, but throws with the registered set listed — the shape of error a decode wants. */
    public static PortType require(String id) {
        PortType type = TYPES.get(id);
        if (type == null) {
            throw new IllegalArgumentException("Unknown port type: " + id + " (registered: " + TYPES.keySet() + ")");
        }
        return type;
    }

    public static Set<String> ids() {
        return Set.copyOf(TYPES.keySet());
    }

    /** Drops every registration. For tests, which must not inherit each other's types. */
    public static void clear() {
        TYPES.clear();
    }
}
