package com.crystalgui.ui.elements;

import com.crystalgui.core.input.keyboard.CgUiKeyCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * Two panes separated by a draggable divider.
 *
 * <h3>Layout</h3>
 * <pre>
 * splitview            (row, or column when vertical; paints nothing itself)
 * ├── __first__        flex-grow = percentage
 * ├── __divider__      fixed size, hit-testable, owns the drag
 * └── __second__       flex-grow = 100 - percentage
 * </pre>
 *
 * <p>This is the first widget here that is <em>meant</em> to hold arbitrary content, so unlike
 * Button/Checkbox/Slider it exposes child slots — but only two, in fixed positions. The root itself
 * still refuses {@code addChild}; content goes through {@link #first()}/{@link #second()}, which
 * return ordinary elements that accept children normally.</p>
 *
 * <h3>Differences from LDLib2's SplitView</h3>
 * <p>LDLib2 sizes its first pane with {@code widthPercent} and treats the divider as a
 * <em>virtual band</em> — a strip at the first pane's trailing edge, hit-tested by hand, with hover
 * feedback hand-painted and a drag-cursor sprite drawn each frame. Here the divider is a real
 * element, so its width, colour and {@code :hover} are plain CSS and hit-testing comes from the
 * engine; the cursor sprite is dropped in favour of the {@code :hover} rule.</p>
 *
 * <p>The split is also carried by flex weights rather than a percentage width, so the two panes
 * divide the space <em>left over after</em> the divider — the divider's width can never push the
 * total past 100%. Same mechanism {@link Slider} uses.</p>
 *
 * <h3>Why this deliberately does NOT animate</h3>
 * <p>Same reason as {@link Slider}: while dragging, the divider has to sit exactly under the cursor,
 * and a {@code transition} on the flex weights would make it lag by the transition's duration. The
 * weights are written at {@code IMPORTANT} origin with no transition declared, and a theme should
 * not add one.</p>
 */
public class SplitView extends UIElement {

    public enum Orientation {
        HORIZONTAL,
        VERTICAL
    }

    public static final String FIRST_CLASS = "__first__";
    public static final String DIVIDER_CLASS = "__divider__";
    public static final String SECOND_CLASS = "__second__";

    /** Present on the root exactly while the orientation is {@link Orientation#VERTICAL}, so a
     * stylesheet can flip the divider's own axis ({@code splitview.__vertical__ .__divider__}). */
    public static final String VERTICAL_CLASS = "__vertical__";

    /** Fires whenever the split actually moves, from any source. */
    public final Signal.Value<Float> onPercentageChanged = new Signal.Value<>();

    private final UIElement first;
    private final UIElement divider;
    private final UIElement second;

    private Orientation orientation = Orientation.HORIZONTAL;
    /** Share of the space given to the first pane, 0..100. */
    private float percentage = 50f;
    private float minPercentage = 5f;
    private float maxPercentage = 95f;

    /** Split at the moment a divider drag began; drag deltas are applied relative to it. */
    private float dragStartPercentage;

    public SplitView() {
        this.first = newPart(FIRST_CLASS);
        this.divider = newPart(DIVIDER_CLASS);
        this.second = newPart(SECOND_CLASS);

        // Panes clip. Structural rather than cosmetic: a pane is a bounded region of the split, so
        // content larger than its share must not paint over the neighbouring pane. It also stops that
        // content's min-content size propagating out and forcing ancestors wider than they asked to
        // be — the same `min-size: auto` trap the panes themselves guard against below, one level up.
        // At DEFAULT origin, so a stylesheet can still override it.
        for (UIElement pane : new UIElement[]{first, second}) {
            StyleGroup.defaultPipeline(pane.getStyle().getGeneralGroup(),
                    g -> g.overflow(Overflow.HIDDEN));
        }

        // The divider is the focus target, not the root — arrow keys should move the split whether
        // or not the panes' own content is focusable.
        this.divider.setFocusPolicy(FocusPolicy.FOCUSABLE);

        setOrientation(Orientation.HORIZONTAL);
        applySplit();

        // A BUBBLE listener, deliberately not attachDefaultListener: default listeners only fire in
        // the TARGET phase (EventListenerGroup.emitTarget), and the target here is the divider, not
        // this root. Attaching to the divider directly isn't possible either — attachDefaultListener
        // is package-private to com.crystalgui.ui and this class lives in ui.elements. Listening on
        // the bubble path and filtering on the target gets both: the divider stays the hit-test
        // target (so its :hover works), and a press anywhere in a pane is ignored.
        this.events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled()) return;
            if (event.getTarget() != divider) return;
            beginDrag(event.getPosition().x(), event.getPosition().y());
        }, false, true);

        this.events.getGroup(KeyboardEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled()) return;
            float step = 1f;
            switch (event.getKeyCode()) {
                case CgUiKeyCodes.KEY_LEFT, CgUiKeyCodes.KEY_UP -> setPercentage(percentage - step);
                case CgUiKeyCodes.KEY_RIGHT, CgUiKeyCodes.KEY_DOWN -> setPercentage(percentage + step);
                case CgUiKeyCodes.KEY_HOME -> setPercentage(minPercentage);
                case CgUiKeyCodes.KEY_END -> setPercentage(maxPercentage);
                default -> {
                    return;
                }
            }
            // Consume, so Tab-traversal/activation doesn't also act on a key we handled.
            event.stopPropagation();
        }, false, false);
    }

    private UIElement newPart(String cssClass) {
        UIElement part = new UIElement();
        part.addClass(cssClass);
        addInternalChild(part);
        return part;
    }

    /** The root owns a fixed three-child structure; content goes into {@link #first()}/
     * {@link #second()}, which are ordinary elements and accept children normally. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    // ── Panes ───────────────────────────────────────────────────────────────

    /** The leading pane — left when horizontal, top when vertical. Accepts children normally. */
    public UIElement first() {
        return first;
    }

    /** The trailing pane — right when horizontal, bottom when vertical. Accepts children normally. */
    public UIElement second() {
        return second;
    }

    /** The divider itself, exposed for styling/focus; it holds no content. */
    public UIElement divider() {
        return divider;
    }

    /** Replaces the leading pane's content with {@code content} (LDLib2's {@code first(...)}). */
    public SplitView first(UIElement content) {
        first.clearAllChildren();
        first.addChild(content);
        return this;
    }

    /** Replaces the trailing pane's content with {@code content} (LDLib2's {@code second(...)}). */
    public SplitView second(UIElement content) {
        second.clearAllChildren();
        second.addChild(content);
        return this;
    }

    // ── Split ───────────────────────────────────────────────────────────────

    public float getPercentage() {
        return percentage;
    }

    /** Moves the split. Clamped to {@link #setLimits}, signals only on a real change. */
    public SplitView setPercentage(float value) {
        float clamped = clamp(value);
        if (clamped == this.percentage) return this;
        this.percentage = clamped;
        applySplit();
        onStyleChanged();
        onPercentageChanged.emit(clamped);
        return this;
    }

    public float getMinPercentage() {
        return minPercentage;
    }

    public float getMaxPercentage() {
        return maxPercentage;
    }

    /** How far the divider may travel, as percentages of the whole. Defaults to LDLib2's 5..95. */
    public SplitView setLimits(float minPercentage, float maxPercentage) {
        this.minPercentage = Math.max(0f, Math.min(100f, minPercentage));
        this.maxPercentage = Math.max(this.minPercentage, Math.min(100f, maxPercentage));
        setPercentage(this.percentage); // re-clamp into the new limits
        return this;
    }

    private float clamp(float value) {
        return Math.max(minPercentage, Math.min(maxPercentage, value));
    }

    public Orientation getOrientation() {
        return orientation;
    }

    public SplitView setOrientation(Orientation orientation) {
        this.orientation = orientation;
        boolean vertical = orientation == Orientation.VERTICAL;

        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.flexDirection(vertical ? FlexDirection.COLUMN : FlexDirection.ROW));

        // `flex-basis: 0` makes the weights the ONLY thing deciding the split. Left at `auto` the
        // basis is the pane's content size, so a 2000px child would start the pane at 2000px and the
        // percentage would merely distribute whatever was left over.
        //
        // No explicit min-width/min-height needed: the panes set `overflow: hidden` above, which now
        // feeds Taffy and zeroes their automatic minimum size for us (see TaffyBridge.setOverflow).
        // Before overflow was wired through, this had to be written by hand on the split axis.
        for (UIElement pane : new UIElement[]{first, second}) {
            StyleGroup.defaultPipeline(pane.getStyle().getLayoutGroup(), l -> l.flexBasis(0));
        }

        if (vertical) {
            addClass(VERTICAL_CLASS);
        } else {
            removeClass(VERTICAL_CLASS);
        }
        return this;
    }

    /** Pushes the split into the panes' flex weights. IMPORTANT origin because this is runtime state
     * a stylesheet has no business overriding — same reasoning as {@link Slider}'s fill/spacer. */
    private void applySplit() {
        StyleGroup.importantPipeline(first.getStyle().getLayoutGroup(), l -> l.flexGrow(percentage));
        StyleGroup.importantPipeline(second.getStyle().getLayoutGroup(), l -> l.flexGrow(100f - percentage));
    }

    public SplitView attachListener(Signal.Value.Listener<Float> action) {
        onPercentageChanged.connect(action);
        return this;
    }

    // ── Pointer mapping ─────────────────────────────────────────────────────

    private boolean isVertical() {
        return orientation == Orientation.VERTICAL;
    }

    /** Distance the divider can travel: the content box along the split axis, minus the divider's
     * own size — the panes only ever share what's left after it. */
    private float travelLength() {
        var layout = getTaffyLayout();
        float content = isVertical() ? layout.contentBoxHeight() : layout.contentBoxWidth();
        float dividerSize = isVertical()
                ? divider.getRuntimeCache().getHeight()
                : divider.getRuntimeCache().getWidth();
        return Math.max(1f, content - dividerSize);
    }

    /** Takes the RAW pointer position — {@link com.crystalgui.ui.input.UIDragController} converts to
     * local space itself and reports every later coordinate already converted. */
    private void beginDrag(float rawMouseX, float rawMouseY) {
        var window = getAttachedWindow();
        if (window == null) return;
        this.dragStartPercentage = this.percentage;
        float travel = travelLength();
        window.getInputHandler().getDragController().startDrag(this, rawMouseX, rawMouseY,
                // Delta from the grab point, not absolute: grabbing the divider anywhere along its
                // thickness must not teleport it so its centre lands under the cursor.
                (mx, my, sx, sy, dx, dy) -> {
                    float delta = isVertical() ? dy : dx;
                    setPercentage(dragStartPercentage + (delta / travel) * 100f);
                });
    }
}
