package com.crystalgui.net.host;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgui.ui.UIElement;

/**
 * A {@link ServerWindow} from lambdas — <b>for a window that does not need a class</b>.
 *
 * <p>The abstract class is right for anything with a model reference, fragments, or state that
 * outlives a tick. It must not be the entry price, or the answer to "show me a panel" is "first
 * declare a type". LDLib2 makes the same call in the other direction: its {@code BlockUI} is a
 * {@code @FunctionalInterface} precisely so a block can declare a UI without declaring a class to hold
 * it.</p>
 *
 * <pre>{@code
 * ServerUiHost.of(connection).open(
 *     UiWindows.window("mymod:machine", MachinePanel::new, p -> p.root)
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
 * <p>Typed on the panel, so the wiring lambda gets the panel's real fields rather than a tree and a
 * fistful of {@code querySelector} strings. The result <em>is</em> a {@code ServerWindow} — same
 * lifecycle, same close matrix, nothing forked — so the two shapes can be mixed freely and a window
 * that outgrows the builder becomes a class without anything around it changing.</p>
 *
 * <h3>The panel is not a {@code UIElement}, and that is on purpose</h3>
 *
 * <p>A description addresses widgets by tag, and {@code ElementRegistry} <b>throws</b> on an
 * unregistered one — so a {@code MachinePanel extends UIElement} would encode as {@code <machinepanel>}
 * and fail to decode on the client. A panel is therefore a plain holder with a root element in it, and
 * the builder takes the function that reaches it. {@link #window(String, Supplier)} is the shorthand
 * for the case where the tree <em>is</em> the whole panel.</p>
 */
public final class UiWindows {

    private UiWindows() {
    }

    /** A window whose contents are just a tree. */
    public static Builder<UIElement> window(String type, Supplier<UIElement> contents) {
        return new Builder<>(type, contents, root -> root);
    }

    /**
     * A window built from a typed panel — the ergonomic shape.
     *
     * @param contents builds the panel, once, when the window opens
     * @param rootOf   reaches the panel's root element
     */
    public static <P> Builder<P> window(String type, Supplier<P> contents, Function<P, UIElement> rootOf) {
        return new Builder<>(type, contents, rootOf);
    }

    /** Collects the lambdas. Every one is optional except the type and the contents. */
    public static final class Builder<P> {

        private final String type;
        private final Supplier<P> contents;
        private final Function<P, UIElement> rootOf;

        @Nullable
        private Function<P, String> title;
        @Nullable
        private String key;
        @Nullable
        private BiConsumer<P, SessionScope> wire;
        @Nullable
        private BiConsumer<P, SessionScope> tick;
        @Nullable
        private Predicate<Object> stillValid;
        @Nullable
        private Consumer<ServerWindow.CloseReason> onClosed;

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
        public Builder<P> wire(BiConsumer<P, SessionScope> wire) {
            this.wire = wire;
            return this;
        }

        /** One world tick. Mirror the model into widgets and stop; the host flushes. */
        public Builder<P> tick(BiConsumer<P, SessionScope> tick) {
            this.tick = tick;
            return this;
        }

        /** May this window go on existing? @see ServerWindow#stillValid */
        public Builder<P> stillValid(Predicate<Object> stillValid) {
            this.stillValid = stillValid;
            return this;
        }

        /** Told once, however the window ended. */
        public Builder<P> onClosed(Consumer<ServerWindow.CloseReason> onClosed) {
            this.onClosed = onClosed;
            return this;
        }

        /** The window. Usually handed straight to {@link ServerUiHost#open}. */
        public ServerWindow build() {
            return new Built<>(this);
        }

        /** The panel, once the window has been opened — for a caller that wants to keep reaching it. */
        @Nullable
        public P panel() {
            return built;
        }

        @Nullable
        private P built;
    }

    /**
     * The window a {@link Builder} makes.
     *
     * <p>An ordinary {@code ServerWindow} with its five overrides delegating to lambdas. It exists as a
     * real class rather than an anonymous one so the panel can be built lazily and exactly once: the
     * host asks for {@link #root()} before it binds, and the same instance has to reach every lambda
     * afterwards.</p>
     */
    private static final class Built<P> extends ServerWindow {

        private final Builder<P> spec;

        /**
         * Kept from {@link #bind}, because {@link #tick} takes the same one — a tick that notifies or
         * calls does so in the namespace its handlers were registered in, which is what a scope is.
         */
        @Nullable
        private SessionScope scope;

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
        protected void bind(SessionScope io) {
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
