package com.crystalgui.widget.overlay;

import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.State;
import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.service.AnchoredPlacement;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import dev.vfyjxf.taffy.style.TaffyDisplay;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.crystalgui.ui.service.Drag;

/**
 * A tooltip — an element promoted into the {@linkplain UIElement#document().isPromoted(this) top layer} and kept
 * anchored to another element.
 *
 * <h3>Why this needs the top layer at all</h3>
 * <p>A tooltip's whole job is to draw <em>outside</em> the thing it describes. Before the top layer
 * existed there was no way to do that: {@code drawSubtree} paints depth-first under every ancestor's
 * scissor, so a tooltip on a row inside an {@code overflow: hidden} scroller was clipped to the
 * scroller. Promotion is what lets it escape ancestor clip, opacity and transform — see
 * {@link TopLayer#add}.</p>
 *
 * <h3>Placement</h3>
 * <p>Recomputed <b>every frame</b> from the anchor's current box rather than cached at show time.
 * That is not laziness — an anchor can scroll, animate, or be reflowed by a sibling's text wrapping,
 * and a cached position would silently drift away from it. Cost is a few floats per visible tooltip,
 * and only while one is visible.</p>
 *
 * <p>Placement is below the anchor by default and <b>flips above</b> when there is not enough room
 * below, then clamps horizontally into the containing block. This is the useful subset of the web's
 * CSS Anchor Positioning ({@code position-try-fallbacks}); the full property surface is deliberately
 * not implemented yet.</p>
 *
 * <p><b>No pixel values here.</b> The gap between anchor and tooltip is the tooltip's own
 * {@code margin} from {@code default.css} — Java positions it flush to the anchor edge and lets the
 * cascade decide the spacing, per this codebase's rule that widgets write structure, stylesheets
 * write geometry.</p>
 */
public class Tooltip extends UIElement {

    public static final Name NAME = Name.of("tooltip");

    public static final State<Tooltip, String> TEXT =
            State.<Tooltip, String>of("text", StateTypes.STRING,
                            Tooltip::getBaseText, Tooltip::setText, "")
                    .omittedWhen("");

    /**
     * Reads {@code getBaseText} and not {@code getText}, and the difference matters: the displayed text
     * has the resolved accelerator appended, so writing it would send a client its own decoration back
     * and a round trip would append it twice.
     */
    public static final WidgetContract<Tooltip> CONTRACT = WidgetContracts.register(
            WidgetContract.of(Tooltip.class, "tooltip")
                    .state(TEXT)
                    .build());


    /** {@code tooltip::part(label)} in a sheet. */
    public static final String LABEL_PART = "label";

    /**
     * Which side of its anchor the tooltip prefers, and how far off it sits.
     *
     * <h3>Why this is settable rather than always below</h3>
     *
     * <p>Below is right for the common case — a toolbar button, a truncated label — and wrong for anything
     * in a narrow vertical rail. An activity bar button is 16px in a 20px column, so a tooltip below it
     * covers the <em>next</em> button down, which is the one you were about to read. Both editors place a
     * stripe tooltip to the side for exactly that reason.</p>
     *
     * <p>A preference, not an instruction: {@link AnchoredPlacement} still flips to the opposite side when
     * there is no room, so a right-hand rail gets its tooltips on the left without anyone configuring
     * it.</p>
     */
    private AnchoredPlacement.Side side = AnchoredPlacement.Side.BOTTOM;

    /** Distance from the anchor's edge, in logical pixels. Zero for the flush look a label wants. */
    private float gap;

    public Tooltip setSide(AnchoredPlacement.Side preferred) {
        this.side = preferred == null ? AnchoredPlacement.Side.BOTTOM : preferred;
        return this;
    }

    public Tooltip setGap(float pixels) {
        this.gap = pixels;
        return this;
    }

    public AnchoredPlacement.Side getSide() {
        return side;
    }

    private final ShadowRoot shadow;
    private final UIText label;

    /**
     * What {@link #setText} wrote — what this tooltip says outside every {@linkplain #addRegion region}.
     *
     * <p>Held separately from the label because a region overwrites the label and the base text has to
     * survive that: without it, moving the pointer off a region would have nothing to go back to.</p>
     */
    private String baseText;

