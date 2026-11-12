package com.crystalgui.desktop.app;

import java.util.function.BooleanSupplier;

import javax.annotation.Nullable;

import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;

/**
 * An application's handle on one of its windows: what to show in it, requests to the window manager, and the events
 * the manager reports back. You get one from {@link AppWindows#open} and never touch the frame that draws it.
 *
 * <pre>{@code
 * AppWindow main = app.windows().open(WindowSpec.titled("Planner").size(900, 600));
 * main.setContent(new PlannerView(app));
 * main.onCloseRequested(() -> !dirty());          // false keeps the window: ask the user first
 * main.onClosed(() -> release());                 // however it was closed
 *
 * AppWindow dialog = main.openOwned(WindowSpec.titled("Rename").size(360, 140));
 * dialog.setContent(new RenameForm(name -> { rename(name); dialog.close(); }));
 * }</pre>
 *
 * <ul>
 *   <li>Every method that changes the window is a <em>request</em>: the manager decides, and {@link #state()} is what
 *       it last reported, so read it after the request has been served, not straight after asking.</li>
 *   <li>Two handles on one window are equal, so a handle is safe as a map key or in a list.</li>
 *   <li>{@link #destroy()} ends a window without asking; {@link #close()} asks first, as the X does.</li>
 * </ul>
 */
public final class AppWindow {

    private final WindowFrame frame;
    @Nullable
    private final Desktop desktop;
    /** The application's own document and this window's client in it; null when it runs in the shell's. */
    @Nullable
    private final AppDocument document;
    @Nullable
    private final ClientRoot client;

    private AppWindow(WindowFrame frame, @Nullable Desktop desktop, @Nullable AppDocument document,
                      @Nullable ClientRoot client) {
        this.frame = frame;
        this.desktop = desktop;
        this.document = document;
        this.client = client;
    }

    /** A handle on {@code frame}. For the engine, which owns frames; an application gets its handles from {@link AppWindows}. */
    public static AppWindow of(WindowFrame frame) {
        return new AppWindow(frame, null, null, null);
    }

    static AppWindow of(WindowFrame frame, Desktop desktop, @Nullable AppDocument document, @Nullable ClientRoot client) {
        return new AppWindow(frame, desktop, document, client);
    }

    /**
     * Runs {@code work} on the shell's document, where the frame lives: at once when already there. Every request an
     * application makes of its window goes through here, so it may be made from the application's own thread.
     */
    void onShell(Runnable work) {
        UIDocument at = shellDocument();
        if (at == null) work.run();
        else at.execute(work);
    }

    /**
     * Runs {@code work} on the application's thread, where its content lives: at once when already there, else after
     * what is queued. For code that holds this handle on another thread and wants to touch what the window shows.
     *
     * <pre>{@code
     * window.execute(() -> view.select(row));
     * }</pre>
     */
    public void execute(Runnable work) {
        onApplication(work);
    }

    private void onApplication(Runnable work) {
        if (document != null) document.execute(work);
        else onShell(work);
    }

    @Nullable
    private UIDocument shellDocument() {
        Desktop shell = desktop != null ? desktop : frame.desktop();
        return shell == null ? null : shell.document();
    }

    /** The frame that draws this window. The engine's: an application never names it. */
    public WindowFrame frame() {
        return frame;
    }

    /** The element the window shows its content in; append to it, or replace it with {@link #setContent}. */
    public UIElement content() {
        return client != null ? client : frame.content();
    }

    /**
     * Shows {@code root} in the window. A root that is a {@code WindowChrome}, as a workbench is, also supplies the
     * window's menu bar, so pass it here rather than appending it to {@link #content()}.
     */
    public AppWindow setContent(UIElement root) {
        if (client != null) client.setContent(root);
        else frame.setContent(root);
        return this;
    }

    /** The title shown in the caption, the taskbar and the switcher. */
    public String title() {
        return frame.getTitle();
    }

    /** Requests a new title. */
    public AppWindow title(String title) {
        onShell(() -> frame.setTitle(title));
        return this;
    }

    /** Requests a namespaced icon id, or null for the monogram of the title. */
    public AppWindow icon(@Nullable String icon) {
        onShell(() -> frame.setIcon(icon));
        return this;
    }

