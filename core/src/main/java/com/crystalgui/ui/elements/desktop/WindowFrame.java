package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.UIDragController;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;

import javax.annotation.Nullable;

/**
 * One window on the {@link Desktop} — chrome around a content slot, moved by its title bar and resized
 * by its eight edges.
 *
 * <p>CrystalOS's unit of stacking ({@code plan_windowing.md}). A window is an <b>element subtree</b>,
 * not a {@link UIWindow}: that class is the engine's {@code Document} analogue and the display surface
 * every frame here shares. The network layer already models a window as {@code (windowId, UIElement
 * root)}, so this is the visual home that model never had.</p>
 *
 * <h3>Extends {@link UIElement}, never {@code Dialog}</h3>
 * <p>{@code Dialog}'s bundle is modality, a close watcher and a backdrop — exactly what a frame must not
 * inherit, and {@code FloatingDock}'s javadoc already paid for that lesson once. What is taken from
 * {@code Dialog} is the <em>pattern</em>: a positional drag from the title bar writing {@code left}/
 * {@code top} at <b>INLINE</b> origin, matching what CSS {@code resize} mandates for the size
 * {@code UIResizer} writes, so an author's {@code !important} can still pin a window down. The two
 * halves of user-driven geometry stay in one origin.</p>
 *
 * <h3>The clamp is Windows', not {@code Dialog}'s</h3>
 * <p>{@code Dialog} and {@code CanvasOverlayMove} both clamp a panel <em>fully inside</em> its container,
 * which is right for a panel over a canvas and wrong for a window: a window wider than the desktop could
 * then never be dragged far enough to reach its own right-hand side. Every window manager clamps the
 * other way round — <b>the title bar must stay reachable</b>, and the body may hang off the sides and the
 * bottom. So {@code top} is pinned into {@code [0, workArea - caption]} and {@code left} may travel until
 * only a caption's width is left on screen. The sliver is <b>measured from the title bar</b> rather than
 * written here as a pixel constant, which is the same trick {@code NodePort.typeColor()} uses to keep a
 * number in the sheet where a theme can move it.</p>
 *
 * <h3>Intent and placement are two fields, deliberately</h3>
 * <p>{@link #wantedLeft}/{@link #wantedTop} is what was <em>asked</em> for; {@link #placedLeft}/
 * {@link #placedTop} is what was written after clamping. Keeping both is what makes a desktop resize
 * non-destructive: shrinking the desktop pulls a window in, and growing it back returns the window to
 * where the user actually put it. Clamping the stored value instead — which is what a single field
 * forces — quietly rewrites the user's intent, and the window never comes back.</p>
 *
 * <p>Neither is re-derived from the resolved box. {@code Dialog} records why: the clamp runs during
 * {@code advanceFrame}, <em>before</em> {@code calculateLayout}, so a frame that has not laid out yet
 * measures zero and reading it back writes that zero straight into the position.</p>
 *
 * <h3>What is deliberately not here yet</h3>
 * <ul>
 *   <li><b>Minimise and maximise buttons.</b> Their behaviour is W3 and W6. A control that looks
 *       clickable and does nothing is the lie the disabled-control rule already forbids, so they arrive
 *       with the state machine they operate rather than as greyed furniture.</li>
 *   <li><b>{@code WindowPolicy}</b> ({@code HIDE_ON_CLOSE} / {@code DESTROY_ON_CLOSE}) — W3. Until then
 *       {@link #requestClose()} detaches the frame, which is what {@code DESTROY_ON_CLOSE} will do
 *       through the policy switch. Named so its absence reads as a decision, the way
 *       {@code ToolWindowState.type} named its own.</li>
 *   <li><b>An icon slot.</b> W4, with the taskbar — a frame's icon is drawn in two places or in
 *       neither, and building an empty box now leaves every theme hiding it.</li>
 *   <li><b>A focus policy.</b> W2 brings activation, per-frame focus memory and the focus-ring
 *       carve-out together; a frame that can take focus before any of those rings its whole viewport,
 *       which is exactly the noise {@code :focus-visible} was introduced to remove.</li>
 * </ul>
 *
 * <p><b>One known W1 artefact.</b> {@code UIResizer} keeps a resize inside the containing block, which
 * this class's move-clamp deliberately does not. So dragging the <em>trailing</em> edge of a window that
 * is currently hanging off that edge pulls it back to the desktop's boundary instead of growing it. The
 * leading edges are unaffected (the same containment stops the origin going negative, which is where
 * this clamp starts), and the fix belongs with W6's maximise/restore geometry rather than in a special
 * case here.</p>
 */