    /** Regions, in the order added. The <b>deepest</b> one containing the pointer wins. @see #addRegion */
    @Nullable
    private List<Region> regions;

    /** Which region is currently speaking, so a frame that changes nothing writes nothing. */
    @Nullable
    private Region activeRegion;

    @Nullable
    private UIElement anchor;
    private boolean placementTickerRunning;

    /** The anchor a wait is running for, or null when none is. @see #showAfterDelay */
    @Nullable
    private UIElement pendingAnchor;

    /** Seconds left on that wait. Only meaningful while {@link #pendingAnchor} is set. */
    private float pendingDelay;
    private boolean delayTickerRunning;

    /** Whether the cascade has been run for this node since it joined. @see #resolveStyleOnce */
    private boolean styleResolved;

    public Tooltip() {
        this("");
    }

    public Tooltip(String text) {
        super(NAME);
        this.shadow = attachShadow();
        this.baseText = text == null ? "" : text;
        this.label = new UIText(text == null ? "" : text);
        // SELF-SIZING, forced rather than detected. The wrap bound in UIText.selfMaxWidthForWrap is only
        // consulted for a label that sizes ITSELF; one that latched "takes what it is handed" wraps against
        // its own content box instead — which the sheet's max-width has already clamped, so the box comes
        // out at the maximum while the glyphs run on past it unwrapped. That is exactly what a long tooltip
        // over a short anchor looked like: a background two thirds the length of its own text.
        //
        // The latch is decided by the FIRST measurement, which happens here with the label empty and its
        // ancestors unmeasured — the racy case forceSelfSizeWidth exists for.
        // forceSelfSizeWidth is GONE, and its absence is the fix rather than a loss: it existed
        // because UIText latched "does it size itself" from its FIRST measurement, which here
        // happened with the label empty and its ancestors unmeasured. Min-content is a question
        // the engine asks per layout now (Measurable.Fit), so there is no latch to pre-empt.
        this.label.setHitTest(false);
        this.label.set(Attribute.PART, LABEL_PART);
        this.shadow.append(this.label);

        // A tooltip is decoration: it must never eat the pointer, or hovering the tooltip that
        // appeared under the cursor would count as leaving the anchor, hiding it, which un-hovers
        // the tooltip, which shows it again — a flicker loop. The web marks tooltips
        // pointer-events: none for exactly this reason.
        setHitTest(false);

        setHidden(true);
    }

    /**
     * A closed tooltip is {@code display: none}, exactly as a closed popover is on the web.
     *
     * <p>This is not cosmetic. A tooltip lives as an internal child of its anchor so the cascade
     * works, and an ordinary child participates in its parent's flex flow — so a <em>hidden</em>
     * tooltip would silently pad every element that had one. {@code display: none} takes it out of
     * Taffy's layout entirely, and is also short-circuited by both {@code drawSubtree} and
     * {@code elementHitTest}, so one property covers layout, paint and input together.</p>
     */
    /**
     * Hides the box while it has nothing to say, and brings it back when it does.
     *
     * <h3>An empty tooltip is never wanted, anywhere</h3>
     *
     * <p>It draws a bare rounded rectangle over whatever is underneath — an answer-shaped thing with no
     * answer in it, which reads as a rendering fault rather than as silence. The dock recorded this as a
     * REASON NOT TO WIRE a region without a base text; it is really a reason to make emptiness mean
     * "say nothing", which is what every other text surface in the engine already does.</p>
     *
     * <p>That is what lets a REGION be the only thing a tooltip says. The project tree wants exactly
     * that: its icon knows what a file declares, and the row itself has nothing to add that the row is
     * not already showing — where a dock tab genuinely does, because a tab is a name with no path
     * around it.</p>
     */
    private void applyEmptiness() {
        setHidden(label.text().isEmpty());
    }

