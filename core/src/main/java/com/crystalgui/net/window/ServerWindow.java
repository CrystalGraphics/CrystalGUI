package com.crystalgui.net.window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgui.net.ServerUiSession;
import com.crystalgui.ui.UIElement;

/**
 * One networked window: <b>the server-side unit a mod authors</b>.
 *
 * <p>What Minecraft's {@code Container} and LDLib2's {@code IContainerUIHolder} are, against a
 * described tree instead of slots. Everything about the window's <em>lifecycle</em> belongs to
 * {@link ServerWindows} — allocating the id, constructing the session, opening it, ticking it, and
 * every one of the ways it can end. What belongs here is the tree, the behaviour, and the three
 * questions only this window can answer.</p>
 *
 * <h3>The shape it replaces</h3>
 *
 * <p>Before this, a mod opened a window by walking the player list from its own tick handler, checking
 * a map of its own, constructing a {@code ServerUiSession} against a hard-coded window id, and
 * remembering to tick it and to close it on a logout event it also had to subscribe to. Twenty mods
 * meant twenty poll loops asking a question the connection layer had already answered once. None of
 * that is here because none of it is a window's business.</p>
 *
 * <pre>{@code
 * public final class MachineWindow extends ServerWindow {
 *     private final MachineModel model;              // world state; this window is a VIEW of it
 *     private final MachinePanel panel = new MachinePanel();
 *
 *     public MachineWindow(MachineModel model) { this.model = model; }
 *
 *     @Override public String type()  { return "mymod:machine"; }
 *     @Override public String title() { return model.label(); }
 *     @Override public String key()   { return "mymod:machine"; }   // one per viewer
 *     @Override public UIElement root() { return panel.root; }
 *
 *     @Override protected void bind(WindowScope io) {
 *         io.onActivate(panel.purge, ctx -> model.purge());
 *         io.onCall("rename", (args, respond) -> { … });
 *     }
 *
 *     @Override protected void tick() { panel.status.setText(model.summary()); }
 *     @Override protected boolean stillValid(Object viewer) { return model.exists(); }
 * }
 * }</pre>
 *
 * <p>A window that is one screenful of handlers does not need a class at all —
 * {@link #of(String, Supplier, Function)} builds one from lambdas. Extending this is for anything with
 * a model reference, fragments, or state that outlives a tick.</p>
 *
 * <h3>The model does not live here</h3>
 *
 * <p>A machine is world state and ticks with the world; this is a view of it whose lifetime is bounded
 * by a viewer. Fusing the two — advancing the model from {@link #tick()} — makes the machine stop
 * existing when somebody closes a window, which is the opposite of what a server-authoritative UI is
 * for. Hold a reference to the model and mirror it; do not <em>be</em> it.</p>
 *
 * <h3>Threading</h3>
 *
 * <p>Every method here runs on the thread that ticked the connection — the server thread in game,
 * whatever drives the loopback in a test. The host adds no thread and no callback from the network
 * thread.</p>
 */
public abstract class ServerWindow {

    /** Set by the host at open, cleared when the window ends. */
    @Nullable
    ServerWindows host;

    @Nullable
    ServerUiSession<Object> session;

    int windowId = -1;

    boolean live;

    /** Attached in order, ticked after this window. @see WindowScope#attach */
    final List<ServerFragment> fragments = new ArrayList<>();

    // ── What a window says about itself ─────────────────────────────────────

    /**
     * What kind of window this is — {@code "mymod:machine"}, namespaced like everything else on the
     * wire.
     *
     * <p>Travels on {@code ui/openWindow} and is what a client dispatches its local behaviour on
     * ({@code ClientWindows.register}). A client that has no factory for the type still shows the
     * window, correctly and interactively: a description is self-sufficient, so an unknown type simply
     * has no local extras. That is the one respect in which this beats Minecraft's own model, where an
     * unknown {@code MenuType} is a broken screen.</p>
     */
    public abstract String type();

    /** The tree. Built once, by whatever this window's constructor did; the host asks for it at open. */
    public abstract UIElement root();

    /** What to call it on screen. The side that opens a window is the side that knows what it is. */
    public String title() {
        return type();
    }

    /**
     * Uniqueness and persistence key for this viewer, or {@code null} for "always a new window".
     *
     * <p>Two things at once. {@link ServerWindows#open} refuses to open a second window under a key that
     * is already open and brings the existing one forward instead — Minecraft's close-the-previous rule
     * narrowed to the same <em>subject</em> rather than applied to every window. And the client's frame
     * takes it, so the compositor restores its geometry where the user left it.</p>
     */
    @Nullable
    public String key() {
        return null;
    }

    // ── What a window does ──────────────────────────────────────────────────

