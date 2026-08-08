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
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.AnchoredPlacement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.canvas.CanvasOverlayMove;
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
 * <h3>It is lit by default, and that is viewport shading rather than a lighting model</h3>
 * <p>This panel used to be unlit on the grounds that the pipeline has no lighting, so shading it would
 * show something the game cannot produce. True, and it made the panel nearly useless: an unlit sphere is
 * a filled circle, and the shape menu, the orbit and the zoom all exist to show <em>form</em>. The
 * framing that matters is not whether the picture matches the game but whether it is clear which you are
 * looking at — the same distinction a modelling tool's "material preview" viewport draws against its
 * final render.</p>
 *
 * <p>So the shading is a fixed key light baked into the preview's generated source
 * ({@link com.crystalgraphics.shadergraph.CgShaderEmitter.Shading}), touching no engine state, and the
 * {@code Lighting} menu entry turns it off when the colour matters more than the form.</p>
 */
public class MainPreviewPanel extends UIElement implements UIFrameTicker, Disposable.Gl {

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

    /**
     * Whether the preview lights its own output. On by default.
     *
     * <p>Viewport shading, not a lighting model — the engine has none, and this is a fixed key light
     * baked into the preview's generated source. Unlit is what the material actually draws in game, which
     * is why the toggle exists rather than the mode simply being on: when the colour matters more than
     * the form, the honest picture is one menu entry away.</p>
     */
    private boolean lit = true;

    /** Orbit at the moment the drag began — accumulating onto the live value compounds. */
    private float dragYaw;
    private float dragPitch;

    /**
     * Moving this panel and keeping it inside the canvas — shared with the Blackboard.
     *
     * <p><b>This class used to carry its own copy</b>, right down to the paragraph explaining why
     * {@code getX()} is not in the same space as {@code left}. {@link CanvasOverlayMove} was extracted
     * <em>from here</em> when the Blackboard needed the same behaviour, and this panel was never migrated
     * onto it — so every fix to the anchoring had to be written twice, in parallel, and got four rounds
     * each. Anchoring to the far edge, the measured re-clamp, the drag guard and the zero-box guard were
     * all landed in both files by hand before this move.</p>
     */
    private final CanvasOverlayMove move = CanvasOverlayMove.install(this, head, this::resizeContainingBlock);

    /** The one entry that is deliberately inert. @see #openMeshMenu */
    public static final String CUSTOM_MESH_LABEL = "Custom Mesh";

    /** Puts the orbit and zoom back to where the panel opened. @see #resetView */
    public static final String RESET_VIEW_LABEL = "Reset Camera";

    /** Toggles the preview's own shading. @see #setLit */
    public static final String LIGHTING_LABEL = "Lighting";

    @Nullable
    private Menu meshMenu;

    /** Kept so the check mark can be re-synced if the mode changes from outside the menu. */
    @Nullable
    private MenuItem lightingItem;

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

        buildMeshMenu();
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

    public boolean isLit() {
        return lit;
    }

