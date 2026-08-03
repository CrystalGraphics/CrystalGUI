package com.crystalgui.graph.shader;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.shadergraph.CgMainPreviewRenderer;
import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgraphics.shadergraph.CgPreviewMesh;
import com.crystalgraphics.shadergraph.CgShaderGraph;
import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.AnchoredPlacement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.MenuItem;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.UIDragController;

import javax.annotation.Nullable;

/**
 * Unity's <b>Main Preview</b>: the finished shader on a mesh you can turn, with a right-click menu of
 * shapes.
 *
 * <h3>Where it lives, and why not in {@code ui.elements.graph}</h3>
 * <p>It holds a {@link CgMainPreviewRenderer} in a <b>field</b>, and a field descriptor resolves at class
 * load rather than on first call — so putting it beside the graph widgets would drag CrystalGraphics core
 * into a package a dedicated server loads. Same reason, and the same documented exception, as
 * {@link ShaderNodePreview}.</p>
 *
 * <h3>Everything it remembers is VIEW state</h3>
 * <p>The chosen mesh, the orbit and the zoom never enter the document and never reach an
 * {@code UndoStack}. Which shape you are looking at does not change what the shader does, so recording it
 * would make Ctrl+Z rotate a sphere instead of undoing an edit — the boundary this project already draws
 * for scroll and selection.</p>
 *
 * <h3>It is unlit, deliberately</h3>
 * <p>See {@link CgMainPreviewRenderer}. There is no lighting model to preview with, and faking one would
 * have someone tuning a shader against shading the pipeline cannot produce.</p>
 */
public class MainPreviewPanel extends UIElement implements UIFrameTicker {

    public static final String PANEL_CLASS = "__main-preview__";
    /** The header strip. A CONTAINER, matching the configurator's group heading exactly — see the
     * stylesheet note on why the title cannot be the strip itself. */
    public static final String HEAD_CLASS = "__head__";
    public static final String TITLE_CLASS = "__title__";
    public static final String SURFACE_CLASS = "__surface__";

    /** On the panel while an orbit drag is live, so the cursor holds for the whole gesture. */
    public static final String ORBITING_CLASS = "__orbiting__";

    /** Radians of orbit per physical pixel dragged. A full turn in roughly a panel-width of travel. */
    private static final float ORBIT_PER_PIXEL = 0.01f;

    private static final float MIN_ZOOM = 0.25f;
    private static final float MAX_ZOOM = 4f;

    private final GraphDocument document;
    private final CgShaderNodeRegistry shaderNodes;
    private final CgMasterNode master;
    private final CgMainPreviewRenderer renderer;

    private final UIElement head = new UIElement();
    private final UIText title = new UIText("Main Preview");
    private final Surface surface = new Surface();

    private CgPreviewMesh mesh = CgPreviewMesh.SPHERE;
    private float yaw;
    private float pitch;
    private float zoom = 1f;

    /** Orbit at the moment the drag began — accumulating onto the live value compounds. */
    private float dragYaw;
    private float dragPitch;

    /** Panel origin at the moment a move began, in the canvas viewport's own space. */
    private float dragLeft;
    private float dragTop;

    /** True once the panel has been dragged, so its position is its own rather than the stylesheet's. */
    private boolean placed;

    /** The one entry that is deliberately inert. @see #openMeshMenu */
    public static final String CUSTOM_MESH_LABEL = "Custom Mesh";

    /** Puts the orbit and zoom back to where the panel opened. @see #resetView */
    public static final String RESET_VIEW_LABEL = "Reset Camera";

    @Nullable
    private Menu meshMenu;

    public MainPreviewPanel(GraphDocument document, CgShaderNodeRegistry shaderNodes,
                            CgMasterNode master) {
        this(document, shaderNodes, master, new CgMainPreviewRenderer());
    }

    public MainPreviewPanel(GraphDocument document, CgShaderNodeRegistry shaderNodes,
                            CgMasterNode master, CgMainPreviewRenderer renderer) {
        this.document = document;
        this.shaderNodes = shaderNodes;
        this.master = master;
        this.renderer = renderer;

        addClass(PANEL_CLASS);
        markAsInternal();

        // Head is a row CONTAINER and the title is a child of it, which is the configurator's own
        // structure and the reason its headings sit centred. A UIText draws its glyphs from its own box
        // top, so making the text BE the strip leaves nothing that can centre it — `align-items` needs a
        // flex item to act on, and there was none. That is what clipped the label's ascenders.
        head.addClass(HEAD_CLASS);
        title.addClass(TITLE_CLASS);
        // The label is scenery; the strip around it is the move handle, so the press must reach the head.
        title.setHitTest(false);
        head.addChild(title);

        surface.addClass(SURFACE_CLASS);
        addInternalChild(head);
        addInternalChild(surface);

        installGestures();
    }