    /**
     * Registers everything this window can do. Called once, by the host, <b>before</b> the session
     * opens.
     *
     * <p>That ordering is not advice a caller has to follow: the host does it, so the "handlers must be
     * registered before open" rule cannot be broken from here.</p>
     */
    protected abstract void bind(WindowScope io);

    /**
     * One world tick while this window is open.
     *
     * <p>Mirror the model into widgets here and stop — every setter is idempotent and marks its element
     * dirty, and the host flushes the whole batch afterwards as one message. Nothing here has to know
     * which fields moved.</p>
     */
    protected void tick() {
    }

    /**
     * Minecraft's {@code canInteractWith}, LDLib2's {@code isStillValid}: may this window go on
     * existing?
     *
     * <p>Checked by the host every tick, before {@link #tick()}. Answering false closes the window with
     * {@link CloseReason#NOT_VALID} — a block broken, a player walked away, an inventory gone. Default
     * true: a window that lives until something closes it.</p>
     *
     * @param viewer the connection's peer — the platform's player handle, or {@code null} in loopback
     */
    protected boolean stillValid(@Nullable Object viewer) {
        return true;
    }

    /**
     * Every way this window can end funnels here, exactly once.
     *
     * <p>A report rather than a veto: by the time this runs the window has stopped serving. A window
     * that wants to <em>refuse</em> a close has to do it on the client, where the discard guard lives —
     * the same split every windowing system makes between "the user asked" and "it happened".</p>
     */
    protected void onClosed(CloseReason reason) {
    }

    /** Why a window ended. @see ServerWindows */
    public enum CloseReason {
        /** The server asked — {@code host.close(window, …)}. */
        SERVER,
        /** The user closed the frame. {@code ui/close}, the direction that used to be missing. */
        CLIENT,
        /** {@link #stillValid} answered false. */
        NOT_VALID,
        /** The peer went away: a logout, a kick, a server stop. Nothing was sent; nobody was there. */
        CONNECTION_LOST
    }

    // ── What the host gives back ────────────────────────────────────────────

    /** This window's session, or {@code null} before it is opened and after it ends. */
    @Nullable
    public final ServerUiSession<Object> session() {
        return session;
    }

    /** Allocated by the host, unique on its connection. Never a constant a mod picks. */
    public final int windowId() {
        return windowId;
    }

    /** The platform's handle for whoever is watching, or {@code null} in loopback. */
    @Nullable
    public final Object viewer() {
        return host == null ? null : host.peer();
    }

    /** Whether this window is currently being served. */
    public final boolean isOpen() {
        return live;
    }

    /** Asks the host to end this window. Safe on one that has already ended. */
    public final void close(String reason) {
        if (host != null) host.close(this, reason);
    }

    // ── The same window, from lambdas ───────────────────────────────────────

    /**
     * A window whose contents are just a tree.
     *
     * @see #of(String, Supplier, Function)
     */
    public static Builder<UIElement> of(String type, Supplier<UIElement> contents) {
        return new Builder<>(type, contents, root -> root);
    }

    /**
     * A window built from lambdas — <b>for one that does not need a class</b>.
     *
     * <p>Extending {@code ServerWindow} is right for anything with a model reference, fragments, or
     * state that outlives a tick. It must not be the entry price, or the answer to "show me a panel"
     * is "first declare a type". LDLib2 makes the same call from the other end: its {@code BlockUI} is
     * a {@code @FunctionalInterface} precisely so a block can declare a UI without declaring a class to
     * hold it.</p>
     *
     * <pre>{@code
     * ServerWindows.of(connection).open(
     *     ServerWindow.of("mymod:machine", MachinePanel::new, p -> p.root)
     *         .key("mymod:machine")
     *         .title(p -> model.label())
     *         .wire((p, io) -> {
     *             io.onActivate(p.purge, ctx -> model.purge());
     *             io.onCall("rename", (args, respond) -> { … });
     *         })
     *         .tick((p, io) -> p.status.setText(model.summary()))
     *         .stillValid(viewer -> model.exists()));
     * }</pre>
     *
     * <p>Typed on the panel, so the wiring lambda gets its real fields rather than a tree and a fistful
     * of {@code querySelector} strings. What comes out <em>is</em> a {@code ServerWindow} — same
     * lifecycle, same close matrix, nothing forked — so the two shapes mix freely and a window that
     * outgrows the builder becomes a class without anything around it changing.</p>
     *
     * <h3>The panel is deliberately not a {@code UIElement}</h3>
     *
     * <p>A description addresses widgets by tag and {@code ElementRegistry} <b>throws</b> on an
     * unregistered one — so a {@code MachinePanel extends UIElement} would encode as
     * {@code <machinepanel>} and fail to decode on the client. A panel is therefore a plain holder with
     * a root element in it, and this takes the function that reaches it.
     * {@link #of(String, Supplier)} is the shorthand for when the tree <em>is</em> the whole panel.</p>
     *
     * @param contents builds the panel, once, when the window opens
     * @param rootOf   reaches the panel's root element
     */
    public static <P> Builder<P> of(String type, Supplier<P> contents, Function<P, UIElement> rootOf) {
        return new Builder<>(type, contents, rootOf);
    }

