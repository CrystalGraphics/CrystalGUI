package com.crystalgui.ui.elements.config;

import com.crystalgui.ui.elements.config.control.*;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Descriptor to control — <b>the one extension point of the kit.</b>
 *
 * <p>A consumer that wants a colour wheel where the default gives a text box registers a factory for
 * {@link ConfigDescriptor.Kind#COLOR} and every panel in the process changes at once. That is the same
 * shape {@code NodeFieldWidgets} already has, and it is deliberately <em>one</em> registry rather than
 * one per host: two registries is how the inspector and the node graph drift into showing the same
 * value two different ways.</p>
 *
 * <h3>A missing kind returns null rather than substituting</h3>
 * <p>A kind with no control is a gap someone has to fill, and a text box standing in for a gradient
 * would hide it behind something that looks deliberate. The caller decides what to show instead — and
 * {@code ConfigKitTest} enumerates the kinds, so the gap is a named test failure long before it is a
 * blank row.</p>
 */
public final class ConfigControls {

    @FunctionalInterface
    public interface Factory {
        /** @param value the current value, already resolved against the descriptor's default */
        ConfigControl create(ConfigDescriptor descriptor, @Nullable Object value);
    }

    private static final Map<ConfigDescriptor.Kind, Factory> FACTORIES =
            new EnumMap<>(ConfigDescriptor.Kind.class);

    static {
        FACTORIES.put(ConfigDescriptor.Kind.TEXT, (d, v) -> new TextControl(d, v == null ? "" : String.valueOf(v)));
        FACTORIES.put(ConfigDescriptor.Kind.BOOLEAN, (d, v) -> new BooleanControl(d, Boolean.TRUE.equals(v)));
        FACTORIES.put(ConfigDescriptor.Kind.SELECT, (d, v) -> new SelectControl(d, v == null ? null : String.valueOf(v)));
        FACTORIES.put(ConfigDescriptor.Kind.VECTOR, (d, v) -> new VectorControl(d, v instanceof double[] a ? a : new double[d.arity()]));
        // NUMBER is the one kind whose control depends on its METADATA rather than only its kind: a
        // range makes it a slider. Deciding that here rather than inside a control is what keeps
        // SliderControl able to assume it has a range, and NumberControl able to assume it has none.
        FACTORIES.put(ConfigDescriptor.Kind.NUMBER, (d, v) -> {
            double value = v instanceof Number n ? n.doubleValue() : 0d;
            return d.range() == null ? new NumberControl(d, value) : new SliderControl(d, value);
        });
        FACTORIES.put(ConfigDescriptor.Kind.ARRAY, (d, v) -> {
            List<Object> list = v instanceof List<?> l ? new ArrayList<>((List<Object>) l) : null;
            return new ArrayControl(d, list);
        });

        FACTORIES.put(ConfigDescriptor.Kind.HEADER, (d, v) -> new HeaderControl(d));
        FACTORIES.put(ConfigDescriptor.Kind.INFO,
                (d, v) -> new InfoControl(d, v == null ? "" : String.valueOf(v)));
        FACTORIES.put(ConfigDescriptor.Kind.COLOR, (d, v) -> new ColorControl(d, v instanceof Integer i ? i : null));
        FACTORIES.put(ConfigDescriptor.Kind.MATRIX, (d, v) -> new MatrixControl(d, v instanceof double[] a ? a : null));
        FACTORIES.put(ConfigDescriptor.Kind.ASSET, (d, v) -> new AssetControl(d, v == null ? null : String.valueOf(v)));
        FACTORIES.put(ConfigDescriptor.Kind.MASK, (d, v) -> {
            Set<String> set = v instanceof Set<?> s ? new LinkedHashSet<>((Set<String>) s) : null;
            return new MaskControl(d, set);
        });
    }

    private ConfigControls() {
    }

    /** Replaces the control used for one kind, everywhere. */
    public static void register(ConfigDescriptor.Kind kind, Factory factory) {
        FACTORIES.put(kind, factory);
    }

    /** The control for a descriptor, or null when its kind has none registered yet. */
    @Nullable
    public static ConfigControl create(ConfigDescriptor descriptor, @Nullable Object value) {
        Factory factory = FACTORIES.get(descriptor.kind());
        return factory == null ? null : factory.create(descriptor, value);
    }

    /** True when {@code kind} can be built — what a caller checks before offering it. */
    public static boolean isRegistered(ConfigDescriptor.Kind kind) {
        return FACTORIES.containsKey(kind);
    }
}
