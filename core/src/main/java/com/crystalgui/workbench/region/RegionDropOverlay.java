package com.crystalgui.workbench.region;

import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.tree.UITreeTraversal;

import com.crystalgui.workbench.stripe.StripeRail;
import com.crystalgui.workbench.stripe.StripeView;
import com.crystalgui.workbench.view.ViewContainer;
import dev.vfyjxf.taffy.style.TaffyPosition;

import org.joml.Vector2f;

import javax.annotation.Nullable;

/**
 * The blue rectangle that shows where a dragged tool window would land, and the label that names it.
 *
 * <h3>One target for the whole workbench, not one per rail</h3>
 *
 * <p>This is the shape the first attempt got wrong. Accepting drops on the {@link StripeView}s meant you
 * had to land a drag on a twenty-pixel stripe — aiming at the <em>control</em> rather than at the
 * <em>place</em>. IntelliJ bands the entire window: hover anywhere in the left third and the sidebar lights
 * up. So there is one drop target, it sits over everything, and {@link RegionDropZones} answers where a
 * point means.</p>
 *
 * <p>{@code DragEvent.Over} bubbles, which is what makes that a single listener rather than one per thing
 * a pointer might cross. It is attached to the workbench's content box; anything inside it — a rail, an
 * editor, a file tree row — reports upward.</p>
 *
 * <h3>Unhittable, and that is not optional</h3>
 *
 * <p>An overlay covering the workbench would otherwise <em>be</em> the drop target for its own drag, and
 * more than that: it would swallow every click in the application. {@code setHitTest(false)} applies to the
 * whole subtree, which is exactly what is wanted here — this element is a picture, and nothing in it is
 * ever pressed.</p>
 *
 * <h3>Shown by opacity, never by {@code display}</h3>
 *
 * <p>The same choice {@code DockGroup}'s drop preview makes. A hidden-by-{@code display} overlay has no
 * box, so the frame it comes back has no measurement to place it from; keeping the box alive and fading it
 * means the rectangle is correct on the frame it appears rather than on the one after.</p>
 */
public class RegionDropOverlay extends UINode {
    /** The overlay that lights where a drop would land. */
    public static final Name NAME = Name.of("regiondropoverlay");


    /** The full-size, unhittable layer. */
    public static final String OVERLAY_CLASS = "__region-drop-overlay__";

    /** The lit rectangle inside it — what a theme colours. */
    public static final String PREVIEW_CLASS = "__region-drop-preview__";

    private final Workbench workbench;
    private final UINode preview = new UINode();

    /** The slot the pointer last resolved to, so a drop uses what the highlight promised. */
    @Nullable
    private RegionDropZones.Target target;

    /**
     * How many stripe drops have been handled.
     *
     * <p>Diagnostic, and it earned its place: "the overlay never heard the drag" and "it heard the drag
     * and the move did nothing" are indistinguishable from the outcome alone, and the drag plumbing has
     * four seams between the press and the placement write.</p>
     */
    private int dropsSeen;

    /** How many stripe drops this overlay has handled. For tests and diagnostics. */
    public int dropsSeen() {
        return dropsSeen;
    }

    /**
     * Where a live stripe drag is currently aiming — the slot, or {@code null} for nowhere, plus the
     * pointer that resolved it.
     *
     * <p>The pointer travels with the slot because the listeners need it and cannot recover it: the drag
     * source has pointer capture, so a rail the drag has merely crossed is told nothing at all.</p>
     */
    public record Aim(@Nullable RegionDropZones.Target slot, float screenX, float screenY) {
    }

    /**
     * Announced whenever the aim changes — including to nowhere, which is what a drag over the editor and
     * the end of a drag both mean.
     *
     * <p><b>Announced rather than pushed.</b> This element resolves the slot and paints the rectangle;
     * what a <em>rail</em> does about it — open a gap, rewrite its ghost's label — is the rail's business,
     * and it used to be done from here by looping over {@code workbench.stripes()} and reaching through
     * {@code UIDragController.getGhost()} for an element that belongs to one of them. Two objects
     * operating each other's parts, which is the coupling this codebase keeps replacing with a signal.</p>
     */
    public final Signal.Value<Aim> onDidChangeAim = new Signal.Value<>();

    /** What the highlight is currently promising, or null when nothing is. For tests and diagnostics. */
    @Nullable
    public RegionDropZones.Target currentTarget() {
        return target;
    }

