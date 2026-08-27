package com.crystalgui.net.window;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgui.ui.UIElement;

/**
 * <b>A {@link Panel}'s identity</b> — the one value both sides reference, and the only string a panel
 * needs.
 *
 * <pre>{@code
 * public static final PanelType<MachinePanel, MachineModel> TYPE =
 *         PanelType.of("mymod:machine", MachinePanel::new);
 * }</pre>
 *
 * <p>From there the whole of the wiring is two call sites:</p>
 *
 * <pre>{@code
 * ServerWindows.of(connection).open(MachinePanel.TYPE.serve(machine));   // server
 * ClientWindows.register(MachinePanel.TYPE);                             // client, once at init
 * }</pre>
 *
 * <p>It is a {@link WindowType} with the panel's construction attached — the type answers "how do I
 * bind one" for the client registry, and the supplier answers "how do I build one" for the server.
 * {@link WindowType} itself deliberately carries only the first, because how a server constructs its
 * content is not part of the contract between the two halves; here the two are together because a
 * panel <em>is</em> both.</p>
 *
 * <h3>Loader-safe, and for a stated reason</h3>
 *
 * <p>Declared as a {@code static final} on the panel, every reference in the initialiser points at the
 * panel class itself — never at a behaviour, never at a model. A descriptor that named a client-only
 * behaviour would resolve it at class init and load it on a dedicated server, which is exactly what
 * {@code :mc1710:serverSmoke} asserts against.</p>
 *
 * @param <P> the panel
 * @param <M> what it is a view of
 */
public final class PanelType<P extends Panel<M>, M> {

    private final WindowType<P> type;
    private final Supplier<P> create;

    private PanelType(String id, Supplier<P> create) {
        if (create == null) throw new IllegalArgumentException("a panel type needs a constructor");
        this.create = create;
        // The bind half of the WindowType IS the base's field walk, so a panel author never writes one.
        this.type = WindowType.of(id, rebuilt -> Panel.bind(create, rebuilt));
    }

    /**
     * @param id     namespaced, and the only thing that crosses the wire — {@code "mymod:machine"}
     * @param create the panel's no-argument constructor, used for both building and binding
     */
    public static <P extends Panel<M>, M> PanelType<P, M> of(String id, Supplier<P> create) {
        return new PanelType<>(id, create);
    }

    /** What crosses the wire. */
    public String id() {
        return type.id();
    }

    /** The underlying descriptor — what {@code ClientWindows} and {@code ServerWindow} key on. */
    public WindowType<P> windowType() {
        return type;
    }

    /** A fresh, built panel: parts created, named, and arranged. Server side. */
    public P build(@Nullable M model) {
        return Panel.build(create, model);
    }

    /**
     * A window serving a fresh panel over {@code model} — <b>the whole server side of a UI</b>.
     *
     * <p>Hand it straight to {@code ServerWindows.open}. The panel's {@link Panel#serve} runs when the
     * host binds it, before the client is told anything, and {@link Panel#layout} has already run.</p>
     */
    public ServerWindow serve(@Nullable M model) {
        return new PanelWindow<>(this, model);
    }

    @Override
    public String toString() {
        return id();
    }

    /**
     * The {@link ServerWindow} a panel does not have to write.
     *
     * <p>Everything it overrides is answerable from the panel, which is the point: {@code type()} and
     * {@code root()} are bookkeeping, and {@code bind} is a hand-off. What a mod would actually have
     * written — the handlers — lives on the panel where the widgets are.</p>
     */
    private static final class PanelWindow<P extends Panel<M>, M> extends ServerWindow {

        private final PanelType<P, M> type;
        private final P panel;

        PanelWindow(PanelType<P, M> type, @Nullable M model) {
            this.type = type;
            this.panel = type.build(model);
        }

        @Override
        public WindowType<P> type() {
            return type.windowType();
        }

        @Override
        public UIElement root() {
            return panel.root();
        }

        @Override
        protected void bind(WindowScope io) {
            panel.serve(io);
        }

        @Override
        protected void onClosed(CloseReason reason) {
            panel.closed(reason.name());
        }

        /** So a caller that wants the panel back can reach it. */
        P panel() {
            return panel;
        }
    }

    /** The built panel behind a window this type made, or {@code null} for a window it did not. */
    @Nullable
    @SuppressWarnings("unchecked")
    public P panelOf(ServerWindow window) {
        if (!(window instanceof PanelWindow)) return null;
        PanelWindow<?, ?> made = (PanelWindow<?, ?>) window;
        return made.type == this ? (P) made.panel() : null;
    }
}