public class WindowFrame extends UIElement {

    /** The drag handle, and everything drawn in it. */
    public static final String TITLE_BAR_CLASS = "__title-bar__";
    /**
     * The window's title text.
     *
     * <p><b>Not {@code __label__}</b>, which is the engine's usual hook for a widget's own text. A frame
     * contains other widgets that have one — every {@code Button} in the title bar for a start — so
     * {@code window .__label__} would reach them too, and the close button's glyph would be styled as a
     * window title. {@code Dialog} scopes its way out of that with {@code .__title-bar__ .__label__};
     * naming the role is cheaper and cannot be got wrong by a theme.</p>
     */
    public static final String TITLE_CLASS = "__title__";
    /** The button strip at the trailing end of the title bar. */
    public static final String CONTROLS_CLASS = "__controls__";
    /** The close affordance, sharing {@code Dialog}'s class so a theme styles both at once. */
    public static final String CLOSE_CLASS = "__close__";
    /**
     * The content slot.
     *
     * <p><b>Never target it with a descendant selector.</b> {@code CrystalEditor}, {@code ProjectFileTree}
     * and {@code ConfiguratorGroup} all name a child of their own {@code __content__}, so
     * {@code window .__content__} reaches every one of them inside any window and zeroes their heights —
     * the trap that has now been sprung three times. The sheet uses {@code window > .__content__}.</p>
     */
    public static final String CONTENT_CLASS = "__content__";

    /** Emitted after the frame closes, however it was closed. */
    public final Signal.Action onClosed = new Signal.Action();

    private final UIElement titleBar;
    private final UIElement controls;
    private final UIElement content;
    private final UIText titleLabel;
    private final Button closeButton;

    /** What was asked for, never clamped. @see WindowFrame */
    private float wantedLeft, wantedTop;
    /** What was written, after clamping. What a drag and {@code UIResizer} both measure from. */
    private float placedLeft, placedTop;
    /** Whether a position has been written at all — {@code Desktop} cascades the ones that have not. */
    private boolean placed;

    /** Origin at the moment a move began. Accumulating from here rather than from the live box keeps a
     * drag from compounding its own deltas — the same reason {@code UIResizer} snapshots its size. */
    private float dragStartLeft, dragStartTop;

    public WindowFrame(String title) {
        // Out of flow and positioned: a window is placed by left/top against the desktop's window layer,
        // not laid out among its siblings. This also earns the four LEADING resize handles --
        // rebuildResizers withholds them from anything an origin write cannot actually move.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).flexDirection(FlexDirection.COLUMN));

        titleLabel = new UIText(title == null ? "" : title);
        titleLabel.addClass(TITLE_CLASS);
        // FALLS THROUGH TO THE BAR. The move listener below is target-only, so a press that lands on the
        // title text would otherwise begin nothing at all -- and "the title bar drags except where the
        // title is" is indistinguishable from a broken drag.
        titleLabel.setHitTest(false);

        controls = new UIElement();
        controls.addClass(CONTROLS_CLASS);

        closeButton = new Button("");
        closeButton.addClass(CLOSE_CLASS);
        closeButton.attachListener(this::requestClose);
        controls.addChild(closeButton);

        titleBar = new UIElement();
        titleBar.addClass(TITLE_BAR_CLASS);
        titleBar.addChild(titleLabel);
        titleBar.addChild(controls);
        addInternalChild(titleBar);

        content = new UIElement();
        content.addClass(CONTENT_CLASS);
        addInternalChild(content);

        // TARGET-ONLY (false, false), which is Dialog's spelling and not CanvasOverlayMove's. The two
        // booleans are ADDITIVE -- the target phase is always subscribed -- so (false, true) would also
        // fire for anything that BUBBLES here, and the close button is inside this bar: a press on it
        // would start a window drag as well as closing the window.
        titleBar.onMouseDown.attachListener((element, event) ->
                beginMove(event.getPosition().x(), event.getPosition().y()), false, false);
    }

