package com.crystalgui.graph.port;

import com.crystalgui.core.async.Contributions;
import com.crystalgui.core.async.Owner;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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

    private static final Contributions<PortType> TYPES =
            Contributions.of("port-types", PortType::id, Contributions.Conflict.REJECT);
    private static final Contributions.Index<Map<String, PortType>> BY_ID = TYPES.index(entries -> {
        Map<String, PortType> byId = new LinkedHashMap<>();
        for (Contributions.Entry<PortType> entry : entries) byId.put(entry.item().id(), entry.item());
        return byId;
    });

    private PortTypeRegistry() {
    }

    /**
     * Registers {@code type} under its own id.
     *
     * <p>Throws on a duplicate id: a silent overwrite hides two consumers fighting over one id far more often than
     * it is a deliberate re-registration. Re-registering the <em>identical</em> type is a no-op rather than a throw,
     * so a host that registers from more than one entry point is not punished for it.</p>
     */
    public static void register(PortType type) {
        TYPES.add(Owner.PROCESS, type);
    }

    @Nullable
    public static PortType get(String id) {
        return BY_ID.get().get(id);
    }

    /** As {@link #get}, but throws with the registered set listed — the shape of error a decode wants. */
    public static PortType require(String id) {
        Map<String, PortType> types = BY_ID.get();
        PortType type = types.get(id);
        if (type == null) {
            throw new IllegalArgumentException("Unknown port type: " + id + " (registered: " + types.keySet() + ")");
        }
        return type;
    }

    public static Set<String> ids() {
        return Set.copyOf(BY_ID.get().keySet());
    }

    /** Drops every registration. For tests, which must not inherit each other's types. */
    public static void clear() {
        TYPES.clearForTesting();
    }
}