    /**
     * Starts redrawing, and reports whether it managed to.
     *
     * <p><b>Safe to call every frame.</b> Ticker registration is {@code HashSet}-backed and therefore
     * idempotent, and a caller that gives up after one attempt gets a panel that never draws at all if
     * the panel was not yet in a window — a page built into an unselected tab, for instance. Silently
     * doing nothing and returning {@code this} was the earlier shape, and it hid exactly that.</p>
     *
     * @return true once a window was found and the ticker is registered
     */
    public boolean attach() {
        UIWindow window = getAttachedWindow();
        if (window == null) return false;
        window.registerTicker(this);
        return true;
    }

    // ── View state ──────────────────────────────────────────────────────────

    public CgPreviewMesh mesh() {
        return mesh;
    }

    public MainPreviewPanel setMesh(CgPreviewMesh next) {
        this.mesh = next == null ? CgPreviewMesh.SPHERE : next;
        return this;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public float zoom() {
        return zoom;
    }

    /** Puts the camera back where it started. What a double-click or a menu entry would call. */
    public MainPreviewPanel resetView() {
        yaw = 0f;
        pitch = 0f;
        zoom = 1f;
        return this;
    }

    // ── Gestures ────────────────────────────────────────────────────────────

    private void installGestures() {
        installMoveGesture();
        surface.onMouseDown.attachListener((element, event) -> {
            float rawX = event.getPosition().x(), rawY = event.getPosition().y();
            if (!surface.containsScreenPoint(rawX, rawY)) return;

            if (event.getButtonId() == com.crystalgraphics.platform.input.CgMouseCodes.RIGHT_BUTTON) {
                openMeshMenu(rawX, rawY);
                event.stopPropagation();
                return;
            }

            UIWindow window = getAttachedWindow();
            if (window == null) return;
            dragYaw = yaw;
            dragPitch = pitch;
            addClass(ORBITING_CLASS);
            // The BUTTON that started it, not the default. A drag ends when its OWN button is released,
            // and `startDrag` without one assumes left — so a middle-drag was never told its button came
            // up. The implicit capture release still fires, leaving a live drag with no button held that
            // goes on eating every mouse move: the preview span into a free-rotate mode that only another
            // click could stop. This engine already records that exact failure for CanvasView's
            // middle-button pan. Same mistake, one widget later.
            window.getInputHandler().getDragController().startDrag(surface, rawX, rawY,
                    event.getButtonId(),
                    new UIDragController.DragListener() {
                        @Override
                        public void onDragUpdate(float mouseX, float mouseY, float startX, float startY,
                                                 float deltaX, float deltaY) {
                            orbitTo(deltaX, deltaY);
                        }

                        @Override
                        public void onDragEnd(float mouseX, float mouseY) {
                            removeClass(ORBITING_CLASS);
                        }

                        @Override
                        public void onDragCancel() {
                            // Escape mid-orbit puts the camera back, which costs nothing and is the one
                            // way out of an accidental drag that has spun the mesh somewhere useless.
                            yaw = dragYaw;
                            pitch = dragPitch;
                            removeClass(ORBITING_CLASS);
                        }
                    });
            event.stopPropagation();
        }, false, true);

        surface.onMouseScroll.attachListener((element, event) -> {
            // A POSITIVE notch means the wheel rolled DOWN — the one sign in this engine that is
            // routinely taken at face value and inverted. ScrollerView is the statement of it.
            zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * (event.getScroll() > 0 ? 0.9f : 1.1f)));
            event.stopPropagation();
        }, false, true);
    }

    /**
     * Dragging the title bar moves the panel, which is what a title bar is for.
     *
     * <p>The move writes {@code left}/{@code top} at INLINE. The panel starts anchored by
     * {@code right}/{@code bottom} from the stylesheet — Unity's corner — and a definite width with both
     * insets set resolves to the start edge, so writing {@code left} is what takes over. Reading the
     * origin from the live layout rather than from a field is the same rule {@code UIResizer} follows:
     * a field only knows the positions this code wrote, so a panel placed by a stylesheet would report
     * 0 and teleport to the corner on the first drag.</p>
     */
    private void installMoveGesture() {
        head.onMouseDown.attachListener((element, event) -> {
            float rawX = event.getPosition().x(), rawY = event.getPosition().y();
            if (!head.containsScreenPoint(rawX, rawY)) return;

            UIWindow window = getAttachedWindow();
            if (window == null) return;

            UIElement block = resizeContainingBlock();
            if (block == null) return;
            // getX() is NOT in the same space as `left`. It is expressed in the frame screenToLocal maps
            // into — which carries the root transform, and reported x = -100 for a box at the top-left in
            // an earlier session — whereas `left` is an inset inside the containing block. Assigning one
            // to the other teleported the panel by the difference on the first press, before the pointer
            // had moved at all.
            //
            // Nor is resizeOriginLeft() the answer here: this panel is anchored by right/bottom from the
            // stylesheet, so its `left` inset is unset and reads 0 — which is the same teleport wearing a
            // different hat, and the exact failure that method's own doc warns about for a
            // stylesheet-placed element.
            //
            // The offset within the containing block is what `left` means, so that is what is measured.
            dragLeft = getRuntimeCache().getX() - block.getRuntimeCache().getX();
            dragTop = getRuntimeCache().getY() - block.getRuntimeCache().getY();
            window.getInputHandler().getDragController().startDrag(head, rawX, rawY,
                    (mouseX, mouseY, startX, startY, deltaX, deltaY) -> moveTo(deltaX, deltaY));
            event.stopPropagation();
        }, false, true);
    }

    /**
     * Places the panel from where it began plus the drag's own delta.
     *
     * <p>The stylesheet's {@code right}/{@code bottom} are left in place rather than cleared. Both insets
     * on an axis is well-defined when the size is definite — Taffy resolves in favour of the start edge —
     * so writing {@code left} is what takes over, and the panel keeps a sensible corner anchor if it is
     * never moved at all.</p>
     */
    private void moveTo(float deltaX, float deltaY) {
        placed = true;
        placeAt(dragLeft + deltaX, dragTop + deltaY);
    }

    /**
     * Writes a position, clamped to the containing block.
     *
     * <p>The same clamp {@code UIResizer} applies to a resize. Without it the panel goes straight out of
     * the canvas — and since the viewport is {@code overflow: hidden} it does not end up somewhere
     * awkward, it is simply <b>gone</b>, with no edge left to grab it back by.</p>
     */
    private void placeAt(float wantedLeft, float wantedTop) {
        UIElement block = resizeContainingBlock();
        if (block == null) return;

        float maxLeft = Math.max(0f, block.getRuntimeCache().getWidth() - getRuntimeCache().getWidth());
        float maxTop = Math.max(0f, block.getRuntimeCache().getHeight() - getRuntimeCache().getHeight());
        float left = Math.max(0f, Math.min(maxLeft, wantedLeft));
        float top = Math.max(0f, Math.min(maxTop, wantedTop));

        // No-ops when unchanged: `replaceOrPutCandidate` drops an identical value, which is what lets this
        // run every frame without re-dirtying layout forever.
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(), l -> l.left(left).top(top));
    }

    /**
     * Re-clamps after the canvas itself changed size.
     *
     * <p>Clamping only while dragging is not enough: the position is written once and then <b>stays</b>,
     * so dragging the split view narrower slides the viewport's edge past a panel that never moved. It
     * looked like the panel sinking under the border, which points at z-order and is nowhere near it.</p>
     *
     * <p>Only once the panel has actually been moved. Before that it is anchored by the stylesheet's
     * {@code right}/{@code bottom}, which already tracks a resizing viewport correctly — writing
     * {@code left}/{@code top} would take that over and pin it to a corner it was never dragged to.</p>
     */
    private void reclampIfPlaced() {
        if (!placed) return;
        placeAt(resizeOriginLeft(), resizeOriginTop());
    }

    /**
     * Applies a drag delta to the orbit.
     *
     * <p>From the orientation the drag <em>started</em> at, never the running one — a {@code DragListener}
     * is ticked every frame with the same accumulated delta, so adding it to the live value each time
     * would spin the mesh continuously while the pointer sat still.</p>
     */
    private void orbitTo(float deltaX, float deltaY) {
        yaw = dragYaw + deltaX * ORBIT_PER_PIXEL;
        // Clamped just short of the poles: at exactly ±90° the view basis degenerates and the mesh
        // appears to snap sideways as it passes through.
        float limit = (float) (Math.PI / 2) - 0.01f;
        pitch = Math.max(-limit, Math.min(limit, dragPitch + deltaY * ORBIT_PER_PIXEL));
    }

    private void openMeshMenu(float screenX, float screenY) {
        UIWindow window = getAttachedWindow();
        if (window == null) return;

        if (meshMenu == null) {
            meshMenu = new Menu();
            for (CgPreviewMesh option : CgPreviewMesh.values()) meshMenu.addItem(option.label());
            // Present but inert, exactly as asked. Listed rather than omitted because an absent entry
            // reads as "this editor cannot do that", where a disabled one reads as "not yet".
            meshMenu.addItem(CUSTOM_MESH_LABEL).setEnabled(false);

            // A section of its own, because it is a different KIND of thing: everything above chooses
            // what is being looked at, and this changes where it is looked at from. That is exactly the
            // distinction a native context menu's rules draw.
            meshMenu.addSeparator();
            meshMenu.addItem(RESET_VIEW_LABEL);

            // Resolved by LABEL at activation time rather than captured per item. Menu rows are ordinary
            // elements here, but reading the choice back from the event keeps this to one listener and
            // means an entry added later cannot be forgotten.
            meshMenu.onItemActivated.connect(item -> applyMenuChoice(item.getText()));
            // Must be IN the tree to be promoted to the top layer — a Menu is a Popover, and an
            // unparented one has nothing to promote from. Internal, because this panel is a composite.
            addInternalChild(meshMenu);
        }
        // ROOT space, not physical pixels. showAt's parameters are named rootX/rootY and mean it: the
        // menu is promoted to the top layer, whose containing block is the root, so a raw pointer
        // position lands wherever that number happens to fall in root coordinates — which put a menu
        // opened over the panel in the bottom-right corner of the whole window.
        var at = AnchoredPlacement.pointerToRoot(window, screenX, screenY);
        meshMenu.showAt(at.x(), at.y(), null);
    }

    /** Applies a menu row's label. Unknown labels — {@code Custom Mesh} — change nothing. */
    private void applyMenuChoice(String label) {
        if (RESET_VIEW_LABEL.equals(label)) {
            resetView();
            return;
        }
        for (CgPreviewMesh option : CgPreviewMesh.values()) {
            if (option.label().equals(label)) {
                setMesh(option);
                return;
            }
        }
    }

    // ── Redraw ──────────────────────────────────────────────────────────────

    /**
     * Re-renders once per frame.
     *
     * <p>Cheap when nothing changed: {@link CgMainPreviewRenderer#render} compares the emitted source,
     * the mesh and the camera and returns the existing texture untouched. The compile is the only cost
     * paid unconditionally, and it is the same one the node thumbnails already pay.</p>
     */
    @Override
    public boolean tickFrame(float delta) {
        reclampIfPlaced();
        CgShaderGraph graph = ShaderGraphBridge.toShaderGraph(document, shaderNodes, master);
        renderer.render(graph, master, mesh, yaw, pitch, zoom);
        return true;
    }

    /** Frees the render target and meshes. */
    public void delete() {
        renderer.delete();
    }

    public CgMainPreviewRenderer renderer() {
        return renderer;
    }

    /** The rectangle the picture is painted into — its own element so a theme can frame it. */
    private final class Surface extends UIElement {

        @Override
        protected void paintSelf(CgUiPaintContext ctx) {
            super.paintSelf(ctx);

            CgTexture texture = renderer.currentTexture();
            // Nothing yet: no successful compile so far. Painting nothing leaves the surface's own
            // background, so an empty panel reads as "not yet" rather than as a hole.
            if (!(texture instanceof CgTexture2D)) return;

            float x = getRuntimeCache().getX();
            float y = getRuntimeCache().getY();
            float w = getRuntimeCache().getWidth();
            float h = getRuntimeCache().getHeight();
            if (w <= 0f || h <= 0f) return;

            // Square, centred: the target is square and the shape is drawn through a square orthographic
            // box, so filling a non-square panel would stretch a sphere into an ellipse — the one
            // silhouette everyone knows, and the case ShaderNodePreview already letterboxes for.
            float side = Math.min(w, h);
            float ox = x + (w - side) * 0.5f;
            float oy = y + (h - side) * 0.5f;
            // v1 and v0 swapped: GL's framebuffer origin is bottom-left and the UI's is top-left.
            ctx.drawImage((CgTexture2D) texture, ox, oy, side, side, 0f, 1f, 1f, 0f, 0xFFFFFFFF);
        }
    }
}
