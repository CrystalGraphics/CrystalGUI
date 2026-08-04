package com.crystalgui.core.settings;

import lombok.Getter;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * One declared, named value — what a preferences panel renders, a codec stores and a consumer reads.
 *
 * <p>Ported from VS Code's {@code IConfigurationPropertySchema}
 * ({@code src/vs/platform/configuration/common/configurationRegistry.ts}), MIT.</p>
 *
 * <h3>A declaration, exactly like a {@code Command}</h3>
 * <p>A consumer names a stable {@code String} id and gets a value; the <em>declaration</em> says what
 * that id means, what type it is, what it defaults to and what it is called. That indirection is what
 * makes settings <b>data</b>: a preferences file can be parsed, shipped as a preset and edited by a user
 * without any of it touching Java — the identical argument {@code Command} makes for key bindings, and
 * for the identical reason.</p>
 *
 * <p>It is also what lets a settings panel be <b>generated</b> rather than hand-written. You cannot
 * enumerate what does not exist as data, which is the specific reason IntelliJ's
 * {@code PersistentStateComponent} was read and declined: it serialises a state class per component, so
 * there is nothing to walk.</p>
 *
 * <h3>Stored as text, typed at the edges</h3>
 * <p>{@link #parse} and {@link #format} are the only two places a value crosses between {@code String}
 * and {@code T}. Everything in between — every layer, every codec, every content hash — sees text, which
 * is the same trade {@code NodeData.properties()} and {@code StyleValue} both make and for the same
 * reason: it keeps the storage layer from needing to know any value types.</p>
 *
 * <p>A {@link #parse} that throws yields the default rather than propagating, so one malformed line in a
 * user's preferences file cannot take out every setting around it. Same call the stylesheet parser makes
 * for a malformed declaration.</p>
 *
 * @param <T> the value type
 */
public final class Setting<T> {

    /**
     * Dotted and stable — {@code "editor.fontSize"}, {@code "shader.queue"}.
     *
     * <p>This is what a preferences file, a preset and a user's override all name, so renaming one
     * silently orphans every stored value that used it. Same contract a {@code Command} id has.</p>
     */
    @Getter private final String id;

    @Getter private final String label;

    @Getter @Nullable private final String description;

    @Getter private final T defaultValue;

    private final Function<String, T> parse;
    private final Function<T, String> format;

    /** For an enumerated setting: the permitted values, in menu order. Empty otherwise. */
    @Getter private final List<String> options;

    private final Set<SettingsLayer> writableLayers;

    private Setting(String id, String label, @Nullable String description, T defaultValue,
                    Function<String, T> parse, Function<T, String> format,
                    List<String> options, Set<SettingsLayer> writableLayers) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("A setting needs an id");
        this.id = id;
        this.label = label == null || label.isEmpty() ? id : label;
        this.description = description;
        this.defaultValue = defaultValue;
        this.parse = parse;
        this.format = format;
        this.options = List.copyOf(options);
        this.writableLayers = Collections.unmodifiableSet(EnumSet.copyOf(writableLayers));
    }

    // ── Declaring ───────────────────────────────────────────────────────────

    /** Everything but {@link SettingsLayer#DEFAULT}, which is the declaration itself and not storage. */
    private static Set<SettingsLayer> everyWritableLayer() {
        EnumSet<SettingsLayer> writable = EnumSet.allOf(SettingsLayer.class);
        writable.remove(SettingsLayer.DEFAULT);
        return writable;
    }

    public static Setting<String> string(String id, String label, String defaultValue) {
        return new Setting<>(id, label, null, defaultValue, s -> s, s -> s,
                List.of(), everyWritableLayer());
    }

    public static Setting<Boolean> bool(String id, String label, boolean defaultValue) {
        return new Setting<>(id, label, null, defaultValue,
                Boolean::parseBoolean, String::valueOf, List.of(), everyWritableLayer());
    }

    public static Setting<Integer> integer(String id, String label, int defaultValue) {
        return new Setting<>(id, label, null, defaultValue,
                Integer::parseInt, String::valueOf, List.of(), everyWritableLayer());
    }

    public static Setting<Double> number(String id, String label, double defaultValue) {
        return new Setting<>(id, label, null, defaultValue,
                Double::parseDouble, String::valueOf, List.of(), everyWritableLayer());
    }

    /**
     * One of {@code options}. The default must be one of them.
     *
     * <p>Kept as strings rather than a Java enum because the whole point is that the option list can come
     * from somewhere other than code — a registry of vertex formats, a set of installed themes. An
     * enumerated setting whose options are a compile-time enum cannot express either.</p>
     */
    public static Setting<String> select(String id, String label, List<String> options, String defaultValue) {
        if (options == null || options.isEmpty()) {
            // A dropdown with nothing in it is a control that silently does nothing — worse than a
            // declaration that fails loudly here. Same call NodeField makes.
            throw new IllegalArgumentException("Setting '" + id + "' declares no options");
        }
        String fallback = options.contains(defaultValue) ? defaultValue : options.get(0);
        return new Setting<>(id, label, null, fallback,
                s -> options.contains(s) ? s : fallback, s -> s, options, everyWritableLayer());
    }

    public Setting<T> description(String value) {
        return new Setting<>(id, label, value, defaultValue, parse, format, options, writableLayers);
    }

    /**
     * Restricts which layers may hold this setting — VS Code's {@code ConfigurationScope}.
     *
     * <p>The case it exists for: a setting that is meaningless outside a document (a shader's render
     * queue) must not be settable as a global preference, or a user sets it once and every graph they
     * open silently inherits it.</p>
     */
    public Setting<T> writableAt(SettingsLayer... layers) {
        EnumSet<SettingsLayer> permitted = EnumSet.noneOf(SettingsLayer.class);
        permitted.addAll(List.of(layers));
        // DEFAULT is the declaration, never storage — accepting it here would let a caller believe a
        // write to it is possible, and Settings would then silently refuse every one.
        permitted.remove(SettingsLayer.DEFAULT);
        if (permitted.isEmpty()) {
            throw new IllegalArgumentException("Setting '" + id + "' would be writable nowhere, so it"
                    + " could only ever report its default. Name at least one layer other than DEFAULT.");
        }
        return new Setting<>(id, label, description, defaultValue, parse, format, options, permitted);
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    public boolean isWritableAt(SettingsLayer layer) {
        return writableLayers.contains(layer);
    }

    public Set<SettingsLayer> writableLayers() {
        return writableLayers;
    }

    /** Whether this setting offers a fixed set of choices. */
    public boolean isEnumerated() {
        return !options.isEmpty();
    }

    /**
     * {@code raw} as a {@code T}, or {@link #defaultValue} when it is null or unparseable.
     *
     * <p>Degrading rather than throwing is deliberate — see the class note.</p>
     */
    public T read(@Nullable String raw) {
        if (raw == null) return defaultValue;
        try {
            T parsed = parse.apply(raw);
            return parsed == null ? defaultValue : parsed;
        } catch (RuntimeException malformed) {
            return defaultValue;
        }
    }

    /** {@code value} as text, for storage. */
    public String write(@Nullable T value) {
        return value == null ? format.apply(defaultValue) : format.apply(value);
    }

    @Override
    public String toString() {
        return "Setting[" + id + "]";
    }
}