    /** A window owns its chrome; put content in {@link #content()}. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    // ── The parts ───────────────────────────────────────────────────────────

    /** Where a window's content goes. The named accessor a composite owes its callers. */
    public UIElement content() {
        return content;
    }

    /** The drag handle. Exposed so a caller may add chrome of its own beside the title. */
    public UIElement titleBar() {
        return titleBar;
    }

    /** The button strip. Exposed for the same reason {@link #titleBar()} is. */
    public UIElement controls() {
        return controls;
    }

    public Button closeButton() {
        return closeButton;
    }

    public WindowFrame setTitle(String title) {
        titleLabel.setText(title == null ? "" : title);
        return this;
    }

    public String getTitle() {
        return titleLabel.getText();
    }

    // ── Closing ─────────────────────────────────────────────────────────────

    /**
     * The close-watcher hook — "dismiss me". The close button and (from W3) Escape both arrive here, so
     * there is exactly one dismissal path.
     *
     * <p><b>W3 replaces this body with the policy switch</b> — {@code HIDE_ON_CLOSE} minimises to the
     * taskbar, {@code DESTROY_ON_CLOSE} does what this does today. Detaching is the honest W1 answer
     * rather than a stub: it is what destroy will be, and everything the engine already does at
     * {@code unregisterElement} (session capture, modal/popover/close-watcher cleanup) happens for free.</p>
     */
    @Override
    public boolean requestClose() {
        if (getParent() == null) return false;
        removeSelf();
        onClosed.emit();
        return true;
    }

    // ── Geometry ────────────────────────────────────────────────────────────

    /**
     * Places the window against the desktop's window layer, clamped so its title bar stays reachable.
     *
     * <p>Marks the frame <b>placed</b>, so {@link Desktop} stops cascading it. A position handed in by a
     * caller — or read back from a session — is every bit as deliberate as one dragged to, which is the
     * correction {@code CanvasOverlayMove.markPlaced} had to make after gating on drags alone.</p>
     */
    public WindowFrame moveTo(float left, float top) {
        placed = true;
        applyPosition(left, top);
        return this;
    }

