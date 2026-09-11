package com.crystalgui.widget.surface.mode;

import javax.annotation.Nullable;

import com.crystalgui.ui.dom.UIElement;

/**
 * One way of working on a surface — Select, Hand, Zoom, Free Transform.
 *
 * <p>Declared with {@code ToolKind}, which derives the command, the accelerator and the tool-strip
 * entry from the declaration; the mode stack asks the current tool before anything under it, so a tool
 * never competes with the widgets it is drawn over.</p>
 *
 * <pre>{@code
 * ctx.registerTool(ToolKind.of("mymod:hand", "Hand")
 *         .icon("mymod:icons/hand")
 *         .command("mymod.tool.hand", "H")
 *         .tool(HandTool::new));
 *
 * final class HandTool implements Tool {
 *     private final SurfaceContext ctx;
 *     HandTool(SurfaceContext ctx) { this.ctx = ctx; }
 *
 *     public boolean pointerDown(float wx, float wy, int button, int modifiers) {
 *         return ctx.surface().panFrom(wx, wy);
 *     }
 * }
 * }</pre>
 *
 * <p>Coordinates are <b>raw surface pixels</b>, exactly as {@code InputMode} delivers them — call
 * {@code ctx.surface().toWorld(x, y)} for world units. Raw is what the picker wants, and one convention
 * beats converting twice in opposite directions.</p>
 *
 * <p>Return {@code true} only when the tool acted on the event: anything left unclaimed reaches the tree
 * below, which is what keeps an editor inside an item alive while a tool is current.</p>
 */
public interface Tool {

    /** Runs when this tool becomes the current one. */
    default void activated() {
    }

    /** Runs when it stops being current, however that happened — including the surface closing. */
    default void deactivated() {
    }

    /**
     * What this tool puts in the editor's context toolbar while it is current, or null for nothing — its
     * page of Photoshop's options bar.
     *
     * <pre>{@code
     * public UIElement options() {
     *     if (options == null) options = new BrushOptions(ctx);
     *     return options;
     * }
     * }</pre>
     *
     * <p>Return the same element every time: the bar keeps a page between uses, so its fields keep what
     * they hold. Shown only where the host has called {@code ctx.modes().showOptionsIn(bar)}.</p>
     */
    @Nullable
    default UIElement options() {
        return null;
    }

    /**
     * Whether every press on the surface is this tool's, wherever it lands.
     *
     * <p>Ordinarily a press is offered only where the consumer's policy says the SURFACE owns it — a
     * marquee belongs to the page, not to the empty plane around it. A modal tool is the exception, and
     * the exception is the whole of what modal means: a Free Transform box whose handles have been
     * rotated off the edge of the page still has to take a press on them, and the press cannot be
     * arbitrated by what happens to be underneath because the answer is "nothing".</p>
     */
    default boolean claimsEveryPress() {
        return false;
    }

    /** @return whether this tool consumed the press. */
    default boolean pointerDown(float rawX, float rawY, int button, int modifiers) {
        return false;
    }

    /** @return whether this tool consumed the movement. */
    default boolean pointerMoved(float rawX, float rawY, int modifiers) {
        return false;
    }

    /** @return whether this tool consumed the release. */
    default boolean pointerUp(float rawX, float rawY, int button, int modifiers) {
        return false;
    }

    /** @return whether this tool consumed the key. */
    default boolean keyPressed(int key, int modifiers, boolean repeat) {
        return false;
    }
}