    public RegionDropOverlay(Workbench workbench) {
        super(NAME);
        this.workbench = workbench;
        addClass(OVERLAY_CLASS);
        setHitTest(false);
        // Covers its host exactly. From Java rather than the sheet for the reason every promoted-ish box
        // here does it: the first layout runs before any rule has matched, and an overlay that is briefly
        // in flow shoves the workbench's real content sideways for a frame.
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(0f).top(0f).right(0f).bottom(0f));
        preview.addClass(PREVIEW_CLASS);
        preview.setHitTest(false);
        StyleGroup.inlinePipeline(preview.getStyle().getGeneralGroup(), g -> g.opacity(0f));
        append(preview);
    }

    /**
     * Listens on {@code host} — the box this overlay covers.
     *
     * <p>Separate from the constructor because the listeners are about the <em>host's</em> events, and the
     * overlay is built as a field before there is a host to attach to.</p>
     */
    public void listenOn(UINode host) {
        host.events.getGroup(DragEvent.Over.class).attachListener((element, event) -> {
            if (!(event.getPayload() instanceof StripeView.StripeDrag dragged)) return;
            RegionDropZones.Target resolved = resolve(host, event.getPosition());
            show(host, resolved);
            onDidChangeAim.emit(new Aim(resolved, event.getPosition().x(), event.getPosition().y()));
            // Rejection is the DEFAULT, and the centre rejects: a stripe drag released there is a
            // TEAR-OUT, and the tear-out is not this overlay's to perform. Accepting here was tried and
            // is wrong for a reason worth keeping -- it covers only the part of the screen this overlay
            // happens to cover, so releasing over the desktop, or over another window, still did nothing.
            // The drag SOURCE is the one thing present at every ending, so that is where it lives now.
            // See StripeView's onDragEnd.
            if (resolved != null) event.preventDefault();
            // BUBBLE = TRUE, and this is the whole reason one listener can cover the workbench.
            //
            // attachListener's two booleans are ADDITIVE, not a mode selector: the target phase is always
            // subscribed, and `false, false` means TARGET ONLY. Over is dispatched to whatever is
            // geometrically under the pointer -- a tree row, an editor, a rail button -- and `content` is
            // never that thing, so it heard nothing at all. No highlight, no label, and a drop that could
            // not be accepted because preventDefault was never reached.
        }, false, true);

        host.events.getGroup(DragEvent.Drop.class).attachListener((element, event) -> {
            if (!(event.getPayload() instanceof StripeView.StripeDrag dragged)) return;
            dropsSeen++;
            RegionDropZones.Target resolved = target;
            int index = resolved == null ? -1
                    : workbench.stripe(StripeRail.of(resolved.region(), resolved.side())).insertionIndex();
            hide();
            // A drop this overlay never accepted cannot arrive here at all -- so `resolved` is non-null
            // in practice, and the guard is kept because a Drop is dispatched by the controller and this
            // is not the place to reason about that. The tear-out is the drag source's; see StripeView.
            if (resolved == null) return;
            // THE SLOT THE HIGHLIGHT PROMISED, re-read from the field rather than recomputed from the drop
            // position. They are the same point in every ordinary case and differ in the one that matters:
            // a drop event arriving after a layout change would resolve against the new geometry, and the
            // tool window would land somewhere the user was never shown.
            // A DROP INTO AN EMPTY HALF OPENS IT; a drop into an occupied one does not.
            //
            // Dragging a button deliberately does not toggle its tool window -- a drag means "put it
            // there", not "show it" -- but an empty destination is the one case where honouring that
            // literally does nothing you can see: an invisible panel moved into an invisible slot, with
            // the only evidence being a button that changed rails.
            //
            // THE HALF, not the region. The two differ exactly when a region is split, which is now the
            // ordinary case: dropping into the sidebar's empty lower half while Project holds the upper
            // one is a drop into something closed, and asking the region flatly answers "occupied" and
            // leaves the drop invisible. That is the report this came from.
            //
            // Asked BEFORE the move, because moveTo reopens a tool window that was already showing -- ask
            // afterwards and the answer is "occupied" precisely when we put something there.
            RegionHost target = workbench.regions().host(resolved.region());
            boolean halfWasEmpty = target == null || target.showing(resolved.side()) == null;

            workbench.toolWindowManager()
                    .moveTo(dragged.typeId(), resolved.region(), resolved.side(), index);
            if (halfWasEmpty) openAndFocus(dragged.typeId());
        }, false, true);   // Drop bubbles too -- see the Over handler.

        // Leaving the workbench entirely -- the drag went to the title bar, or off the window.
        //
        // TARGET ONLY here, and correctly so: Leave does NOT bubble, it is chain-dispatched to every
        // element being left. So `content` is told directly when the pointer leaves it, and asking for
        // the bubble phase would subscribe to something that never fires.
        host.events.getGroup(DragEvent.Leave.class).attachListener((element, event) -> {
            if (!(event.getPayload() instanceof StripeView.StripeDrag)) return;
            hide();
            onDidChangeAim.emit(new Aim(null, event.getPosition().x(), event.getPosition().y()));
        }, false, false);
    }

    /** Which slot a pointer at this screen point means, in the host's local space. */
    @Nullable
    private RegionDropZones.Target resolve(UINode host, ReadOnlyVec2f position) {
        Vector2f local = host.toLocal(position.x(), position.y());
        Box box = host.box();
        if (box == null) return null;
        float width = box.width();
        float height = box.height();
        // LOCAL COORDINATES ARE OFFSETS HERE, and the comment this replaces said the opposite -- truly,
        // of the old engine, where `screenToLocal` answered a point in the space the host's own x/y live
        // in and the origin had to come off. M6.1 moved that origin to zero, so the subtraction that
        // made it right is now what would make it wrong: every band would be measured from twice the
        // host's offset into the window.
        float x = local.x();
        float y = local.y();
        return RegionDropZones.forPoint(x, y, width, height,
                leftBand(host), rightBand(host), bottomBand(host));
    }

    /**
     * How far in from an edge that region's band reaches, or {@code 0} when the region is hidden.
     *
     * <h3>The region's EDGE, not its width</h3>
     *
     * <p>The sidebar does not start at the workbench's left edge — the left rail is in front of it. Using
     * the region's width alone puts the band twenty pixels short of where the sidebar visibly ends, so a
     * strip down its right-hand side looks like the sidebar and resolves as the editor. Measuring to the
     * edge makes the band and the thing it stands for the same rectangle.</p>
     *
     * <p>Zero when hidden rather than a guess, because {@link RegionDropZones} is where that default
     * belongs: it is the class that knows a band must exist even when the region does not, and splitting
     * the decision across two files is how they end up disagreeing about a closed sidebar.</p>
     */
    private float leftBand(UINode host) {
        RegionHost region = visible(DockRegion.SIDEBAR);
        if (region == null) return 0f;
        Box hostBox = host.box();
        if (hostBox == null || region.box() == null) return 0f;
        // The region's origin IN THE HOST'S SPACE -- `Box.x()` is parent-relative, so subtracting two
        // boxes' raw offsets only means anything when they share a parent. The same conversion the
        // right and bottom bands beside this one use.
        return Box.originIn(region.box(), hostBox).x() + region.box().width();
    }

    /** @see #leftBand */
    private float rightBand(UINode host) {
        RegionHost region = visible(DockRegion.AUXILIARY);
        if (region == null) return 0f;
        Box hostBox = host.box();
        if (hostBox == null) return 0f;
        // The region's origin IN THE HOST'S SPACE. `Box.x()` is parent-relative, so subtracting two
        // boxes' raw x only means anything when they share a parent -- `originIn` is the conversion
        // that also carries the intervening transforms and scrolls.
        return hostBox.width() - Box.originIn(region.box(), hostBox).x();
    }

    /** @see #leftBand */
    private float bottomBand(UINode host) {
        RegionHost region = visible(DockRegion.PANEL);
        if (region == null) return 0f;
        Box hostBox = host.box();
        if (hostBox == null) return 0f;
        return hostBox.height() - Box.originIn(region.box(), hostBox).y();
    }

    @Nullable
    private RegionHost visible(DockRegion region) {
        RegionHost host = workbench.regions().host(region);
        return host == null || host.isEmpty() ? null : host;
    }

    /**
     * Opens a tool window in the region it was just dropped into, and puts focus inside it.
     *
     * <p><b>{@code requestPointerFocus}, never {@code requestFocus}.</b> The two differ in exactly one way
     * that matters here: the programmatic one <em>rings</em>, because {@code :focus-visible} exists to
     * outline keyboard focus and not clicks. This focus is the end of a mouse gesture, so ringing the
     * panel's first control would be the noise that pseudo-class was added to remove.</p>
     */
    private void openAndFocus(String typeId) {
        workbench.showPanel(typeId);
        ViewContainer container = workbench.toolWindowManager().containerOf(typeId);
        UIDocument window = document();
        if (container == null || window == null) return;
        UINode focusable = window.focus().firstFocusableIn(container);
        if (focusable != null) window.focus().requestPointerFocus(focusable);
    }

    /** Lights the rectangle {@code slot} would occupy, or clears it when there is none. */
    private void show(UINode host, @Nullable RegionDropZones.Target slot) {
        this.target = slot;
        if (slot == null) {
            hide();
            return;
        }
        float[] rect = RegionDropZones.previewRect(slot, regionRect(host, slot.region()));
        StyleGroup.inlinePipeline(preview.getStyle().getLayoutGroup(), l -> l
                .positionType(TaffyPosition.ABSOLUTE)
                .left(rect[0]).top(rect[1])
                .width(rect[2]).height(rect[3]));
        StyleGroup.inlinePipeline(preview.getStyle().getGeneralGroup(), g -> g.opacity(1f));
    }

    /**
     * A region's box in {@code host}'s local space — its real one when it is open, a rail-inset band when
     * it is not.
     *
     * <p>The real box is what stops the highlight covering the rail: the sidebar starts <em>after</em> the
     * left stripe, and the band deliberately does not, because hovering the rail has to target the region
     * behind it. Two rectangles, two jobs.</p>
     */
    private float[] regionRect(UINode host, DockRegion region) {
        Box hostBox = host.box();
        if (hostBox == null) return new float[]{0f, 0f, 0f, 0f};
        RegionHost open = visible(region);
        if (open != null && open.box() != null) {
            // `Box.x()` is parent-relative, so subtracting two boxes' raw offsets only means anything
            // when they share a parent. `originIn` is the conversion, and it carries the intervening
            // transforms and scrolls a subtraction never did.
            var origin = Box.originIn(open.box(), hostBox);
            return new float[]{origin.x(), origin.y(), open.box().width(), open.box().height()};
        }
        return RegionDropZones.fallbackRect(region, hostBox.width(), hostBox.height(),
                leftBand(host), rightBand(host), bottomBand(host), railInset());
    }

    /**
     * How wide a rail is, so a hidden region's preview starts where the region would.
     *
     * <p>Measured rather than assumed: the rail's width is a stylesheet decision, and a theme that widens
     * it would otherwise leave the preview overlapping it by exactly the difference.</p>
     */
    private float railInset() {
        for (StripeView stripe : workbench.stripes()) {
            Box box = stripe.box();
            float width = box == null ? 0f : box.width();
            if (width > 0f) return width;
        }
        return 0f;
    }

    /**
     * Clears the highlight, whatever the drag did.
     *
     * <h3>Called from the drag's own ending, not only from a Drop</h3>
     *
     * <p>This is the one call guaranteed to run. A stripe drag can end five ways — dropped on a slot,
     * dropped on the editor, released off-window, cancelled with Escape, or cancelled because the drop
     * handler detached the source — and only the first goes through {@link DragEvent.Drop}. Hiding there
     * and relying on {@link DragEvent.Leave} for the rest is what left the region lit <em>after</em> the
     * tool window had already moved into it: nothing was still over the workbench to be left, so no Leave
     * was ever dispatched to it.</p>
     *
     * <p>{@code DragListener.onDragEnd} and {@code onDragCancel} between them cover every ending, which is
     * exactly what {@code pointercancel} exists to guarantee. Idempotent, so the Drop path calling it first
     * costs nothing.</p>
     */
    public void clear() {
        hide();
        onDidChangeAim.emit(new Aim(null, 0f, 0f));
    }

    /**
     * Clears the highlight.
     *
     * <p><b>Zeroed as well as faded</b>, which is what {@code DockGroup.hideDropPreview} does and is not
     * belt-and-braces. A box at {@code opacity: 0} is still a box: it keeps its rect, and anything that
     * paints outside the opacity layer — an outline — has somewhere to paint. Fading alone left the
     * region lit after a drop had already moved the tool window into it.</p>
     *
     * <p>Kept in the tree rather than removed: taking it out and putting it back is a structural change to
     * a subtree a drag is live over, and it is the cheaper of the two anyway.</p>
     */
    private void hide() {
        target = null;
        StyleGroup.inlinePipeline(preview.getStyle().getLayoutGroup(), l -> l
                .positionType(TaffyPosition.ABSOLUTE)
                .left(0f).top(0f).width(0f).height(0f));
        StyleGroup.inlinePipeline(preview.getStyle().getGeneralGroup(), g -> g.opacity(0f));
    }
}