    /** Where the window is in its life: visible, hidden (minimised or put away), or destroyed. */
    public WindowState state() {
        return frame.state();
    }

    /** Whether this window holds the focus. */
    public boolean isActive() {
        return frame.isActive();
    }

    /** Requests a place, in the work area's coordinates; the manager clamps it on screen. */
    public AppWindow moveTo(float left, float top) {
        onShell(() -> frame.moveTo(left, top));
        return this;
    }

    /** Requests a size; the window's minimum size wins over a smaller one. */
    public AppWindow resizeTo(float width, float height) {
        onShell(() -> frame.resizeTo(width, height));
        return this;
    }

    /** Sends the window to its taskbar entry; {@link #activate()} brings it back. */
    public void minimise() {
        onShell(frame::minimize);
    }

    public void maximise() {
        onShell(frame::maximize);
    }

    /** Back from maximised, or from hidden, to where it was. */
    public void restore() {
        onShell(() -> {
            if (frame.state() == WindowState.HIDDEN) frame.show(true);
            else frame.restore();
        });
    }

    /** Brings the window forward whatever state it is in: shown if hidden, raised if behind, focused either way. */
    public void activate() {
        onShell(() -> {
            if (frame.state() == WindowState.HIDDEN) frame.show(true);
            Desktop on = frame.desktop();
            if (on != null) on.windowManager().activate(frame, true);
        });
    }

    /**
     * Closes the window as its X does: the {@link #onCloseRequested} guard is asked, then the window's policy
     * destroys or hides it.
     *
     * @return whether the request was handled; a refusing guard still handles it. From another document's thread the
     *         request is posted, and answered true
     */
    public boolean close() {
        UIDocument shell = shellDocument();
        if (shell == null || shell.isCurrent()) return frame.requestClose();
        shell.execute(frame::requestClose);
        return true;
    }

    /** Ends the window now, without asking the guard. */
    public void destroy() {
        onShell(frame::destroy);
    }

    /**
     * Asks before a close the user made. Return false to keep the window; the answer is final for that request, so
     * to prompt, return false, ask, then call {@link #close()} again from the prompt's callback. Asked on the shell's
     * thread, so it reads only what the application keeps safe to read there.
     */
    public AppWindow onCloseRequested(BooleanSupplier allow) {
        onShell(() -> frame.setDiscardGuard(allow));
        return this;
    }

    /** Runs once the window has been destroyed, however it ended, on the application's thread. */
    public AppWindow onClosed(Runnable then) {
        onShell(() -> frame.onDestroyed.connect(() -> onApplication(then)));
        return this;
    }

    /**
     * Opens a window owned by this one: it minimises and closes with it and stays above it.
     *
     * @throws IllegalStateException when this window is not on a desktop (it was destroyed)
     */
    public AppWindow openOwned(WindowSpec spec) {
        Desktop on = desktop != null ? desktop : frame.desktop();
        if (on == null) throw new IllegalStateException("window is not on a desktop: " + title());
        return AppWindows.open(on, frame.application(), document, spec.ownedBy(this));
    }

    /**
     * Adds a settings gear to the title bar, left of the pin, for settings that belong to the window itself.
     *
     * <pre>{@code
     * AppWindow.CaptionAction gear = window.addSettingsAction(panel::toggleSettings);
     * panel.onSettingsToggled.connect(gear::lit);      // shown as on while the panel is open
     * }</pre>
     */
    public CaptionAction addSettingsAction(Runnable onPress) {
        CaptionAction action = new CaptionAction(this);
        onShell(() -> action.button = frame.addCaptionAction(WindowFrame.SETTINGS_ACTION_CLASS, "Settings",
                () -> onApplication(onPress)));
        return action;
    }

    /** A button an application added to its window's title bar. */
    public static final class CaptionAction {

        private final AppWindow window;
        /** Set by the request that adds it; read on the shell's thread, behind that request. */
        private Button button;

        private CaptionAction(AppWindow window) {
            this.window = window;
        }

        /** Shows the action as on, as while the panel it opens is shown. */
        public void lit(boolean on) {
            window.onShell(() -> {
                if (on) button.addClass(WindowFrame.ACTION_ON_CLASS);
                else button.removeClass(WindowFrame.ACTION_ON_CLASS);
            });
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AppWindow window && window.frame == frame;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(frame);
    }
}