    /**
     * Sizes the window.
     *
     * <p><b>INLINE</b>, which is the same slot {@code UIResizer} writes — so a user's drag legitimately
     * replaces what a caller asked for rather than fighting it every frame. That collision is the correct
     * one: the spec has a user resize replace "existing property declaration(s) in the style attribute".</p>
     */
    public WindowFrame resizeTo(float width, float height) {
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(), l -> l.width(width).height(height));
        return this;
    }

    /** Whether this frame has been given a position — by a caller, a drag, or the desktop's cascade. */
    public boolean isPlaced() {
        return placed;
    }

    /** The window's origin inside the work area, as last written. */
    public float left() {
        return placedLeft;
    }

    public float top() {
        return placedTop;
    }

    /**
     * A window owns its position, so a top/left resize handle has to go through it rather than writing
     * {@code left}/{@code top} itself — otherwise the handle's write and the next clamp fight, and the
     * window resizes while snapping its origin home. {@code Dialog} records the same trap.
     */
    @Override
    protected void applyResizeOrigin(float left, float top) {
        placed = true;
        applyPosition(left, top);
    }

    /** The written position once there is one; the measured offset until then. @see UIElement#resizeOriginLeft */
    @Override
    protected float resizeOriginLeft() {
        return placed ? placedLeft : super.resizeOriginLeft();
    }

    @Override
    protected float resizeOriginTop() {
        return placed ? placedTop : super.resizeOriginTop();
    }

    /**
     * Re-clamps this window against the work area as it is now.
     *
     * <p>Driven by {@link Desktop}'s window layer when <em>it</em> resizes, because that is the thing
     * that moved: an absolutely positioned frame at a fixed size sees no layout change of its own when
     * the desktop shrinks under it, so its own callback never fires. {@code CanvasOverlayMove} records
     * the same asymmetry, and the failure it causes — an edge sliding past a panel that never moved,
     * which reads as a z-order bug and is nowhere near one.</p>
     *
     * <p>Re-clamps from the <b>wanted</b> position, so a window pushed in by a shrinking desktop returns
     * to where the user put it when the room comes back.</p>
     */
    void reclamp() {
        if (!placed) return;
        // NOT WHILE A DRAG IS LIVE. The clamp reads measured boxes, which lag the drag by a frame, so
        // running both writes last frame's answer over the one the pointer just asked for.
        UIWindow window = getAttachedWindow();
        if (window != null && window.getInputHandler().getDragController().isDragging()) return;
        applyPosition(wantedLeft, wantedTop);
    }

    /**
     * Post-layout is where an unplaced window learns where it goes: the cascade offset needs a measured
     * caption height and a measured work area, and neither exists when {@code addWindow} runs.
     *
     * <p>Writing style from here re-dirties layout, which is deliberate and settles — the same shape
     * {@code UIText.recompute} uses, and for the same reason {@code replaceOrPutCandidate} exists.</p>
     */
    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (placed) {
            applyPosition(wantedLeft, wantedTop);
            return;
        }
        Desktop desktop = desktop();
        if (desktop != null) desktop.placeByCascade(this);
    }

    /** The desktop this window is on, or null while it is detached. */
    @Nullable
    public Desktop desktop() {
        for (UIElement element = getParent(); element != null; element = element.getParent()) {
            if (element instanceof Desktop) return (Desktop) element;
        }
        return null;
    }

    /** The caption's measured height — the cascade step, and the sliver the clamp keeps on screen. */
    float captionHeight() {
        return titleBar.getRuntimeCache().getHeight();
    }

    private void beginMove(float pointerX, float pointerY) {
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        // A synthesized activation press (Space/Enter on a focused element) carries the cursor's position,
        // which may be nowhere near the bar. Honouring one teleports the window.
        if (!titleBar.containsScreenPoint(pointerX, pointerY)) return;

        // FROM WHERE THE WINDOW IS, not from what was last asked for. A window currently held at the
        // edge by the clamp has a wanted position further out; starting a drag from that would spend the
        // difference before anything moved.
        dragStartLeft = placedLeft;
        dragStartTop = placedTop;

        UIDragController drag = window.getInputHandler().getDragController();
        // Positional drag, zero threshold: a window must track the very first pixel, and a title bar has
        // no competing click interpretation to protect.
        drag.startDrag(titleBar, pointerX, pointerY,
                (mouseX, mouseY, startX, startY, deltaX, deltaY) -> {
                    placed = true;
                    applyPosition(dragStartLeft + deltaX, dragStartTop + deltaY);
                });
    }

    /** Records the intent, then writes it clamped. */
    private void applyPosition(float left, float top) {
        wantedLeft = left;
        wantedTop = top;

        float clampedLeft = left;
        float clampedTop = top;

        UIElement area = resizeContainingBlock();
        float areaWidth = area == null ? 0f : area.getRuntimeCache().getWidth();
        float areaHeight = area == null ? 0f : area.getRuntimeCache().getHeight();
        float frameWidth = getRuntimeCache().getWidth();
        float caption = captionHeight();

        // A ZERO BOX CARRIES NO INFORMATION, so the intent is written through unclamped rather than
        // clamped against nothing. CanvasOverlayMove's version returns early instead and loses the write
        // entirely -- which is survivable there because something re-places the panel every frame, and
        // would strand a window here on the one frame that matters, its first.
        if (areaWidth > 0f && areaHeight > 0f && frameWidth > 0f && caption > 0f) {
            clampedLeft = clamp(left, caption - frameWidth, areaWidth - caption);
            clampedTop = clamp(top, 0f, areaHeight - caption);
        }

        placedLeft = clampedLeft;
        placedTop = clampedTop;

        final float writtenLeft = clampedLeft;
        final float writtenTop = clampedTop;
        // INLINE, matching UIResizer. No-ops when unchanged -- replaceOrPutCandidate drops an identical
        // value, which is what lets this run per layout pass without re-dirtying layout forever.
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(),
                l -> l.left(writtenLeft).top(writtenTop));
    }

    /** {@code lo} is allowed to exceed {@code hi} — a window narrower than its own caption — and the
     * upper bound wins, which keeps the title bar on screen rather than the body. */
    private static float clamp(float value, float lo, float hi) {
        return Math.max(Math.min(lo, hi), Math.min(value, hi));
    }
}
