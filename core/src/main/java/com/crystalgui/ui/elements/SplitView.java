package com.crystalgui.ui.elements;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.input.UIDragController;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;

import java.util.ArrayList;
import java.util.List;

/**
 * n panes separated by draggable dividers.
 *
 * <h3>Layout</h3>
 * <pre>
 * splitview            (row, or column when vertical; paints nothing itself)
 * ├── __split-pane__ __first__     pane 0        flex-grow = weight
 * ├── __divider__                   divider 0     fixed size, hit-testable, owns the drag
 * ├── __split-pane__ __second__     pane 1        flex-grow = weight
 * ├── __divider__                   divider 1     (only once there are three panes)
 * └── __split-pane__                pane 2…
 * </pre>
 *
 * <h3>Why n and not two</h3>
 *
 * <p>It was binary until the dock needed a branch, and <b>nesting binary splits is not a cosmetic
 * substitute</b>: with three panes arranged as {@code (A | (B | C))}, dragging the A/B divider resizes A
 * against the <em>whole</em> {@code (B|C)} group and splits the change proportionally between B and C.
 * Every IDE moves only A and B. The picture is identical and the feel is wrong, which is the worst kind of
 * difference because no screenshot shows it. It also breaks round-tripping — resize, save, restore, and
 * the nested tree is a different tree.</p>
 *
 * <p>The two-pane API is kept exactly as it was, as a facade: {@link #first()} and {@link #second()} are
 * {@code pane(0)} and {@code pane(1)}, {@link #getPercentage()} is pane 0's share of the first two panes,
 * and {@link #setLimits} writes the same bounds it always did. A second n-ary container beside this one
 * would be the duplication {@code gui_curve.shader} is a standing monument to.</p>
 *
 * <h3>Sizing: weights, plus real minimums</h3>
 *
 * <p>A pane's share is a {@code flex-grow} weight, so panes divide whatever is left after the dividers and
 * the total can never overshoot. On top of that each pane carries an optional <b>pixel</b> minimum and
 * maximum ({@link #setPaneSizeLimits}), which is what a weight cannot express — "this sidebar is at least
 * 150px" stays true at every window size, and a percentage does not.</p>
 *
 * <p>{@code snap} is VS Code's: a pane dragged well past its minimum collapses to nothing rather than
 * stopping dead against it.</p>
 *
 * <p><b>{@code LayoutPriority} is deliberately not ported.</b> VS Code's only has meaning when its
 * {@code proportionalLayout} is off; flex weights are inherently proportional, which is VS Code's own
 * default, so there is nothing for the enum to select between here. A pane that must keep its pixel width
 * across a window resize is a fixed {@code flex-basis} with {@code flex-grow: 0} — a different mechanism,
 * and one to add when something actually asks for it rather than by analogy.</p>
 *
 * <h3>Differences from LDLib2's SplitView</h3>
 * <p>LDLib2 sizes its first pane with {@code widthPercent} and treats the divider as a
 * <em>virtual band</em> — a strip at the first pane's trailing edge, hit-tested by hand, with hover
 * feedback hand-painted and a drag-cursor sprite drawn each frame. Here a divider is a real element, so
 * its width, colour and {@code :hover} are plain CSS and hit-testing comes from the engine; the cursor
 * sprite is dropped in favour of the {@code :hover} rule.</p>
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

    /** Pane 0. Named for the two-pane case, which is still the overwhelming majority of uses. */
    public static final String FIRST_CLASS = "__first__";
    public static final String DIVIDER_CLASS = "__divider__";
    /** Pane 1. */
    public static final String SECOND_CLASS = "__second__";
    /**
     * On <b>every</b> pane, so a theme can style them uniformly however many there are.
     *
     * <p>Deliberately not {@code __pane__}: that is {@link Tab}'s class, and a theme rule written against
     * it would hit both widgets unless every author remembered to scope it.</p>
     */
    public static final String PANE_CLASS = "__split-pane__";

    /** Present on the root exactly while the orientation is {@link Orientation#VERTICAL}, so a
     * stylesheet can flip the divider's own axis ({@code splitview.__vertical__ .__divider__}). */
    public static final String VERTICAL_CLASS = "__vertical__";
    /**
     * On the root while a divider drag is running, so the sheet can keep the resize cursor up for the
     * whole gesture.
     *
     * <p>Needed because the drag captures the pointer on <b>this</b> element, not on the divider — the drag
     * maths are in the SplitView's local space — and the cursor resolves from the capture target. Without
     * it the cursor is correct while hovering the divider, reverts to the default arrow the moment you
     * press, and only comes back if you happen to release over the divider again.</p>
     */
    public static final String DRAGGING_CLASS = "__dragging__";

    /** Fires whenever the split actually moves, from any source. */
    public final Signal.Value<Float> onPercentageChanged = new Signal.Value<>();

    /** One pane: its element, its share, and what it refuses to shrink past. */
    private static final class Pane {
        final UIElement element;
        float weight;
        float minPx;
        float maxPx = Float.MAX_VALUE;
        boolean snap;

        Pane(UIElement element, float weight) {
            this.element = element;
            this.weight = weight;
        }
    }

    private final List<Pane> panes = new ArrayList<>();
    private final List<UIElement> dividers = new ArrayList<>();

    private Orientation orientation = Orientation.HORIZONTAL;
    private float minPercentage = 5f;
    private float maxPercentage = 95f;

    /** Every pane's weight at the moment a drag began; deltas are applied relative to these. */
    private float[] dragStartWeights = new float[0];

    public SplitView() {
        addPaneInternal(50f);
        addPaneInternal(50f);

        setOrientation(Orientation.HORIZONTAL);
        applySplit();

        // A BUBBLE listener, deliberately not attachDefaultListener: default listeners only fire in
        // the TARGET phase (EventListenerGroup.emitTarget), and the target here is a divider, not
        // this root. Attaching to the divider directly isn't possible either — attachDefaultListener
        // is package-private to com.crystalgui.ui and this class lives in ui.elements. Listening on
        // the bubble path and filtering on the target gets both: the divider stays the hit-test
        // target (so its :hover works), and a press anywhere in a pane is ignored.
        this.events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled()) return;
            // Resolved from the event, never captured: dividers are inserted and removed as panes come
            // and go, so an index closed over at construction would move the wrong one the moment the
            // layout changed. Same rule the pooled gutter arrows already document.
            int index = dividers.indexOf(event.getTarget());
            if (index < 0) return;
            beginDrag(index, event.getPosition().x(), event.getPosition().y());
        }, false, true);

        this.events.getGroup(KeyboardEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled()) return;
            int index = focusedDividerIndex();
            if (index < 0) return;
            float step = totalWeight() / 100f; // one percent of the whole, whatever the weights sum to
            switch (event.getKeyCode()) {
                case CgKeyCodes.KEY_LEFT, CgKeyCodes.KEY_UP -> nudgeDivider(index, -step);
                case CgKeyCodes.KEY_RIGHT, CgKeyCodes.KEY_DOWN -> nudgeDivider(index, step);
                case CgKeyCodes.KEY_HOME -> setPercentageAt(index, minPercentage);
                case CgKeyCodes.KEY_END -> setPercentageAt(index, maxPercentage);
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
        return part;
    }

    /** The root owns a fixed pane/divider structure; content goes into {@link #pane(int)}, which is an
     * ordinary element and accepts children normally. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    // ── Panes ───────────────────────────────────────────────────────────────

    public int paneCount() {
        return panes.size();
    }

    public UIElement pane(int index) {
        return panes.get(index).element;
    }

    public int dividerCount() {
        return dividers.size();
    }

    public UIElement divider(int index) {
        return dividers.get(index);
    }

    /** The leading pane — left when horizontal, top when vertical. Accepts children normally. */
    public UIElement first() {
        return pane(0);
    }

    /** The second pane — right when horizontal, bottom when vertical. Accepts children normally. */
    public UIElement second() {
        return pane(1);
    }

    /** The first divider, exposed for styling/focus; it holds no content. */
    public UIElement divider() {
        return divider(0);
    }

    /** Replaces the leading pane's content with {@code content} (LDLib2's {@code first(...)}). */
    public SplitView first(UIElement content) {
        return paneContent(0, content);
    }

    /** Replaces the second pane's content with {@code content} (LDLib2's {@code second(...)}). */
    public SplitView second(UIElement content) {
        return paneContent(1, content);
    }

    public SplitView paneContent(int index, UIElement content) {
        UIElement pane = pane(index);
        pane.clearAllChildren();
        pane.addChild(content);
        return this;
    }

    /**
     * Appends a pane, taking its weight from the pane it follows.
     *
     * <p>Halving the last pane rather than renormalising everything is the same rule the dock's tree uses
     * for a sibling insert, and for the same reason: every other pane keeps the proportion the user gave
     * it.</p>
     */
    public UIElement addPane() {
        return insertPane(panes.size());
    }

    /** Inserts a pane at {@code index}, splitting the weight of whichever pane it displaces. */
    public UIElement insertPane(int index) {
        int at = Math.max(0, Math.min(panes.size(), index));
        float weight;
        if (panes.isEmpty()) {
            weight = 100f;
        } else {
            Pane donor = panes.get(Math.min(at, panes.size() - 1));
            weight = donor.weight / 2f;
            donor.weight -= weight;
        }
        UIElement element = addPaneInternal(at, weight);
        configurePane(element);
        applySplit();
        return element;
    }

    /**
     * Removes a pane and gives its weight back to the others in proportion.
     *
     * @return whether it was removed. The last two panes are not removable — a split view with one pane is
     *         a container with a divider in it, and every caller would have to check for that shape.
     */
    public boolean removePane(int index) {
        if (panes.size() <= 2 || index < 0 || index >= panes.size()) return false;

        Pane removed = panes.remove(index);
        removeInternalChild(removed.element);
        // Remove the divider that separated it from a neighbour: the one before it, unless it was first.
        int dividerIndex = Math.max(0, index - 1);
        removeInternalChild(dividers.remove(dividerIndex));

        float total = totalWeight();
        if (total > 0f) {
            for (Pane pane : panes) pane.weight += removed.weight * (pane.weight / total);
        } else {
            for (Pane pane : panes) pane.weight = 100f / panes.size();
        }
        applySplit();
        return true;
    }

    private UIElement addPaneInternal(float weight) {
        return addPaneInternal(panes.size(), weight);
    }

    private UIElement addPaneInternal(int index, float weight) {
        UIElement element = newPart(PANE_CLASS);
        panes.add(index, new Pane(element, weight));

        if (panes.size() == 1) {
            addInternalChild(element);
        } else {
            // Internal children are [pane, divider, pane, divider, …], so a pane at logical index i sits
            // at child index 2i and the divider that precedes it at 2i-1.
            UIElement newDivider = newPart(DIVIDER_CLASS);
            // The divider is the focus target, not the root — arrow keys should move the split whether
            // or not the panes' own content is focusable.
            newDivider.setFocusPolicy(FocusPolicy.FOCUSABLE);

            if (index == 0) {
                insertInternalChildAt(element, 0);
                insertInternalChildAt(newDivider, 1);
                dividers.add(0, newDivider);
            } else {
                insertInternalChildAt(newDivider, 2 * index - 1);
                insertInternalChildAt(element, 2 * index);
                dividers.add(index - 1, newDivider);
            }
        }
        renamePaneClasses();
        return element;
    }

    /**
     * Keeps {@code __first__} and {@code __second__} on the panes that actually <em>are</em> first and
     * second.
     *
     * <p>No shipped sheet targets them today, but they are public constants and part of the widget's
     * contract, so a theme may. A pane inserted at the front has to take the class with it, or the rule
     * stays on whatever used to be there and the new leading pane gets nothing — the same positional
     * bookkeeping {@code TabView}'s strip does.</p>
     */
    private void renamePaneClasses() {
        for (int i = 0; i < panes.size(); i++) {
            UIElement element = panes.get(i).element;
            setClass(element, PANE_CLASS, true);
            setClass(element, FIRST_CLASS, i == 0);
            setClass(element, SECOND_CLASS, i == 1);
        }
    }

    private static void setClass(UIElement element, String cssClass, boolean present) {
        if (present == element.hasClass(cssClass)) return;
        if (present) {
            element.addClass(cssClass);
        } else {
            element.removeClass(cssClass);
        }
    }

    /**
     * Panes clip, and size from their weight alone.
     *
     * <p>Clipping is structural rather than cosmetic: a pane is a bounded region of the split, so content
     * larger than its share must not paint over the neighbouring pane. It also stops that content's
     * min-content size propagating out and forcing ancestors wider than they asked to be.</p>
     *
     * <p>{@code flex-basis: 0} makes the weights the ONLY thing deciding the split. Left at {@code auto}
     * the basis is the pane's content size, so a 2000px child would start the pane at 2000px and the
     * weights would merely distribute whatever was left over.</p>
     *
     * <p>All at DEFAULT origin, so a stylesheet can still override any of it.</p>
     */
    private void configurePane(UIElement pane) {
        StyleGroup.defaultPipeline(pane.getStyle().getGeneralGroup(), g -> g.overflow(Overflow.HIDDEN));
        StyleGroup.defaultPipeline(pane.getStyle().getLayoutGroup(), l -> l.flexBasis(0));
    }

    // ── Split ───────────────────────────────────────────────────────────────

    /** Pane 0's share of the first two panes, 0..100 — the two-pane facade over the weights. */
    public float getPercentage() {
        if (panes.size() < 2) return 100f;
        return percentageAt(0);
    }

    /** Moves the first divider. Clamped to {@link #setLimits}, signals only on a real change. */
    public SplitView setPercentage(float value) {
        return setPercentageAt(0, value);
    }

    /**
     * Moves divider {@code index} so the pane before it takes {@code value}% of that pair.
     *
     * <p>Only the two panes either side of the divider move — the semantic that nesting binary splits
     * cannot reproduce.</p>
     */
    public SplitView setPercentageAt(int index, float value) {
        if (index < 0 || index >= dividers.size()) return this;
        Pane before = panes.get(index);
        Pane after = panes.get(index + 1);
        float pairSum = before.weight + after.weight;
        if (pairSum <= 0f) return this;

        float clamped = clampPercentage(index, value);
        float newBefore = pairSum * clamped / 100f;
        float newAfter = pairSum - newBefore;
        // Compared against the WEIGHTS, not against a re-derived percentage. The percentage is a ratio of
        // two floats, so round-tripping a value through it does not land back on the same number --
        // setPercentage(30) twice would see 30 != 30.000002 and signal a second time. The question being
        // asked is "did anything actually change", and what changes is the weights.
        if (newBefore == before.weight && newAfter == after.weight) return this;

        before.weight = newBefore;
        after.weight = newAfter;
        applySplit();
        onStyleChanged();
        onPercentageChanged.emit(getPercentage());
        return this;
    }

    private float percentageAt(int index) {
        Pane before = panes.get(index);
        Pane after = panes.get(index + 1);
        float pairSum = before.weight + after.weight;
        // Multiply before dividing: `w / sum * 100` loses the exact value for anything that is not a
        // power of two, so a pane at 30 reads back as 30.000002.
        return pairSum <= 0f ? 0f : before.weight * 100f / pairSum;
    }

    private void nudgeDivider(int index, float deltaWeight) {
        float pairSum = panes.get(index).weight + panes.get(index + 1).weight;
        if (pairSum <= 0f) return;
        setPercentageAt(index, percentageAt(index) + deltaWeight / pairSum * 100f);
    }

    public float getMinPercentage() {
        return minPercentage;
    }

    public float getMaxPercentage() {
        return maxPercentage;
    }

    /** How far a divider may travel, as percentages of its own pair. Defaults to LDLib2's 5..95. */
    public SplitView setLimits(float minPercentage, float maxPercentage) {
        this.minPercentage = Math.max(0f, Math.min(100f, minPercentage));
        this.maxPercentage = Math.max(this.minPercentage, Math.min(100f, maxPercentage));
        setPercentage(getPercentage()); // re-clamp into the new limits
        return this;
    }

    /**
     * A pane's hard size limits in logical pixels, which a percentage cannot express.
     *
     * <p>"At least 150px" stays true at every window size; "at least 15%" does not. Pass
     * {@code Float.MAX_VALUE} for no maximum.</p>
     */
    public SplitView setPaneSizeLimits(int index, float minPx, float maxPx) {
        Pane pane = panes.get(index);
        pane.minPx = Math.max(0f, minPx);
        pane.maxPx = Math.max(pane.minPx, maxPx);
        return this;
    }

    /**
     * Whether this pane collapses to nothing when dragged well past its minimum, instead of stopping dead.
     *
     * <p>VS Code's {@code snap}. The half-a-minimum threshold is what stops it triggering on the way to
     * the minimum, which would make the pane unreachable at its smallest legal size.</p>
     */
    public SplitView setPaneSnap(int index, boolean snap) {
        panes.get(index).snap = snap;
        return this;
    }

    /**
     * The bounds divider {@code index} may move within, as a percentage of its pair, after folding in both
     * neighbours' pixel limits.
     *
     * @return {@code [min, max]}
     */
    private float[] boundsFor(int index) {
        Pane before = panes.get(index);
        Pane after = panes.get(index + 1);
        float pairSum = before.weight + after.weight;
        if (pairSum <= 0f) return new float[]{minPercentage, maxPercentage};

        float travel = travelLength();
        float weightPerPx = totalWeight() / travel;
        float pairPx = pairSum / weightPerPx;

        float min = minPercentage;
        float max = maxPercentage;
        if (pairPx > 0f) {
            // The pane before the divider is bounded directly; the one after is bounded by its complement.
            min = Math.max(min, before.minPx / pairPx * 100f);
            max = Math.min(max, before.maxPx / pairPx * 100f);
            min = Math.max(min, 100f - after.maxPx / pairPx * 100f);
            max = Math.min(max, 100f - after.minPx / pairPx * 100f);
        }
        if (min > max) {
            // Over-constrained — two minimums that cannot both be met. Meeting the leading one is the
            // stable choice: the alternative oscillates between them as the window resizes.
            max = min;
        }
        return new float[]{min, max};
    }

    private float clampPercentage(int index, float value) {
        float[] bounds = boundsFor(index);
        if (value < bounds[0]) {
            // Snap: past half of the minimum, the pane collapses rather than stopping against it.
            if (panes.get(index).snap && value < bounds[0] / 2f) return 0f;
            return bounds[0];
        }
        if (value > bounds[1]) {
            if (panes.get(index + 1).snap && value > bounds[1] + (100f - bounds[1]) / 2f) return 100f;
            return bounds[1];
        }
        return value;
    }

    private float totalWeight() {
        float total = 0f;
        for (Pane pane : panes) total += pane.weight;
        return total;
    }

    public Orientation getOrientation() {
        return orientation;
    }

    public SplitView setOrientation(Orientation orientation) {
        this.orientation = orientation;
        boolean vertical = orientation == Orientation.VERTICAL;

        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.flexDirection(vertical ? FlexDirection.COLUMN : FlexDirection.ROW));

        // No explicit min-width/min-height needed: the panes set `overflow: hidden` below, which now
        // feeds Taffy and zeroes their automatic minimum size for us (see TaffyBridge.setOverflow).
        // Before overflow was wired through, this had to be written by hand on the split axis.
        for (Pane pane : panes) configurePane(pane.element);

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
        for (Pane pane : panes) {
            float weight = pane.weight;
            StyleGroup.importantPipeline(pane.element.getStyle().getLayoutGroup(), l -> l.flexGrow(weight));
        }
    }

    public SplitView attachListener(Signal.Value.Listener<Float> action) {
        onPercentageChanged.connect(action);
        return this;
    }

    // ── Pointer mapping ─────────────────────────────────────────────────────

    private boolean isVertical() {
        return orientation == Orientation.VERTICAL;
    }

    /** Distance the dividers can travel: the content box along the split axis, minus every divider's own
     * size — the panes only ever share what's left after them. */
    private float travelLength() {
        var layout = getTaffyLayout();
        float content = isVertical() ? layout.contentBoxHeight() : layout.contentBoxWidth();
        float dividerSize = 0f;
        for (UIElement divider : dividers) {
            dividerSize += isVertical()
                    ? divider.getRuntimeCache().getHeight()
                    : divider.getRuntimeCache().getWidth();
        }
        return Math.max(1f, content - dividerSize);
    }

    private int focusedDividerIndex() {
        var window = getAttachedWindow();
        if (window == null) return dividers.isEmpty() ? -1 : 0;
        UIElement focused = window.getInputHandler().getFocusedElement();
        int index = dividers.indexOf(focused);
        if (index >= 0) return index;
        return dividers.isEmpty() ? -1 : 0;
    }

    /** Takes the RAW pointer position — {@link com.crystalgui.ui.input.UIDragController} converts to
     * local space itself and reports every later coordinate already converted. */
    private void beginDrag(int dividerIndex, float rawMouseX, float rawMouseY) {
        var window = getAttachedWindow();
        if (window == null) return;

        dragStartWeights = new float[panes.size()];
        for (int i = 0; i < panes.size(); i++) dragStartWeights[i] = panes.get(i).weight;

        float travel = travelLength();
        float total = totalWeight();
        addClass(DRAGGING_CLASS);
        window.getInputHandler().getDragController().startDrag(this, rawMouseX, rawMouseY,
                // Delta from the grab point, not absolute: grabbing the divider anywhere along its
                // thickness must not teleport it so its centre lands under the cursor.
                new UIDragController.DragListener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                        float delta = isVertical() ? dy : dx;
                        applyDividerDelta(dividerIndex, (delta / travel) * total);
                    }

                    @Override
                    public void onDragEnd(float mx, float my) {
                        removeClass(DRAGGING_CLASS);
                    }

                    @Override
                    public void onDragCancel() {
                        removeClass(DRAGGING_CLASS);
                    }
                });
    }

    /**
     * Moves one divider by a weight delta, measured from where the drag started.
     *
     * <p>Only the pair either side of it moves. Measuring from the drag's own start rather than from the
     * current weights is what stops clamping from accumulating: drag past the minimum and back, and the
     * divider returns to exactly where it was rather than to where the clamp left it.</p>
     */
    private void applyDividerDelta(int dividerIndex, float deltaWeight) {
        if (dividerIndex < 0 || dividerIndex + 1 >= dragStartWeights.length) return;
        float startBefore = dragStartWeights[dividerIndex];
        float startAfter = dragStartWeights[dividerIndex + 1];
        float pairSum = startBefore + startAfter;
        if (pairSum <= 0f) return;

        // Restore the pair to its drag-start split before re-applying, so `boundsFor` sees the same pair
        // extent every frame and the percentage below is measured against the same denominator.
        panes.get(dividerIndex).weight = startBefore;
        panes.get(dividerIndex + 1).weight = startAfter;

        float target = (startBefore + deltaWeight) / pairSum * 100f;
        setPercentageAt(dividerIndex, target);
    }
}
