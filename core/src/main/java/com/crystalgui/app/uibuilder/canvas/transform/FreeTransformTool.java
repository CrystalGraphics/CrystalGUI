package com.crystalgui.app.uibuilder.canvas.transform;

import org.joml.Vector2f;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;

import com.crystalgui.app.uibuilder.canvas.TreeSelectTool;
import com.crystalgui.app.uibuilder.canvas.transform.TransformGesture.Grip;
import com.crystalgui.app.uibuilder.canvas.transform.TransformGesture.Kind;
import com.crystalgui.widget.surface.SurfaceContext;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.surface.mode.Tool;
import com.crystalgui.widget.surface.snap.SnapSuspend;

/**
 * Free Transform — Photoshop's Ctrl+T, as a modal tool over one selection.
 *
 * <p>Modal in the strict sense: <b>every</b> press is consumed while it is up, so nothing else can be
 * selected and no widget underneath is pressed. Enter commits, Escape cancels, and either one hands the
 * surface back to Select. Its numbers are a {@link TransformOptionsBar}, shown in the editor's context
 * toolbar for as long as the tool is current.</p>
 *
 * <pre>{@code
 * ctx.registerTool(ToolKind.of(FreeTransformTool.ID, "Free Transform")
 *         .tool(surface -> new FreeTransformTool(surface, box, new TransformOptionsBar(box))));
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
 *
 * <p>A move and a handle snap to what is around the element, as the out-of-flow move and the resize
 * handles do, and to the element's own layout box; Ctrl suspends it. @see TransformSnap</p>
 */
public final class FreeTransformTool implements Tool {

    public static final String ID = "crystalgui:uibuilder.freeTransform";

    private final SurfaceContext ctx;

    private final TransformBox box;

    private final TransformOptionsBar options;

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

    public FreeTransformTool(SurfaceContext ctx, TransformBox box, TransformOptionsBar options) {
        this.ctx = ctx;
        this.box = box;
        this.options = options;
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

    /** The box's numbers, in place of the editor's toolbar while the box is up. */
    @Override
    public UIElement options() {
        return options;
    }

    /**
     * <b>Yes.</b> The box is modal over one selection, and its handles are wherever the gesture has put
     * them — often over empty plane once the element is rotated or scaled up.
     */
    @Override
    public boolean claimsEveryPress() {
        return true;
    }

    /**
     * <b>Commits.</b>
     *
     * <p>Photoshop's rule, and the safe one either way: leaving by any route other than Escape keeps the
     * work. A tool that cancelled here would throw the gesture away when the user clicked another tool,
     * and a live preview left behind with no box to reach it is worse than both. A number still being
     * typed into the box's fields is part of the work, so it lands first.</p>
     */
    @Override
    public void deactivated() {
        landTypedNumber();
        box.commit();
        ctx.cursors().clear();
    }

    /** Lands a number typed into the box's own fields and not yet entered. */
    private void landTypedNumber() {
        TextField typing = fieldBeingTypedInto();
        if (typing != null && UINode.isShadowIncludingInclusiveAncestor(options, typing)) typing.commit();
    }

    /**
     * Begins a gesture, through {@code Drag} rather than by tracking the release itself.
     *
     * <p><b>Pointer capture is the point.</b> {@code SurfaceMode} only delivers a release while the
     * pointer is inside the surface, so a drag finished off the canvas never reported up: the gesture
     * stayed armed with the button long since released, kept transforming on every move, and — because
     * the cursor is not re-decided mid-drag — took every other cursor down with it until the tool was
     * left. Capture ends the drag wherever it ends.</p>
     */
    @Override
    public boolean pointerDown(float rawX, float rawY, int button, int modifiers) {
        if (button != CgMouseCodes.LEFT_BUTTON) return true;
        Vector2f at = ctx.surface().toViewportPoint(rawX, rawY);
        Grip grip = box.grip(at.x, at.y, CgModifiers.hasCtrl(modifiers));
        if (grip.is(Kind.NONE)) return true;
        box.press(grip);
        box.setDragging(true);

        final float fromX = at.x;
        final float fromY = at.y;
        Drag.start(box, rawX, rawY, new Drag.Listener() {
            @Override
            public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                // The deltas arrive in the SOURCE element's space, and the source is the overlay the box
                // measures everything else in -- so they need no conversion.
                int live = modifiersNow();
                box.dragTo(fromX + dx, fromY + dy, dx, dy, CgModifiers.hasShift(live),
                        CgModifiers.hasAlt(live), !SnapSuspend.isSuspended(live));
            }

            @Override
            public void onDragEnd(float mx, float my) {
                finish();
            }

            @Override
            public void onDragCancel() {
                finish();
            }

            private void finish() {
                box.setDragging(false);
                box.release();
            }
        });
        return true;
    }