    /** @see #lit */
    public MainPreviewPanel setLit(boolean value) {
        this.lit = value;
        if (lightingItem != null) lightingItem.setSelected(value);
        return this;
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
        // The MOVE gesture is not here: CanvasOverlayMove installs it from the field initializer, which is
        // the whole of what this panel needs to say about dragging its own title bar now.
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
     * Marks this panel as deliberately positioned, without moving it — for a rect restored from the file.
     *
     * <p>The re-clamp is gated on it, and it was only ever set by a drag, so a panel whose position came
     * from the document ignored the canvas resizing until it had been nudged once. @see
     * CanvasOverlayMove#markPlaced()</p>
     */
    public void markPlaced() {
        move.markPlaced();
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

    /**
     * Builds the context menu once, at construction.
     *
     * <p>Eager rather than lazy on first right-click. The laziness saved nothing measurable and cost real
     * clarity: the toggle's state lived in a menu that did not exist until someone opened it, so
     * {@code lightingItem} was null for most of the panel's life and nothing could inspect the menu
     * without simulating a press.</p>
     */
    private void buildMeshMenu() {
        {
            meshMenu = new Menu();
            for (CgPreviewMesh option : CgPreviewMesh.values()) meshMenu.addItem(option.label());
            // Present but inert, exactly as asked. Listed rather than omitted because an absent entry
            // reads as "this editor cannot do that", where a disabled one reads as "not yet".
            meshMenu.addItem(CUSTOM_MESH_LABEL).setEnabled(false);

            // A section of its own, because it is a different KIND of thing: everything above chooses
            // what is being looked at, and this changes where it is looked at from. That is exactly the
            // distinction a native context menu's rules draw.
            meshMenu.addSeparator();
            // Checkable, so its state is readable at a glance. Without it the entry looked like a command
            // rather than a toggle — there was no way to tell whether lighting was on except by studying
            // the sphere. Going through the MENU rather than the item is what reserves the mark gutter for
            // every row, so this one does not sit indented against its neighbours.
            lightingItem = meshMenu.addCheckableItem(LIGHTING_LABEL);
            lightingItem.setSelected(lit);
            meshMenu.addItem(RESET_VIEW_LABEL);

            // Resolved by LABEL at activation time rather than captured per item. Menu rows are ordinary
            // elements here, but reading the choice back from the event keeps this to one listener and
            // means an entry added later cannot be forgotten.
            meshMenu.onItemActivated.connect(item -> applyMenuChoice(item.getText()));
            // Must be IN the tree to be promoted to the top layer — a Menu is a Popover, and an
            // unparented one has nothing to promote from. Internal, because this panel is a composite.
            addInternalChild(meshMenu);
        }
    }

    private void openMeshMenu(float screenX, float screenY) {
        UIWindow window = getAttachedWindow();
        if (window == null || meshMenu == null) return;
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
        if (LIGHTING_LABEL.equals(label)) {
            setLit(!lit);
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
        move.reclampIfPlaced(resizeOriginLeft(), resizeOriginTop());
        CgShaderGraph graph = ShaderGraphBridge.toShaderGraph(document, shaderNodes, master);
        // The camera is framed for the panel, so the picture fills it rather than sitting letterboxed in
        // the middle of it. Read from the SURFACE, not from the panel: the header takes a strip off the
        // top, and framing to the outer box would crop the mesh by exactly that much.
        renderer.render(graph, master, mesh, yaw, pitch, zoom, lit, surfaceAspect());
        return true;
    }

    /** Frees the render target and meshes. */
    /**
     * Releases the preview renderer.
     *
     * <p>{@code Disposable.Gl} because {@code CgMainPreviewRenderer}'s target is {@code createOwned} and
     * therefore invisible to {@code CgFrameBufferRegistry} — so nothing else in the engine can free it,
     * and freeing it off the GL thread would corrupt silently rather than throw.</p>
     *
     * <p><b>This was dead code.</b> {@code delete()} existed and had no caller anywhere:
     * {@code ShaderGraphEditor}'s teardown released {@code previews} and not this, so the target and
     * its meshes leaked for the life of the process. It is now owned by the graph editor that builds it.</p>
     */
    @Override
    public void dispose() {
        Disposer.dispose(this);
    }

    public CgMainPreviewRenderer renderer() {
        return renderer;
    }

    /**
     * The picture area's width over its height, or {@code 1} before it has been laid out.
     *
     * <p>Square is the right answer for an unlaid-out panel rather than a guess: it is what the renderer
     * did unconditionally before, so a frame taken too early frames the mesh exactly as it always used to
     * and the next frame corrects it — as opposed to a zero, which collapses the orthographic box and
     * draws nothing at all.</p>
     */
    private float surfaceAspect() {
        float w = surface.getRuntimeCache().getWidth();
        float h = surface.getRuntimeCache().getHeight();
        return w > 0f && h > 0f ? w / h : 1f;
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

            // THE WHOLE SURFACE, stretched — which is only correct because the renderer was handed this
            // panel's aspect and squashed the picture by exactly the same factor on its way in. Drawing a
            // square target this way without that would turn a sphere into an ellipse.
            //
            // It used to letterboxed to `min(w, h)`, which was right when the camera was always square and
            // wrong in two visible ways: most of a wide panel was empty backdrop, and zooming in far enough
            // ran the mesh into the target's own square boundary — a sphere became a rounded square, which
            // reads as the shader being broken rather than as a frame.
            //
            // v1 and v0 swapped: GL's framebuffer origin is bottom-left and the UI's is top-left.
            ctx.drawImage((CgTexture2D) texture, x, y, w, h, 0f, 1f, 1f, 0f, 0xFFFFFFFF);
        }
    }
}
