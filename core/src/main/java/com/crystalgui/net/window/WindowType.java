package com.crystalgui.net.window;

import java.util.function.Function;

import com.crystalgui.ui.UIElement;

/**
 * <b>What a kind of window is</b> — its id on the wire, and how a client gets typed hold of the tree
 * it rebuilt from that window's description.
 *
 * <h3>The string this replaces</h3>
 *
 * <p>The two halves of a networked UI used to be paired by a raw {@code String}: a server window
 * answered {@code type()} with one and a client called {@code register(type, factory)} with another,
 * and <b>nothing checked that they matched</b>. {@code ClientWindows} handles an unregistered type by
 * mounting the window anyway — deliberately, and it is the one respect this beats
 * {@code MenuScreens} — so a typo produced a window that opened, rendered and reported every event
 * with no local behaviour, no error and no log line. <b>The good outcome and the broken one were
 * pixel-identical.</b></p>
 *
 * <p>Minecraft does not have that problem for a reason worth copying: {@code MenuType<T>} is a
 * registered <em>object</em>, so {@code openMenu} and {@code MenuScreens.register} reference one
 * value rather than two copies of a string. This is that value.</p>
 *
 * <h3>It lives on the panel</h3>
 *
 * <p>The panel is the artefact both sides genuinely share, so the declaration belongs there:</p>
 *
 * <pre>{@code
 * public final class MachinePanel {
 *     public static final WindowType<MachinePanel> TYPE =
 *             WindowType.of("mymod:machine", MachinePanel::bindTo);
 *     …
 * }
 * }</pre>
 *
 * <p>That placement is also what keeps it <b>loader-safe</b>: every reference in the initialiser
 * points at the panel itself. A descriptor that also named the client's behaviour would be a
 * {@code static final} field whose initialiser resolves that constructor at class init — loading a
 * client-only class on a dedicated server, which is exactly what {@code :mc1710:serverSmoke} asserts
 * against. <b>A method-body reference to a client-only class is lazy and safe; a static field holding
 * one is not.</b> So the behaviour stays registered from client code, and what this buys instead is
 * that the registration is <em>type-checked</em> against the panel rather than matched by string.</p>
 *
 * <h3>Two things, and deliberately not four</h3>
 *
 * <p>{@link #id()} and {@link #bind(UIElement)} — what crosses the wire, and how the far side gets
 * typed hold of what arrives. <b>How the server happens to construct its panel is not part of the
 * contract between the two halves</b>, so a {@code create}/{@code rootOf} pair does not belong here:
 * that is the builder's business, and an application that already owns its panel (see
 * {@code plan_ui_host.md} VI.1) has no supplier to give at all. Putting construction in the shared
 * descriptor would bake in an ownership model rather than describe an agreement.</p>
 *
 * <p>{@code bind} is the one that surprises people, so it is worth stating plainly: <b>the client has
 * no panel object and cannot have one.</b> Its tree is decoded from a description that carries tags,
 * not classes — which is precisely what lets an old client draw a new panel. What {@code bind}
 * produces is a <em>binding</em>: the same class, over the rebuilt tree, with the same field names.
 * Android's View Binding and JavaFX's {@code @FXML} injection solve the identical problem the
 * identical way.</p>
 *
 * <p>So the two panels are different instances over different trees. A client-side
 * {@code panel.power.setChecked(…)} is a local write the next state delta overwrites — the
 * preview-not-a-fact rule, unchanged.</p>
 *
 * @param <P> the panel type: a plain holder with a root element in it, never a {@code UIElement}
 *            subclass (a description addresses widgets by tag, and {@code ElementRegistry} throws on
 *            an unregistered one)
 */
public final class WindowType<P> {

    private final String id;
    private final Function<UIElement, P> bind;

    private WindowType(String id, Function<UIElement, P> bind) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("a window type needs an id");
        if (bind == null) throw new IllegalArgumentException("a window type needs a way to bind");
        this.id = id;
        this.bind = bind;
    }

    /**
     * A window whose content is a typed panel.
     *
     * @param id   namespaced, and the only thing that crosses the wire — {@code "mymod:machine"}
     * @param bind resolves a <em>rebuilt</em> tree back into a panel. The client's half
     */
    public static <P> WindowType<P> of(String id, Function<UIElement, P> bind) {
        return new WindowType<>(id, bind);
    }

    /**
     * A window whose content is just a tree — <b>the tree is its own panel</b>.
     *
     * <p>{@code bind} is the identity, which is not a degenerate case so much as the honest one: with
     * nothing to bind <em>to</em>, binding is nothing. Right for a window assembled inline, and for
     * anything that reaches its widgets some other way.</p>
     */
    public static WindowType<UIElement> bare(String id) {
        return new WindowType<>(id, root -> root);
    }

    /** What crosses the wire, and what both registries key on. */
    public String id() {
        return id;
    }

    /**
     * Typed hold of a tree the client rebuilt from a description.
     *
     * <p>Throws when the tree is not the shape this type expects — which is the point. A binding that
     * quietly returned a panel with null fields would put the silent-skip failure back one level
     * down, where it is harder to see rather than easier. @see UIElement#require</p>
     */
    public P bind(UIElement rebuilt) {
        if (rebuilt == null) throw new IllegalArgumentException("nothing to bind <" + id + "> to");
        return bind.apply(rebuilt);
    }

    /**
     * Identity, deliberately — <b>two types with one id are two types</b>.
     *
     * <p>Equality by id would make a duplicate declaration look like the same window and silently
     * merge two mods' UIs. A duplicate id is a collision to be reported, not resolved.</p>
     */
    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return id;
    }
}