    /**
     * Collects the lambdas. Every one is optional except the type and the contents.
     *
     * <p>Hand it straight to {@link ServerWindows#open(Builder)} — there is a {@link #build()} for the
     * caller who wants the window itself, but a fluent chain that must end in a call nobody needs is a
     * call somebody will forget.</p>
     */
    public static final class Builder<P> {

        private final String type;
        private final Supplier<P> contents;
        private final Function<P, UIElement> rootOf;

        @Nullable
        private Function<P, String> title;
        @Nullable
        private String key;
        @Nullable
        private BiConsumer<P, WindowScope> wire;
        @Nullable
        private BiConsumer<P, WindowScope> tick;
        @Nullable
        private Predicate<Object> stillValid;
        @Nullable
        private Consumer<CloseReason> onClosed;

        /** Built once, lazily, and shared by every lambda. @see Built#panel() */
        @Nullable
        private P built;

        Builder(String type, Supplier<P> contents, Function<P, UIElement> rootOf) {
            if (type == null || type.isEmpty()) throw new IllegalArgumentException("a window needs a type");
            if (contents == null) throw new IllegalArgumentException("a window needs contents");
            if (rootOf == null) throw new IllegalArgumentException("a window needs a root");
            this.type = type;
            this.contents = contents;
            this.rootOf = rootOf;
        }

        /** What to call it on screen. Read once, at open. */
        public Builder<P> title(Function<P, String> title) {
            this.title = title;
            return this;
        }

        /** A fixed title, for the common case. */
        public Builder<P> title(String title) {
            return title(panel -> title);
        }

        /** Uniqueness and persistence key. @see ServerWindow#key() */
        public Builder<P> key(@Nullable String key) {
            this.key = key;
            return this;
        }

        /** Registers the behaviour. Runs once, before the session opens. */
        public Builder<P> wire(BiConsumer<P, WindowScope> wire) {
            this.wire = wire;
            return this;
        }

        /** One world tick. Mirror the model into widgets and stop; the host flushes. */
        public Builder<P> tick(BiConsumer<P, WindowScope> tick) {
            this.tick = tick;
            return this;
        }

        /** May this window go on existing? @see ServerWindow#stillValid */
        public Builder<P> stillValid(Predicate<Object> stillValid) {
            this.stillValid = stillValid;
            return this;
        }

        /** Told once, however the window ended. */
        public Builder<P> onClosed(Consumer<CloseReason> onClosed) {
            this.onClosed = onClosed;
            return this;
        }

        /** The window itself. {@link ServerWindows#open(Builder)} calls this for you. */
        public ServerWindow build() {
            return new Built<>(this);
        }

        /** The panel, once the window has been opened — for a caller that keeps reaching it. */
        @Nullable
        public P panel() {
            return built;
        }
    }

    /**
     * The window a {@link Builder} makes.
     *
     * <p>An ordinary {@code ServerWindow} whose overrides delegate to lambdas. A named class rather
     * than an anonymous one so the panel can be built lazily and <b>exactly once</b>: the host asks for
     * {@link #root()} before it binds, and the same instance has to reach every lambda afterwards.</p>
     */
    private static final class Built<P> extends ServerWindow {

        private final Builder<P> spec;

        /**
         * Kept from {@link #bind}, because {@link #tick} takes the same one — a tick that notifies or
         * calls does so in the namespace its handlers were registered in, which is what a scope is.
         */
        @Nullable
        private WindowScope scope;

        Built(Builder<P> spec) {
            this.spec = spec;
        }

        private P panel() {
            if (spec.built == null) spec.built = spec.contents.get();
            return spec.built;
        }

        @Override
        public String type() {
            return spec.type;
        }

        @Override
        public UIElement root() {
            return spec.rootOf.apply(panel());
        }

        @Override
        public String title() {
            return spec.title == null ? spec.type : spec.title.apply(panel());
        }

        @Nullable
        @Override
        public String key() {
            return spec.key;
        }

        @Override
        protected void bind(WindowScope io) {
            scope = io;
            if (spec.wire != null) spec.wire.accept(panel(), io);
        }

        @Override
        protected void tick() {
            if (spec.tick != null && scope != null) spec.tick.accept(panel(), scope);
        }

        @Override
        protected boolean stillValid(@Nullable Object viewer) {
            return spec.stillValid == null || spec.stillValid.test(viewer);
        }

        @Override
        protected void onClosed(CloseReason reason) {
            if (spec.onClosed != null) spec.onClosed.accept(reason);
        }
    }
}
