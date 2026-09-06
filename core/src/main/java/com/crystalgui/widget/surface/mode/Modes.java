package com.crystalgui.widget.surface.mode;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.core.signal.Signal;
import java.util.function.Supplier;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Input;
import com.crystalgui.widget.surface.SurfaceContext;

/**
 * Which tool is current, and the one {@link Input} mode that feeds it.
 *
 * <pre>{@code
 * ctx.modes().use("crystalgui:select");
 * ctx.modes().onDidChangeTool.connect(this::refreshStrip);
 * }</pre>
 *
 * <p>A tool is built once, the first time it is picked, and kept — so a tool may hold gesture state
 * between presses. Picking a second tool deactivates the first.</p>
 *
 * <p>The mode is pushed onto the window's input stack when the surface joins one, which is what puts a
 * live gesture ahead of ordinary dispatch. It declines everything outside its own surface, so several
 * open surfaces do not fight over the pointer.</p>
 */
public final class Modes {

    private final SurfaceContext ctx;
    private final SurfaceMode mode;

    @Nullable
    private final Supplier<UIElement> view;

    private final Map<String, Tool> built = new LinkedHashMap<>();

    @Nullable
    private String currentId;

    @Nullable
    private Tool current;

    @Nullable
    private String previousId;

    @Nullable
    private UIDocument pushedOn;

    /** Fires after the current tool changes, including to none. */
    public final Signal.Action onDidChangeTool = new Signal.Action();

    public Modes(SurfaceContext ctx) {
        this(ctx, null);
    }

    /**
     * @param view what a press the surface CLAIMS should focus — the surface widget itself. Handed in
     *             rather than reached for, so {@link SurfaceContext} does not have to expose the element.
     */
    public Modes(SurfaceContext ctx, @Nullable Supplier<UIElement> view) {
        this.ctx = ctx;
        this.view = view;
        this.mode = new SurfaceMode(ctx, this);
    }

    /** @see #Modes(SurfaceContext, Supplier) */
    @Nullable
    UIElement view() {
        return view == null ? null : view.get();
    }

    @Nullable
    public Tool current() {
        return current;
    }

    @Nullable
    public String currentId() {
        return currentId;
    }

    /** Makes the tool registered under {@code id} current. An id nothing registered is a no-op. */
    public void use(@Nullable String id) {
        if (java.util.Objects.equals(currentId, id)) return;
        if (current != null) current.deactivated();
        previousId = currentId;
        currentId = id;
        current = id == null ? null : build(id);
        if (current != null) current.activated();
        onDidChangeTool.emit();
    }

    /** Makes {@code id} current, remembering what was — a key held down for a spring-loaded tool. */
    public void useTemporarily(String id) {
        String was = currentId;
        use(id);
        previousId = was;
    }

    /** Goes back to what was current before the last {@link #useTemporarily}. */
    public void restore() {
        use(previousId);
    }

    @Nullable
    private Tool build(String id) {
        Tool existing = built.get(id);
        if (existing != null) return existing;
        for (var kind : ctx.tools()) {
            if (!kind.id().equals(id) || kind.factory() == null) continue;
            Tool tool = kind.factory().apply(ctx);
            built.put(id, tool);
            return tool;
        }
        return null;
    }

    /** Joins the window's input stack. Called when the surface connects. */
    public void attach(@Nullable UIDocument window) {
        if (pushedOn == window) return;
        detach();
        if (window == null) return;
        window.input().pushMode(mode);
        pushedOn = window;
    }

    /** Leaves it. Called when the surface is disposed, and before joining another window. */
    public void detach() {
        if (pushedOn == null) return;
        pushedOn.input().popMode(mode);
        pushedOn = null;
    }
}