    private void setHidden(boolean hidden) {
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(),
                l -> l.display(hidden ? TaffyDisplay.NONE : TaffyDisplay.FLEX));
    }

    /**
     * Attaches a hover tooltip to {@code anchor} and returns it.
     *
     * <p>This lives here rather than as {@code UIElement.setTooltip} on purpose. {@link UIElement} is
     * the core DOM node that every widget is built on; a tooltip is a widget. Putting the wiring on
     * {@code UIElement} inverted that — core would import {@code ui.elements} — and it grew the class
     * every element in the tree pays for by a field and three methods, for a feature most elements
     * never use. Here, the cost is borne only by trees that actually have a tooltip.</p>
     *
     * <p>The tooltip becomes an <b>internal child of the anchor</b>, which keeps the cascade behaving
     * the way the web's does: it inherits {@code color}, {@code font-family} and the rest from where
     * it sits in the tree, not from wherever it happens to paint. Promotion moves only its Taffy node
     * and its paint/hit-test entry. Being internal, it is skipped by public traversal and by
     * {@code UIDescriptionCodec}, like every other internal child.</p>
     *
     * <p>Shown a beat after {@code mouseenter}, hidden immediately on {@code mouseleave}. The wait is
     * {@code tooltip-delay} in the cascade rather than a constant here — which is what this paragraph
     * used to say was "separate work". {@link #showAfterDelay} has the argument for why it exists at
     * all; the asymmetry with hiding is deliberate and is explained there too.</p>
     */
    /**
     * What this tooltip describes, or {@code null} while it belongs to nothing.
     *
     * <p>Public because the tooltip is no longer a CHILD of its anchor -- it joins the document, so
     * walking an anchor's children can no longer answer "does this have a tooltip". This can.</p>
     */
    @Nullable
    public UIElement anchor() {
        return anchor;
    }

    public static Tooltip attach(UIElement anchor, String text) {
        Objects.requireNonNull(anchor, "anchor");
        Tooltip tooltip = new Tooltip(text);
        // NOT A CHILD OF THE ANCHOR, which is what the old engine did and what this engine cannot.
        //
        // A tooltip was an internal child of the thing it describes, so the cascade gave it colour and
        // font for free. Here nearly every anchor worth a tooltip is a composite with a SHADOW ROOT
        // and no slot -- a Button, a taskbar entry, a rail button -- and a light child of such a node
        // is never composed at all: no box, no paint, no promotion, and nothing anywhere reporting a
        // problem. It reads as the tooltip never firing.
        //
        // The document is where it goes instead. It is promoted to the top layer the moment it shows,
        // so its parent was never what positioned it; what is genuinely lost is the inherited colour
        // and font, which `ua/overlays.css` states on `tooltip` outright -- and the sheet was already
        // fighting that inheritance for `white-space`, on the argument that a tooltip is not part of
        // its anchor's text flow. It is a little more so now.
        tooltip.joinDocumentOf(anchor);

        // Listeners are attached exactly once, here, against a tooltip that is created in the same
        // breath. The earlier UIElement.setTooltip could be called repeatedly — and a
        // set(text)/set(null)/set(text) cycle silently attached a second pair every time.
        anchor.onMouseEnter.attachListener((el, event) -> tooltip.showAfterDelay(anchor), false, false);
        anchor.onMouseLeave.attachListener((el, event) -> tooltip.hide(), false, false);
        return tooltip;
    }

    /** Detaches a tooltip created by {@link #attach}, hiding it and removing it from its anchor.
     * The anchor's hover listeners become inert rather than being removed — they hold only this
     * instance, which no longer has anywhere to show. */
    public void detach() {
        hide();
        UIElement parent = parentElement();
        if (parent != null) parent.remove(this);
    }

    /** The internal text element, so callers can style or measure it without opening the tree. */
    public UIText getLabel() {
        return label;
    }

    /**
     * Sets what this tooltip says when the pointer is in none of its {@linkplain #addRegion regions}.
     *
     * <p>With no regions — which is every tooltip in the engine but the dock's tabs — that is simply
     * what it says, and this is the plain setter it has always been.</p>
     */
    public Tooltip setText(String text) {
        this.baseText = text == null ? "" : text;
        if (activeRegion == null) label.setText(baseText);
        return this;
    }

    /** What is <b>displayed</b> — a region's wording while one is active, else {@link #getBaseText}. */
    public String getText() {
        return label.text();
    }

    /** What this tooltip says outside every region. @see #setText */
    public String getBaseText() {
        return baseText;
    }

    // ── Regions ──────────────────────────────────────────────────────────────────

    /**
     * Says {@code text} instead while the pointer is inside {@code region}.
     *
     * <p>IntelliJ's editor tab is the shape this exists for: hovering the <b>icon</b> tells you what the
     * declaration is ("Final class"), and hovering anywhere else on the tab tells you where the file is.
     * One control, two things worth saying, decided by which part of it you are pointing at.</p>
     *
     * <h3>Why a region rather than a second tooltip on the sub-element</h3>
     *
     * <p>Two reasons, and the first is fatal on its own. <b>The sub-element is usually unhittable.</b> A
     * composite's parts are {@code setHitTest(false)} as a rule — click-focus targets the exact element
     * hit rather than the nearest focusable ancestor, so a hittable tab icon swallows the press that
     * selects the tab and the drag that starts from it. An unhittable element receives no
     * {@code mouseenter} at all, so a tooltip attached to it could never fire.</p>
     *
     * <p>And second, {@code Enter} does not bubble but <em>is</em> dispatched to every element in the
     * entered chain — so even where the part is hittable, a tooltip on the part and one on the whole
     * would both show, stacked over each other. Making the innermost win would mean arbitration between
     * tooltips; one tooltip that changes its wording needs none.</p>
     *
     * <p>Resolved on the placement ticker rather than from a {@code mousemove} listener, for the reason
     * placement itself is: a region can be reflowed out from under a stationary pointer, and a listener
     * would only notice the next time the mouse moved.</p>
     */
    public Tooltip addRegion(UIElement region, @Nullable String text) {
        Objects.requireNonNull(region, "region");
        // REPLACED, NEVER STACKED, and empty REMOVES.
        //
        // Both follow from the callers being pooled. A dock tab re-anchors its icon region every time the
        // icon is re-read, and a tree row is a different file every time the view recycles it -- so
        // appending would grow one dead region per refresh, each pointing at an element that is either
        // detached or now showing something else. There is no remove() to pair with an add here, so
        // "say nothing about this element" has to be spellable, and empty is how every other text setter
        // in the engine spells it.
        if (regions != null) regions.removeIf(existing -> existing.element == region);
        if (text == null || text.isEmpty()) {
            if (activeRegion != null && activeRegion.element == region) {
                activeRegion = null;
                label.setText(baseText);
            }
            return this;
        }
        if (regions == null) regions = new ArrayList<>(2);
        regions.add(new Region(region, text));
        return this;
    }

    /** Drops every region, so a rebuilt anchor does not accumulate them. */
    public Tooltip clearRegions() {
        regions = null;
        activeRegion = null;
        label.setText(baseText);
        return this;
    }

    /** How many regions this tooltip has — the only observable that a caller wired one. */
    public int regionCount() {
        return regions == null ? 0 : regions.size();
    }

    /**
     * Picks the deepest region under the pointer and puts its wording on the label.
     *
     * <p><b>Deepest, not first.</b> Regions nest — an icon inside a header inside a tab — and the answer
     * a person wants is the most specific thing they are pointing at, which is the same rule hit-testing
     * and {@code :hover} already follow. Order of registration is not that.</p>
     *
     * <p>Geometry only: {@link UIElement#containsScreenPoint} is a rounded-box containment test and does
     * not consult {@code hitTest}, which is precisely why an unhittable part can still have a region.</p>
     */
    private void resolveRegion() {
        if (regions == null) return;
        UIDocument window = document();
        if (window == null) return;
        ReadOnlyVec2f pointer = window.input().pointer();

        Region deepest = null;
        int deepestDepth = -1;
        for (Region region : regions) {
            UIElement element = region.element;
            // A region whose element has left the tree cannot contain anything -- and asking would read a
            // stale transform rather than answering false.
            if (element.document() != window) continue;
            if (!element.containsSurfacePoint(pointer.x(), pointer.y())) continue;
            int depth = element.depth();
            if (depth > deepestDepth) {
                deepestDepth = depth;
                deepest = region;
            }
        }

        if (deepest == activeRegion) return;
        activeRegion = deepest;
        label.setText(deepest == null ? baseText : deepest.text);
    }

    /** A sub-area of the anchor with wording of its own. @see #addRegion */
    private static final class Region {
        private final UIElement element;
        private final String text;

        private Region(UIElement element, String text) {
            this.element = element;
            this.text = text;
        }
    }

    /** A tooltip owns its label; it has no public content slot. */

    @Nullable
    public UIElement getAnchor() {
        return anchor;
    }

    // ── Show / hide ─────────────────────────────────────────────────────────

    /**
     * Promotes this tooltip and anchors it to {@code anchor}. Idempotent — calling it again while
     * already shown just re-anchors and raises.
     */
    public Tooltip showFor(UIElement anchor) {
        if (anchor == null || anchor.document() == null) return this;
        if (dragIsLive(anchor)) return hide();
        joinDocumentOf(anchor);
        // Whatever was being waited for, this supersedes it -- including the ordinary case where the
        // ticker itself is the caller.
        cancelPendingShow();
        this.anchor = anchor;

        setHidden(false);
        document().promote(this);
        // BEFORE placement, not after: the wording decides the box, and the box decides whether the
        // tooltip flips above its anchor. Resolving afterwards places the previous frame's text.
        resolveRegion();
        applyEmptiness();
        reposition();

        if (!placementTickerRunning) {
            placementTickerRunning = true;
            UIDocument window = document();
            // AFTER LAYOUT: placement reads this tooltip's own measured box -- see Popover.
            if (window != null) window.animation().afterLayout(this, this::tickPlacement);
        }
        return this;
    }

    /**
     * Shows this tooltip once the pointer has rested on {@code anchor} for {@code tooltip-delay}.
     *
     * <h3>Why a delay at all</h3>
     *
     * <p>A hover is not a request. The pointer crosses a dozen controls on the way to the one it wants,
     * and a tooltip on each of them is a box flickering over whatever you were reading — in a tab strip,
     * over the very tabs you are trying to look at. Waiting for the pointer to STOP is what separates
     * "passed over this" from "asked about this", which is why every desktop toolkit has this wait and
     * none of them sets it to zero.</p>
     *
     * <h3>...and why hiding has none</h3>
     *
     * <p>Not an oversight and not symmetry worth having. The delay exists to infer intent from
     * hesitation, and leaving carries no such ambiguity: the pointer is elsewhere, so the answer is no
     * longer about anything. A hide delay would leave a box over the control you just moved onto.</p>
     *
     * <p>Counted down on a frame ticker rather than against {@code System.nanoTime()}: the frame delta is
     * the same clock every other timed thing here advances on, it pauses when the window does, and it
     * sidesteps the arbitrary-origin trap that clock carries.</p>
     *
     * <p>A delay of zero — the property's initial value, so anything the user-agent sheet does not reach
     * — shows immediately, which is exactly what every caller got before this existed.</p>
     */
    public Tooltip showAfterDelay(UIElement anchor) {
        if (anchor == null || anchor.document() == null) return this;
        if (dragIsLive(anchor)) return hide();

        // JOINED AND STYLED BEFORE THE DELAY IS READ, and both halves are the point.
        //
        // A widget tooltips itself in its own constructor, where its anchor is in no document yet, so
        // `attach` cannot join and the first join happens here. `tooltipDelay()` is a CASCADED value:
        // on a node the cascade has never seen it answers the property's initial, which is 0 — read
        // as "no delay", so the FIRST hover of every tooltip in the application appeared instantly and
        // every hover after it waited properly. The old engine did not have this because a tooltip was
        // a CHILD of its anchor, in the tree and styled long before anything hovered it.
        joinDocumentOf(anchor);
        resolveStyleOnce();

        float delay = getStyle().getGeneralGroup().tooltipDelay();
        // `!(delay > 0)` rather than `delay <= 0`, which is FALSE for NaN -- so a NaN would be taken as a
        // real wait and counted down forever, and the tooltip would simply never appear. The engine has
        // paid for that exact comparison once already, in TextEditor.lineHeight.
        if (!(delay > 0f)) return showFor(anchor);

        pendingAnchor = anchor;
        pendingDelay = delay;
        if (!delayTickerRunning) {
            delayTickerRunning = true;
            UIDocument window = document();
            if (window != null) window.animation().every(this, this::tickDelay);
            else delayTickerRunning = false;
        }
        return this;
    }

    /**
     * Puts this tooltip in {@code anchor}'s document, if it is not already somewhere.
     *
     * <p>Called from BOTH {@link #attach} and {@link #showFor}, because {@code attach} is routinely
     * called while a screen is still being built and the anchor is in no document yet — every widget
     * that tooltips itself does so in its own constructor. Doing it only at attach time means every
     * such tooltip is silently never shown; doing it only at show time means one that COULD have been
     * placed early is not. Both, and the second is a no-op whenever the first worked.</p>
     */
    /**
     * Runs the cascade once, for a tooltip that has only just joined.
     *
     * <p>One pass, once per tooltip, on the frame it first joins — and only then, because it is
     * asking a question about a node the frame's own style pass could not have seen. It is called
     * from input dispatch, which runs after this frame's style and layout, so nothing this frame is
     * invalidated by it: what it produces is a value to read, and the box follows next frame with
     * everything else.</p>
     */
    private void resolveStyleOnce() {
        if (styleResolved) return;
        UIDocument host = document();
        if (host == null) return;
        styleResolved = true;
        host.calculateStyle(0f);
    }

    private void joinDocumentOf(UIElement anchor) {
        if (parent() != null) return;
        UIDocument host = anchor.document();
        if (host != null) host.append(this);
    }

    /**
     * Abandons a wait in progress. Idempotent, and safe when nothing is pending.
     *
     * <p>Folded into {@link #hide} rather than left to callers: a tooltip told to hide during its own
     * countdown would otherwise still appear afterwards, over an anchor the pointer left a second ago.</p>
     */
    public Tooltip cancelPendingShow() {
        pendingAnchor = null;
        pendingDelay = 0f;
        return this;
    }

    /** True while the pointer has entered but the wait has not elapsed — the only observable of it. */
    public boolean isShowPending() {
        return pendingAnchor != null;
    }

    /**
     * Whether a drag is running in {@code anchor}'s window — in which case no tooltip may show.
     *
     * <h3>Not an edge case: a drag pins the pointer to what it picked up</h3>
     *
     * <p>{@code startDrag} takes pointer capture, and the spec treats everything during capture as
     * inside the capturing element's boundary — that is what stops {@code :hover} flickering across
     * every element a drag crosses. The cost is that the source stays hovered for the whole gesture, so
     * its delay elapses <em>in mid-air</em> and the tip appears next to the thing you are carrying,
     * over the strip you are aiming at. A tab drag showed the dragged file's full path across the tabs
     * it was being dropped between.</p>
     *
     * <p>Every toolkit suppresses this, and the reason is not the overlap: a tooltip answers "what is
     * this", and while you are holding the thing the question is already answered. Asked here rather
     * than left to each widget — the stripe rail hid its own on mouse-down and no other consumer knew
     * it had to, which is the shape of rule that gets rediscovered once per widget.</p>
     *
     * <p>Checked at all three moments a tip can reach the screen: both entry points, and every frame of
     * {@link #tickPlacement} — a drag can begin while one is already up, which neither entry point
     * would ever be called again for.</p>
     */
    private static boolean dragIsLive(UIElement anchor) {
        if (anchor == null) return false;
        UIDocument window = anchor.document();
        return window != null && window.input().mode(Drag.class) != null;
    }

    /** Demotes and detaches from its anchor. The placement ticker drops itself on the next frame. */
    public Tooltip hide() {
        cancelPendingShow();
        this.anchor = null;
        document().demote(this);
        setHidden(true);
        return this;
    }

    public boolean isShown() {
        return anchor != null && document().isPromoted(this);
    }

    /**
     * Re-place once this element's own box is known.
     *
     * <p>{@link #showFor} runs before the promoted node has ever been laid out, so at that moment
     * this tooltip's width and height are both 0 — and flipping and clamping are decided by exactly
     * those. Without this hook the first frame is placed as if the tooltip were a point, and only
     * the next frame's ticker corrects it: a visible one-frame jump.</p>
     *
     * <p>Same shape as {@code UIText}, which also re-derives geometry after layout and pushes it back
     * at IMPORTANT origin. It settles for the same reason: {@code replaceOrPutCandidate} no-ops on an
     * unchanged value, so the extra pass stops re-dirtying the tree as soon as placement holds still.</p>
     */
    /**
     * Places the tip once its own box exists.
     *
     * <p>Was an {@code onLayoutChanged} override, which this engine has no counterpart for by design:
     * layout is one pass with no feedback into it. The post-layout hook is where a widget that must
     * READ its measured box goes, and it is registered per show rather than standing.</p>
     */
    private void placeAfterLayout() {
        if (isShown()) reposition();
    }

    /**
     * Re-runs placement against the anchor's current box.
     *
     * <p>The geometry — reading the anchor through the transform chain rather than its layout box,
     * flipping above when cramped, clamping into the containing block — lives in
     * {@link AnchoredPlacement}, extracted here when {@code Popover} became its second consumer. A
     * tooltip is just {@code Side.BOTTOM} with no offset.</p>
     */
    public void reposition() {
        // AGAINST WHATEVER IS SPEAKING. A region exists because a sub-area of the anchor means something
        // of its own, so the box has to point at the thing it is describing rather than at the element it
        // happens to be parented to. A dock TAB is small enough that the difference is a few pixels and
        // this went unnoticed there; a full-width tree ROW put "Final class" at the left edge of the
        // panel, a hundred pixels from the icon it was about.
        //
        // Re-read every frame rather than latched at show time, for the same reason the placement is:
        // the pointer moves from label to icon without ever leaving the anchor, and the ticker calls
        // resolveRegion() immediately before this.
        UIElement against = activeRegion == null ? anchor : activeRegion.element;
        AnchoredPlacement.place(this, against, side, gap);
    }

    /**
     * Counts a pending show down and fires it, then drops itself.
     *
     * <p>Separate from {@link #tickPlacement} because the two are live at opposite times — one before
     * the tooltip is shown and one only while it is — so a single ticker would spend every frame of a
     * visible tooltip asking about a wait that ended, and every frame of a wait re-placing a box that is
     * not on screen.</p>
     */
    private boolean tickDelay(float deltaSeconds) {
        {
            UIElement target = pendingAnchor;
            // Same rule as the placement ticker: an anchor that has gone sends no leave, and a wait
            // that fired against one would put a tip over whatever took its place.
            if (target == null || !target.isConnected()) {
                cancelPendingShow();
                delayTickerRunning = false;
                return false;
            }
            pendingDelay -= deltaSeconds;
            if (pendingDelay > 0f) return true;

            delayTickerRunning = false;
            // showFor clears the pending state itself, so this cannot re-enter or fire twice.
            showFor(target);
            return false;
        }
    }

    /** Keeps placement current while shown, then drops itself. Registration is idempotent
     * ({@code HashSet}-backed) but the flag avoids re-registering on every {@code showFor}. */
    private boolean tickPlacement(float deltaSeconds) {
        {
            if (!isShown()) {
                placementTickerRunning = false;
                return false;
            }
            // AN ANCHOR THAT HAS GONE CANNOT SEND A LEAVE. `hide()` hangs off the anchor's
            // onMouseLeave, and a node that leaves the tree emits nothing — so a tooltip whose anchor
            // was rebuilt stayed on screen for ever, and every rebuild stranded another. Three of them
            // were visible over one panel at once.
            if (anchor == null || !anchor.isConnected()) {
                hide();
                placementTickerRunning = false;
                return false;
            }
            // A DRAG CAN START UNDER A TIP THAT IS ALREADY UP, which neither show path would be called
            // again for. @see #dragIsLive
            if (dragIsLive(anchor)) {
                hide();
                placementTickerRunning = false;
                return false;
            }
            resolveRegion();
            // EVERY FRAME, because the pointer moves between regions without leaving the anchor: a row
            // whose icon speaks and whose label does not has to fall silent halfway across itself.
            applyEmptiness();
            reposition();
            return true;
        }
    }
}
