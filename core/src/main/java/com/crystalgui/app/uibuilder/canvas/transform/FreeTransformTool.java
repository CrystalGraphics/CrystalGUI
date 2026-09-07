package com.crystalgui.app.uibuilder.canvas.transform;

import org.joml.Vector2f;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;

import com.crystalgui.app.uibuilder.canvas.TreeSelectTool;
import com.crystalgui.app.uibuilder.canvas.transform.TransformGesture.Grip;
import com.crystalgui.app.uibuilder.canvas.transform.TransformGesture.Kind;
import com.crystalgui.widget.surface.SurfaceContext;
import com.crystalgui.widget.surface.mode.Tool;

/**
 * Free Transform — Photoshop's Ctrl+T, as a modal tool over one selection.
 *
 * <p>Modal in the strict sense: <b>every</b> press is consumed while it is up, so nothing else can be
 * selected and no widget underneath is pressed. Enter commits, Escape cancels, and either one hands the
 * surface back to Select.</p>
 *
 * <pre>{@code
 * ctx.registerTool(ToolKind.of(FreeTransformTool.ID, "Free Transform")
 *         .tool(surface -> new FreeTransformTool(surface, box)));
 * }</pre>
 *
 * <h3>What each drag means</h3>
 *
 * <ul>
 *   <li>a handle <b>scales</b>; Shift holds the ratio, Alt works about the pivot rather than the
 *       opposite edge</li>
 *   <li>just outside a corner <b>rotates</b>; Shift snaps to 15 degrees</li>
 *   <li>Ctrl on an EDGE handle <b>skews</b>. Ctrl on a corner is Photoshop's Distort and is refused —
 *       a free corner is not an affine map — so it stays a scale rather than doing nothing</li>
 *   <li>the crosshair <b>places the pivot</b>, snapping to the nine anchor points unless Alt is held</li>
 *   <li>anywhere else inside the box <b>moves</b> it; Shift keeps the move on one axis</li>
 * </ul>
 */
public final class FreeTransformTool implements Tool {

    public static final String ID = "crystalgui:uibuilder.freeTransform";

    private final SurfaceContext ctx;

    private final TransformBox box;

    private boolean dragging;

    private float pressX;

    private float pressY;

    /**
     * Whether the transform box is the current tool on this surface.
     *
     * <p>Asked every frame by the selection outline and the resize handles, which both stand down while
     * it is up: three sets of chrome on one element, two of them tracking the untransformed box, is what
     * "the handles are in the wrong place" looks like. A standing rule rather than a flag toggled on
     * entry, because a one-shot instruction loses to anything that re-decides per frame — which is
     * exactly how the handles came back during a preview.</p>
     */
    public static boolean isCurrent(SurfaceContext ctx) {
        return ID.equals(ctx.modes().currentId());
    }

    public FreeTransformTool(SurfaceContext ctx, TransformBox box) {
        this.ctx = ctx;
        this.box = box;
    }

    /**
     * Opens the box on whatever is selected.
     *
     * <p>The command checks first that there is exactly one node with a box, so this is not where a bad
     * selection is caught: switching tools from inside {@code activated} would re-enter the mode stack
     * mid-change.</p>
     */
    @Override
    public void activated() {
        box.begin(ctx.selection().size() == 1 ? ctx.selection().items().get(0) : null);
    }

    /**
     * <b>Commits.</b>
     *
     * <p>Photoshop's rule, and the safe one either way: leaving by any route other than Escape keeps the
     * work. A tool that cancelled here would throw the gesture away when the user clicked another tool,
     * and a live preview left behind with no box to reach it is worse than both.</p>
     */
    @Override
    public void deactivated() {
        box.commit();
    }

    @Override
    public boolean pointerDown(float rawX, float rawY, int button, int modifiers) {
        if (button != CgMouseCodes.LEFT_BUTTON) return true;
        Vector2f at = ctx.surface().toViewportPoint(rawX, rawY);
        Grip grip = box.grip(at.x, at.y, CgModifiers.hasCtrl(modifiers));
        if (grip.is(Kind.NONE)) return true;
        box.press(grip);
        dragging = true;
        pressX = at.x;
        pressY = at.y;
        return true;
    }

    @Override
    public boolean pointerMoved(float rawX, float rawY, int modifiers) {
        if (!dragging) return box.isActive();
        Vector2f at = ctx.surface().toViewportPoint(rawX, rawY);
        box.dragTo(at.x, at.y, at.x - pressX, at.y - pressY,
                CgModifiers.hasShift(modifiers), CgModifiers.hasAlt(modifiers));
        return true;
    }

    @Override
    public boolean pointerUp(float rawX, float rawY, int button, int modifiers) {
        if (!dragging) return box.isActive();
        dragging = false;
        box.release();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int modifiers, boolean repeat) {
        if (!box.isActive()) return false;
        if (key == CgKeyCodes.KEY_ESCAPE) {
            box.cancel();
            backToSelect();
            return true;
        }
        if (key == CgKeyCodes.KEY_RETURN || key == CgKeyCodes.KEY_NUMPADENTER) {
            box.commit();
            backToSelect();
            return true;
        }
        // EVERYTHING ELSE IS SWALLOWED. A modal gesture that let an arrow key through would nudge the
        // selection out from under a live preview, and the two writes are on different channels.
        return true;
    }

    private void backToSelect() {
        ctx.modes().use(TreeSelectTool.ID);
    }
}
