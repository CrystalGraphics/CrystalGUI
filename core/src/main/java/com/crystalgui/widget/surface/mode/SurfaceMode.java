package com.crystalgui.widget.surface.mode;

import com.crystalgraphics.platform.input.CgMouseCodes;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.InputMode;
import com.crystalgui.widget.surface.SurfaceContext;
import com.crystalgui.widget.surface.SurfacePolicy;

/**
 * The engine's one {@link InputMode}: it asks the consumer's policy who owns a press, and gives the ones
 * that are the surface's to the current tool.
 *
 * <p>Pushed by {@link Modes} while the surface is in a window. A mode is asked before anything on the
 * tree, which is what lets a tool own a press that lands on a widget — and declining is how a widget
 * that must stay live keeps it. A graph answers <em>tree</em> for a port's value field; a UI builder
 * answers <em>surface</em> for everything inside the artboard, because the thing being designed must not
 * react to being designed.</p>
 *
 * <p>It claims the left button only. Middle-drag panning and the wheel are the canvas's, and a mode that
 * swallowed them would take them from every surface at once.</p>
 */
final class SurfaceMode implements InputMode {

    /** Left only: middle-drag panning and the wheel are the canvas's. */
    private static final int LEFT = CgMouseCodes.LEFT_BUTTON;

    private final SurfaceContext ctx;
    private final Modes modes;

    SurfaceMode(SurfaceContext ctx, Modes modes) {
        this.ctx = ctx;
        this.modes = modes;
    }

    @Override
    public String name() {
        return "surface";
    }

    @Override
    public boolean pointerButton(int button, boolean pressed, float x, float y) {
        Tool tool = modes.current();
        if (tool == null || button != LEFT) return false;
        if (!ctx.surface().contains(x, y)) return false;
        int modifiers = SelectTool.modifiersNow();
        if (!pressed) return tool.pointerUp(x, y, button, modifiers);
        if (!ownedHere(x, y)) return false;
        return tool.pointerDown(x, y, button, modifiers);
    }

    @Override
    public boolean pointerMoved(float x, float y) {
        Tool tool = modes.current();
        if (tool == null || !ctx.surface().contains(x, y)) return false;
        return tool.pointerMoved(x, y, SelectTool.modifiersNow());
    }

    @Override
    public boolean keyPressed(int key, int modifiers, boolean repeat) {
        Tool tool = modes.current();
        return tool != null && tool.keyPressed(key, modifiers, repeat);
    }

    /** Whether the press belongs to the surface rather than to the widget under it. */
    private boolean ownedHere(float x, float y) {
        UIElement hit = ctx.picking().elementAt(x, y);
        if (hit == null) return false;
        return ctx.policy(SurfacePolicy.class).ownerOf(hit) == SurfacePolicy.PressOwner.SURFACE;
    }
}