    /**
     * A drag callback carries no modifiers of its own, so Shift and Alt are asked for live.
     *
     * <p>The engine's own Select tool and the out-of-flow move both ask the same way.</p>
     */
    private static int modifiersNow() {
        var input = CgPlatform.input();
        return input == null ? 0 : input.getCurrentModifiers();
    }

    @Override
    public boolean pointerMoved(float rawX, float rawY, int modifiers) {
        Vector2f at = ctx.surface().toViewportPoint(rawX, rawY);
        box.hoverAt(at.x, at.y);
        // THE CURSOR IS DECIDED PER FRAME, not here: Ctrl turns a scale handle into a skew handle, and a
        // modifier arriving while the hand is still would otherwise show the wrong shape until nudged.
        return box.isActive();
    }

    @Override
    public boolean keyPressed(int key, int modifiers, boolean repeat) {
        if (!box.isActive()) return false;
        boolean enter = key == CgKeyCodes.KEY_RETURN || key == CgKeyCodes.KEY_NUMPADENTER;
        // A MODE IS ASKED BEFORE THE TREE, so swallowing everything left no text field in the application
        // typeable while the box was up. A field with focus gets its keys back; the modal claim is over the
        // CANVAS, not over the keyboard.
        TextField typing = fieldBeingTypedInto();
        if (typing != null) {
            if (!UINode.isShadowIncludingInclusiveAncestor(options, typing)) return false;
            // EXCEPT THAT ENTER AND ESCAPE STILL END THE TRANSFORM from its own numbers, as they do from
            // Blender's numeric input. Enter lands what was typed first, so the number showing is the one
            // committed.
            if (enter) {
                typing.commit();
                box.commit();
                backToSelect();
                return true;
            }
            if (key == CgKeyCodes.KEY_ESCAPE) {
                box.cancel();
                backToSelect();
                return true;
            }
            return false;
        }
        if (key == CgKeyCodes.KEY_ESCAPE) {
            box.cancel();
            backToSelect();
            return true;
        }
        // BLENDER'S TRICK: type a number and it lands in the field for whatever was last grabbed, so a
        // gesture can be finished exactly without the hand leaving the canvas. The field takes focus with
        // its text selected and THIS key goes on to it, so the digit replaces what was there and the rest
        // is ordinary typing -- the same whether the platform sends the character with the key or after it.
        if (isNumberKey(key) && !CgModifiers.hasCtrl(modifiers)) {
            TextField field = options.fieldFor(box.lastGrip().kind()).field();
            UIDocument window = box.document();
            if (window != null) {
                window.focus().requestFocus(field);
                field.selectAll();
                return false;
            }
        }

        // THE GESTURE'S OWN HISTORY, not the document's. Nothing has been written yet -- the whole
        // transform is one edit made on commit -- so a Ctrl+Z falling through would undo whatever was
        // done BEFORE the box opened, which is never what the hand meant. Still swallowed once the
        // gesture is back at its start, so it cannot reach past the modal state either.
        if (key == CgKeyCodes.KEY_Z && CgModifiers.hasCtrl(modifiers)) {
            if (CgModifiers.hasShift(modifiers)) box.redoStep();
            else box.undoStep();
            return true;
        }
        if (key == CgKeyCodes.KEY_Y && CgModifiers.hasCtrl(modifiers)) {
            box.redoStep();
            return true;
        }
        if (enter) {
            box.commit();
            backToSelect();
            return true;
        }
        // EVERYTHING ELSE IS SWALLOWED. A modal gesture that let an arrow key through would nudge the
        // selection out from under a live preview, and the two writes are on different channels.
        return true;
    }

    /** Whether a key starts a number. */
    private static boolean isNumberKey(int key) {
        return key >= CgKeyCodes.KEY_1 && key <= CgKeyCodes.KEY_9 || key == CgKeyCodes.KEY_0
                || key == CgKeyCodes.KEY_MINUS || key == CgKeyCodes.KEY_PERIOD;
    }

    /** The text field holding focus, wherever it is, or null. */
    @Nullable
    private TextField fieldBeingTypedInto() {
        UIDocument window = box.document();
        if (window == null) return null;
        for (UIElement at = window.focus().focused(); at != null; at = at.composedParent()) {
            if (at instanceof TextField field) return field;
        }
        return null;
    }

    private void backToSelect() {
        ctx.modes().use(TreeSelectTool.ID);
    }
}
