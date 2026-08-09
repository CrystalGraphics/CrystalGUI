package com.crystalgui.ui.elements.workbench;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.fs.CgPath;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.DragGhost;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.UIInputHandler;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.input.UIDragController;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Dragging files within the tree — VS Code's {@code FileDragAndDrop}.
 *
 * <h3>Why this is its own part</h3>
 *
 * <p>It is the one interaction spanning every other: it reads the <b>selection</b>, walks the <b>rows</b>,
 * resolves a <b>drop target</b> against the model, and puts a <b>ghost</b> on the window. Inline it was
 * the single largest thing in the widget and the reason the widget touched all four at once. VS Code
 * separates it for the same reason and gives it the same name.</p>
 *
 * <p><b>Reported, never performed.</b> A drop emits {@link ProjectFileTree#onFilesDropped}; the tree does
 * not own the file service — {@link Workbench} does — and one that reached for a service could serve a
 * single host.</p>
 *
 * @see ProjectFileTree for why a part sits beside the widget rather than behind an interface
 */
final class ExplorerDragAndDrop {

    private final ProjectFileTree tree;

    ExplorerDragAndDrop(ProjectFileTree tree) {
        this.tree = tree;
    }

    /**
     * The capsule that follows the cursor while files are being moved.
     *
     * <p>Was thirty-odd lines here — park it, write {@code position: absolute} and {@code display: none}
     * at IMPORTANT from Java, keep a label field, re-register per drag — with a comment explaining each.
     * {@code StripeView} then needed the same thirty, verbatim, which is the point at which a body stops
     * being a call site and becomes a duplicated implementation. All three rules and all three
     * explanations now live in {@link DragGhost}.</p>
     */
    private final DragGhost ghost = new DragGhost();

    /** The ghost has to be IN THE TREE before a drag can promote it. @see DragGhost */
    void parkGhostIn(UIElement host) {
        ghost.parkIn(host);
    }

    private record DragPayload(List<CgPath> paths) {
    }

    /**
     * Makes a row draggable and the tree a drop target.
     *
     * <p><b>Dropping on a FILE targets its parent folder.</b> VS Code's rule, and the one that makes a
     * tree forgiving: rows are small, a folder's children are directly beneath it, and "into the folder
     * this thing is in" is almost always what was meant. Refusing the drop instead means aiming at a
     * 12-pixel row.</p>
     *
     * <p>Rejection is the default — a target accepts by calling {@code preventDefault()} on {@code Over},
     * re-read every frame and never latched. HTML5 drag-and-drop's one good idea, which this engine
     * already keeps.</p>
     */
    void installRowDrag(UIElement row) {
        row.events.getGroup(MouseEvent.Down.class).attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            // NEVER FROM THE KEYBOARD. Space and Enter on a focused element are delivered as a synthesized
            // MouseEvent.Down -- that is how Button and Checkbox get keyboard activation with no keyboard
            // code -- and the synthesized press carries the PHYSICAL CURSOR POSITION. A drag is the one
            // press that means "the pointer went down here" rather than "activate me", so confirming a New
            // File prompt with Enter started a drag anchored at wherever the mouse happened to be resting.
            //
            // And it could never end: pointer capture is released by a real button-up, which is not coming,
            // so the drag stayed armed. That is the second half of the same report -- with a live drag,
            // ListView's release handler correctly declines to collapse the selection, so every later click
            // added a row instead of replacing one, and the panel looked like it had lost multi-select
            // semantics entirely.
            //
            // KEYBOARD_DETAIL is the opt-out the input handler already provides for exactly this, and
            // GraphView is the widget that found it: Enter synthesized a press and started a marquee.
            if (event.getDetail() == UIInputHandler.KEYBOARD_DETAIL) return;
            UIWindow window = tree.treeView().getAttachedWindow();
            CgPath item = tree.itemForRow(row);
            if (window == null || item == null || item.isProjectRoot()) return;

            // The whole SELECTION when the pressed row is part of it, otherwise just this row -- the
            // same rule the graph uses for node drags, and for the same reason: "drag the five I
            // selected" is the common gesture, and a press that collapsed the selection breaks it.
            List<CgPath> dragged = tree.selectedPaths().contains(item) ? tree.selectedPaths() : List.of(item);

            // NO ICON for a multi-selection: "3 items" has no one glyph, and picking the first file's
            // would claim the drag is about that file.
            ghost.follow(window, dragged.size() == 1
                    ? FileIconTheme.getDefault().iconFor(dragged.get(0).name(), false, false)
                    : null,
                    dragged.size() == 1 ? dragged.get(0).name() : dragged.size() + " items");

            window.getInputHandler().getDragController().startDrag(row,
                    event.getPosition().x(), event.getPosition().y(), new DragPayload(dragged),
                    new UIDragController.DragListener() {
                        @Override
                        public void onDragUpdate(float mx, float my, float sx, float sy,
                                                 float dx, float dy) {
                            // Nothing per frame: where the drop would land is decided by DragEvent.Over
                            // on the TREE, which is dispatched against what is geometrically under the
                            // pointer. This listener is pinned to the source by pointer capture and can
                            // never tell.
                        }
                    });
        }, false, false);
    }

    void installDropTarget() {
        tree.treeView().events.getGroup(DragEvent.Over.class).attachListener((element, event) -> {
            if (!(event.getPayload() instanceof DragPayload)) return;
            // OUTLINED WHEREVER THE POINTER IS, not only where a drop would be accepted.
            //
            // IntelliJ marks the row under the cursor even when dropping there does nothing, and that is
            // the more useful signal: an outline that appears only over valid targets leaves you unable to
            // tell "this cannot take it" from "the drag is not tracking me at all". The refusal is carried
            // by the cursor, which is what a cursor is for.
            markDropTarget(tree.rowElementFor(event.getTarget()));
            if (dropTargetFor(event.getTarget()) != null) {
                // ACCEPTING is preventDefault. Re-read every frame, so a drag that wanders over
                // something invalid stops being accepted without anything having to un-latch it.
                event.preventDefault();
            }
        }, false, true);

        // The pointer left the tree entirely -- over the editor, or off the window. Over stops firing, so
        // without this the last row keeps its outline for the rest of the drag.
        tree.treeView().events.getGroup(DragEvent.Leave.class).attachListener(
                (element, event) -> markDropTarget(null), false, true);

        tree.treeView().events.getGroup(DragEvent.Drop.class).attachListener((element, event) -> {
            markDropTarget(null);
            if (!(event.getPayload() instanceof DragPayload payload)) return;
            CgPath destination = dropTargetFor(event.getTarget());
            if (destination == null) return;
            // The modifier means COPY, matching every file manager. Read at DROP time rather than at
            // press time, because the decision is made while dragging -- you pick the folder first and
            // then hold the key.
            boolean copy = (CgPlatform.input().getCurrentModifiers() & CgModifiers.CTRL) != 0;
            tree.onFilesDropped.emit(payload.paths(), new ProjectFileTree.DropRequest(destination, copy));
        }, false, true);
    }

    /** On the row the pointer is over during a drag. */

    /** The row currently outlined, so the class can be taken off again without searching for it. */
    @Nullable
    private UIElement outlinedRow;

    /**
     * Moves the drop outline to {@code row}, or clears it for {@code null}.
     *
     * <p>Held as a reference rather than re-derived, because the row it has to come <em>off</em> may no
     * longer be under the pointer, may have scrolled out of the window, and — since rows are pooled — may
     * by then be showing a different file entirely. A pooled row that kept this class would wear an outline
     * around whatever it was next bound to.</p>
     *
     * <p>Cleared from {@code Drop} and from {@code Leave}, and those two are enough: a cancelled drag
     * leaves the pointer's boundary and so raises {@code Leave} on the way out. A third, defensive
     * clear driven off "is a drag still live" was written and removed again -- no path could reach
     * it, and an untestable backstop is a claim of safety nothing checks.</p>
     */
    private void markDropTarget(@Nullable UIElement row) {
        if (outlinedRow == row) return;
        if (outlinedRow != null) outlinedRow.removeClass(ProjectFileTree.DROP_TARGET_CLASS);
        outlinedRow = row;
        if (row != null) row.addClass(ProjectFileTree.DROP_TARGET_CLASS);
    }

    /** The folder a drop on {@code hit} lands in, or null if it is not over a row. */
    @Nullable
    private CgPath dropTargetFor(@Nullable UIElement hit) {
        UIElement row = tree.rowElementFor(hit);
        if (row == null) return null;
        CgPath item = tree.itemForRow(row);
        if (item == null) return null;
        return tree.source().isDirectory(item) ? item : item.parent();
    }

}
