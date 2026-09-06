package com.crystalgui.widget.surface.mode;

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
 * <p>Coordinates are the plane's — world units, already through the pan and the zoom. Return
 * {@code true} only when the tool acted on the event: anything left unclaimed reaches the tree below,
 * which is what keeps an editor inside an item alive while a tool is current.</p>
 */
public interface Tool {

    /** Runs when this tool becomes the current one. */
    default void activated() {
    }

    /** Runs when it stops being current, however that happened — including the surface closing. */
    default void deactivated() {
    }

    /** @return whether this tool consumed the press. */
    default boolean pointerDown(float worldX, float worldY, int button, int modifiers) {
        return false;
    }

    /** @return whether this tool consumed the movement. */
    default boolean pointerMoved(float worldX, float worldY, int modifiers) {
        return false;
    }

    /** @return whether this tool consumed the release. */
    default boolean pointerUp(float worldX, float worldY, int button, int modifiers) {
        return false;
    }

    /** @return whether this tool consumed the key. */
    default boolean keyPressed(int key, int modifiers, boolean repeat) {
        return false;
    }
}
