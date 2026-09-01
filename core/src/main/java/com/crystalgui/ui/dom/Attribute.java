package com.crystalgui.ui.dom;

import com.crystalgui.ui.input.FocusPolicy;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * A typed attribute key — {@code Attribute.ENABLED}, {@code Attribute.INERT} — with the value a node
 * holds when nothing has set it.
 *
 * <p>The old node carried each of these as a field with a setter, and the audit's census (§1) lists
 * what that grew into: focus policy, hit-test, inert, popover invoker, keymap, settings, scroll
 * exemption, user-sized axes, resize mode. A typed key is one map, one {@code set}, one observer
 * signal, and one place for a lookup through the tree the way {@code DataContext} already walks —
 * which is what retires the keymap and settings fields (plan_m5.md D5.4).</p>
 *
 * <p>Every key registers itself by name so the codec can carry a value it has never seen the key of:
 * {@link #named(String)} finds it and {@link #parse(String)} reads it back by type. A value that is not
 * one of the four carried types (boolean, integer, float, string) or an enum stays local to the
 * process, which is the right default for a key nobody has thought about the wire for.</p>
 *
 * @param <T> the value type
 */
public final class Attribute<T> {

    private static final Map<String, Attribute<?>> BY_NAME = new ConcurrentHashMap<>();

    /** Whether the node responds to input at all; {@code :disabled} when false. */
    public static final Attribute<Boolean> ENABLED = of("enabled", Boolean.class, true);
    /** The HTML {@code inert} attribute: the subtree keeps its box and stops being interactive. */
    public static final Attribute<Boolean> INERT = of("inert", Boolean.class, false);
    /** Whether hit-testing may land on this subtree; {@code pointer-events: none} when false. */
    public static final Attribute<Boolean> HIT_TEST = of("hit-test", Boolean.class, true);
    /** The name of the slot a light child asks to be placed in; empty for the default slot. */
    /**
     * A focus navigation scope: a dialog, a window frame, a pane. Tab is trapped inside whichever
     * one a modal blocks, and "is focus already in here" is asked of one.
     */
    public static final Attribute<Boolean> FOCUS_SCOPE = of("focus-scope", Boolean.class, false);

    /** Whether and how this node takes focus. Four values, and two of them look alike. */
    public static final Attribute<FocusPolicy> FOCUS_POLICY = of("focus-policy", FocusPolicy.class, FocusPolicy.NONE);

    public static final Attribute<String> SLOT = of("slot", String.class, "");
    /** The {@code ::part()} name a node inside a shadow tree is exposed under; empty for none. */
    public static final Attribute<String> PART = of("part", String.class, "");

    /**
     * Not on screen and taking no space — HTML's own {@code hidden}, with {@code [hidden] &#123;
     * display: none &#125;} in the user-agent sheet doing the work.
     *
     * <p>This replaces the old engine's {@code setDisplayed}, which wrote {@code display} at
     * {@code IMPORTANT} origin from 74 call sites — the single largest family of engine writes into
     * the cascade, and a family the boundary scan now forbids outright. An attribute says the same
     * thing without outranking anything: a sheet can still restyle a hidden node, and a theme that
     * wanted {@code visibility} or a collapse animation instead can say so, which an
     * {@code !important} display could not be argued with.</p>
     *
     * <p>Deliberately NOT the same question as {@link com.crystalgui.ui.service.Lifecycle#freeze}: a
     * hidden node still matches selectors, still runs its hooks and still holds a box's worth of
     * state, it merely lays out to nothing. Freezing is what stops a subtree working.</p>
     */
    public static final Attribute<Boolean> HIDDEN = of("hidden", Boolean.class, false);

    /**
     * This box does not move with what it is hosted in — a scrollbar, a gutter, a find bar.
     *
     * <p>The old engine's {@code setScrollExempt}, and the one 5.4 gap the census found: without it a
     * scroller's own bars scroll away with the content they are for. Read by {@code BoxTree}'s
     * composition, which is the only place a host's scroll offset is applied.</p>
     */
    public static final Attribute<Boolean> SCROLL_EXEMPT = of("scroll-exempt", Boolean.class, false);

    /**
     * Which of this node's kind's events a session has asked to hear about — space-separated, like
     * HTML's own {@code class} and {@code part}.
     *
     * <p>An attribute rather than a field because it is the last piece of per-INSTANCE description
     * the mirror still carried specially: M2's note said {@code reportedEvents} stayed a field "only
     * because the encoder that writes it is a context-free {@code Codec<UIElement>}", and a carried
     * attribute needs no encoder of its own.</p>
     *
     * <p>Space-separated rather than a {@code Set}: only the four scalar types and enums cross the
     * wire ({@link #isCarried()}), and inventing a fifth for one key would be a codec everything else
     * pays to know about. It is also what the DOM does with every multi-valued attribute it has.</p>
     */
    public static final Attribute<String> REPORTS = of("reports", String.class, "");

    /**
     * Whether this node's state should outlive it across a session.
     *
     * <p>An ATTRIBUTE rather than a Java flag, so a description can carry it: a tool window built
     * from a stylesheet-driven layout keeps its divider across a restart without its panel class
     * knowing {@link SessionState} exists. Read only by that class, which asks the node's contract
     * for the payload -- so opting in costs nothing for a widget whose contract carries no state.</p>
     */
    public static final Attribute<Boolean> SESSION_PERSISTENT =
            of("session-persistent", Boolean.class, false);

    private final String name;
    private final Class<T> type;
    private final T initial;

    private Attribute(String name, Class<T> type, T initial) {
        this.name = name;
        this.type = type;
        this.initial = initial;
    }

    /**
     * Declares a key. The name must be unique across the process; a second declaration with the
     * same name is refused rather than silently shadowing the first.
     */
    public static <T> Attribute<T> of(String name, Class<T> type, T initial) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Attribute<T> attribute = new Attribute<>(name, type, initial);
        Attribute<?> existing = BY_NAME.putIfAbsent(name, attribute);
        if (existing != null) {
            throw new IllegalStateException("An attribute named '" + name + "' is already declared as "
                    + existing.type.getName());
        }
        return attribute;
    }

    /** The key declared under {@code name}, or {@code null} — how the codec finds one it is handed. */
    @Nullable
    public static Attribute<?> named(String name) {
        return BY_NAME.get(name);
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    /** What a node holds when nothing has set this. */
    public T initial() {
        return initial;
    }

    /** Whether values of this type can be written as text and read back — the wire's question. */
    public boolean isCarried() {
        return type == Boolean.class || type == Integer.class || type == Float.class
                || type == String.class || type.isEnum();
    }

    /** The text form of a value, for a key that {@link #isCarried()}. */
    public String write(T value) {
        return type.isEnum() ? ((Enum<?>) value).name() : String.valueOf(value);
    }

    /** Reads {@link #write}'s output back. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public T parse(String text) {
        if (type == Boolean.class) return type.cast(Boolean.parseBoolean(text));
        if (type == Integer.class) return type.cast(Integer.parseInt(text));
        if (type == Float.class) return type.cast(Float.parseFloat(text));
        if (type == String.class) return type.cast(text);
        if (type.isEnum()) return (T) Enum.valueOf((Class<? extends Enum>) type, text);
        throw new IllegalArgumentException("Attribute '" + name + "' of type " + type.getName()
                + " is not carried on the wire");
    }

    @Override
    public String toString() {
        return name;
    }
}
