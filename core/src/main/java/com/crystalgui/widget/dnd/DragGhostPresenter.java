package com.crystalgui.widget.dnd;

import javax.annotation.Nullable;

import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.DragPresenter;
import com.crystalgui.ui.service.DragPreview;
import com.crystalgui.ui.service.Input;

import org.joml.Vector4f;

/**
 * Draws a drag's preview in its own document: one {@link DragGhost} per document, parked in its root, promoted to the top
 * layer while a drag shows it and moved by a transform, so it costs no layout.
 *
 * <p>Installed for every document by {@link #install}; nothing calls it directly. While the document is a window's
 * client the shell draws the preview instead (the ghost here would be cut off at the document's edge).</p>
 */
public final class DragGhostPresenter implements DragPresenter {

    private final UIDocument document;
    private final DragGhost ghost = new DragGhost();
    private boolean placementPending;
    private float surfaceX, surfaceY;
    private boolean showing;

    private DragGhostPresenter(UIDocument document) {
        this.document = document;
        // IN THE TREE BEFORE A DRAG CAN PROMOTE IT: promotion re-hosts a box, and a node with no document has none.
        document.append(ghost);
    }

    /** Makes every document draw its drags' previews with a {@link DragGhost}. Once, at startup. */
    public static void install() {
        Input.presenterFactory = DragGhostPresenter::new;
    }

    @Nullable
    @Override
    public UIElement element() {
        return ghost;
    }

    @Override
    public void show(DragPreview preview, float x, float y) {
        surfaceX = x;
        surfaceY = y;
        ghost.apply(preview);
        if (!document.isPromoted(ghost)) {
            ghost.setDisplayed(true);
            // UNHITTABLE for the drag's life: it is under the pointer by construction, so a hittable one answers every drop query itself.
            ghost.setHitTest(false);
            document.promote(ghost);
        } else if (!ghost.isDisplayed()) {
            ghost.setDisplayed(true);
        }
        showing = true;
        if (!place(preview)) placeAfterLayout(preview);
    }

    @Override
    public void hide() {
        showing = false;
        Box box = ghost.box();
        if (box != null) box.setTransform(null);
        document.demote(ghost);
        // BACK TO HIDDEN: it is a permanent node, and one left displayed is a stray label in the root.
        ghost.setDisplayed(false);
    }

    /** Whether it was placed: false while it has no box, which a ghost lacks until the frame after it is shown. */
    private boolean place(DragPreview preview) {
        Box box = ghost.box();
        Box top = box == null ? null : box.host();
        if (top == null) return false;
        Vector4f p = new Vector4f(surfaceX, surfaceY, 0f, 1f).mul(top.worldToLocal());
        float scale = Math.max(0.01f, document.boxes().uiScale());
        box.setTransform(Transform.translate(p.x - preview.grabX() / scale, p.y - preview.grabY() / scale));
        return true;
    }

    /** Until it lands, at wherever the pointer is by then: one hook, ending on the first frame it can do the job. */
    private void placeAfterLayout(DragPreview preview) {
        if (placementPending) return;
        placementPending = true;
        document.animation().afterLayout(ghost, delta -> {
            boolean waiting = showing && !place(preview);
            placementPending = waiting;
            return waiting;
        });
    }
}
