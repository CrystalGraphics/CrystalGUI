package com.crystalgui.ui.dom;

import com.crystalgui.ui.input.FocusPolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * which is what retires the keymap and settings fields (plan/engine-core.md D5.4).</p>
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
    public static final Attribute<Boolean> ENABLED = of("enabled", Boolean.class, true)
            .describedAs("Whether it responds to input. A disabled element also matches :disabled.");
    /** The HTML {@code inert} attribute: the subtree keeps its box and stops being interactive. */
    public static final Attribute<Boolean> INERT = of("inert", Boolean.class, false)
            .describedAs("Takes no input and no focus, and neither does anything inside it.");
    /** Whether hit-testing may land on this subtree; {@code pointer-events: none} when false. */
    public static final Attribute<Boolean> HIT_TEST = of("hit-test", Boolean.class, true)
            .describedAs("Whether a click can land on it or on anything inside it.");

    /**
     * A press inside this subtree leaves the focus owner alone.
     *
     * <p><b>The engine's default is the web's</b>: {@code Focus.pressed} walks up from what was hit for
     * the nearest click-focusable ancestor and, finding none, <em>clears</em> focus — which is Blink's
     * {@code HandleMouseFocus}, and why clicking a bare {@code <div>} blurs the input beside it. The web
     * cancels that by calling {@code preventDefault()} on the mousedown, because there focus assignment
     * is the press's default action and therefore runs after dispatch.</p>
     *
     * <p>Here it runs <em>before</em> dispatch, so no handler can object in time, and
     * {@code preventDefault()} already means several other things on a press across this codebase —
     * fourteen mouse-down handlers call it for drags and scrubs that do want focus. Making focus the
     * press's default action would silently stop focus at all of them, so the opt-out is declared on the
     * element instead.</p>
     *
     * <h3>This name is ours</h3>
     *
     * <p><b>The web has no declarative form of this</b>, which is worth saying plainly because the
     * neighbours here do: {@code inert} is HTML's, and {@code hit-test} is {@code pointer-events} under
     * another name. There is no attribute that means "a click in here must not move focus" — on the web
     * that is only expressible as behaviour, through {@code preventDefault()} above.</p>
     *
     * <p>The nearest prior art is <b>Swing's</b> {@code JComponent.setRequestFocusEnabled(false)}, which
     * is a hint that a click should not focus the component — cited from memory rather than from a
     * checkout, and it differs in scope either way: Swing's answers for one component, this answers for
     * a subtree and defers to any focusable inside it. So it is the same intent, not a port.</p>
     *
     * <p>Subtree-wide, but only for what is <b>not</b> click-focusable itself: the walk stops at the
     * first focusable ancestor as it always did, so a list row inside a retaining popup still takes
     * focus and still selects. What it catches is the caption, the divider, the padding — the parts
     * that are scenery, and whose only effect on focus today is to destroy it.</p>
     */
    public static final Attribute<Boolean> RETAINS_FOCUS = of("retains-focus", Boolean.class, false)
            .describedAs("A press on scenery inside it leaves the focus owner where it is.");

    /**
     * Never the answer to a hit test, though everything inside it still is.
     *
     * <p>Distinct from {@link #HIT_TEST}, and the distinction is the whole point: {@code hit-test:
     * false} is subtree-wide and returns without recursing, so it cannot express "a layer that only
     * holds things". This is what a full-size overlay wants — the top layer already gets it, and every
     * canvas layer needs it for the same reason.</p>
     *
     * <p>Without it a full-size box over a surface is the answer to every hit that lands on background,
     * which {@code Box.search} calls this codebase's most-repeated failure. Setting {@code hit-test}
     * instead is not the alternative: a pick deliberately reaches through that, so a design surface —
     * which picks rather than hit-tests — sees the layer anyway.</p>
     */
    public static final Attribute<Boolean> HIT_TRANSPARENT =
            of("hit-transparent", Boolean.class, false)
                    .describedAs("Clicks pass through it to what is behind, and still reach what is inside it.");

    /**
     * This subtree wants presses that carry a modifier, so a window-level gesture must not take them.
     *
     * <p>A desktop claims {@code Alt+drag} to move a window from anywhere inside it — the Linux WM
     * staple, and the answer for a window whose title bar is covered or off-screen. It has to be taken
     * on the CAPTURE phase to mean "anywhere", which means it reaches content before content does.</p>
     *
     * <p>That is right until the content wants the same modifier. A design canvas spends Alt on resizing
     * from the centre and on suspending snap — so holding Alt and pressing a resize handle moved the
     * WINDOW, and the only way to resize from the centre was to press first and add Alt afterwards.</p>
     *
     * <p>Set on the container, not the control: everything inside a canvas is in the same argument, and
     * a list of individual widgets is a list that goes stale. The desktop gesture still works everywhere
     * else in the window.</p>
     */
    public static final Attribute<Boolean> KEEPS_MODIFIER_PRESS =
            of("keeps-modifier-press", Boolean.class, false)
                    .describedAs("Alt and Ctrl presses stay here instead of moving the window.");
    /** The name of the slot a light child asks to be placed in; empty for the default slot. */
    /**
     * A focus navigation scope: a dialog, a window frame, a pane. Tab is trapped inside whichever
     * one a modal blocks, and "is focus already in here" is asked of one.
     */
    public static final Attribute<Boolean> FOCUS_SCOPE = of("focus-scope", Boolean.class, false)
            .describedAs("Tab stays inside it, as it does in a dialog.");

    /** Whether and how this node takes focus. Four values, and two of them look alike. */
    public static final Attribute<FocusPolicy> FOCUS_POLICY = of("focus-policy", FocusPolicy.class, FocusPolicy.NONE)
            .describedAs("Whether it takes focus, and whether from a click, from Tab, or both.");

    public static final Attribute<String> SLOT = of("slot", String.class, "")
            .describedAs("Which named slot of the template it is placed in; empty is the default slot.");
    /** The {@code ::part()} name a node inside a shadow tree is exposed under; empty for none. */
    public static final Attribute<String> PART = of("part", String.class, "")
            .describedAs("The name a theme reaches it by with ::part() from outside its template.");

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
    public static final Attribute<Boolean> HIDDEN = of("hidden", Boolean.class, false)
            .describedAs("Not shown, and takes no space.");

    /**
     * This box does not move with what it is hosted in — a scrollbar, a gutter, a find bar.
     *
     * <p>The old engine's {@code setScrollExempt}, and the one 5.4 gap the census found: without it a
     * scroller's own bars scroll away with the content they are for. Read by {@code BoxTree}'s
     * composition, which is the only place a host's scroll offset is applied.</p>
     */
    public static final Attribute<Boolean> SCROLL_EXEMPT = of("scroll-exempt", Boolean.class, false)
            .describedAs("Stays put when what holds it scrolls, like a scrollbar.");

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
    public static final Attribute<String> REPORTS = of("reports", String.class, "")
            .describedAs("Which of its events a server hears about, separated by spaces.");

    /**
     * Whether this node's state should outlive it across a session.
     *
     * <p>An ATTRIBUTE rather than a Java flag, so a description can carry it: a tool window built
     * from a stylesheet-driven layout keeps its divider across a restart without its panel class
     * knowing {@link SessionState} exists. Read only by that class, which asks the node's contract
     * for the payload -- so opting in costs nothing for a widget whose contract carries no state.</p>
     */
    public static final Attribute<Boolean> SESSION_PERSISTENT =
            of("session-persistent", Boolean.class, false)
                    .describedAs("Keeps its state, such as a divider's position, across a restart.");

    private final String name;
    private final Class<T> type;
    private final T initial;

    @Nullable
    private volatile String description;

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

    /** Every declared key, by name — what an editor lists. */
    public static List<Attribute<?>> declared() {
        List<Attribute<?>> all = new ArrayList<>(BY_NAME.values());
        all.sort(Comparator.comparing(Attribute::name));
        return all;
    }

    /** The key declared under {@code name}, or {@code null} — how the codec finds one it is handed. */
    @Nullable
    public static Attribute<?> named(String name) {
        return BY_NAME.get(name);
    }

    public String name() {
        return name;
    }

    /**
     * Says what this attribute does, for an editor's hint — declared where the attribute is.
     *
     * <pre>{@code
     * public static final Attribute<Boolean> HIDDEN = of("hidden", Boolean.class, false)
     *         .describedAs("Not shown, and takes no space.");
     * }</pre>
     */
    public Attribute<T> describedAs(String text) {
        this.description = text;
        return this;
    }

    /** What {@link #describedAs} said, or null. */
    @Nullable
    public String description() {
        return description;
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
