package com.crystalgui.widget.dnd;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.*;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;

import javax.annotation.Nullable;
import com.crystalgui.ui.service.Drag;

/**
 * The floating capsule that follows the cursor during a drag — icon and label, ready to use.
 *
 * <pre>
 * private final DragGhost dragGhost = new DragGhost();
 * // once, wherever this widget builds itself:
 * dragGhost.parkIn(this);
 * // on mouse-down, before startDrag:
 * dragGhost.follow(window, "crystalgui:folder", "Problems");
 * </pre>
 *
 * <h3>Why this exists</h3>
 *
 * <p>{@link UIDragController#setGhost} takes <b>any</b> element, deliberately — <i>"the input layer has no
 * business importing {@code ui.elements}, and a ghost is just some element that follows the cursor"</i>.
 * That is the right seam and it stays. What it is not is a <em>usable</em> API on its own: every caller
 * ends up writing the same forty lines, and two of them had — {@code ProjectFileTree} and
 * {@code StripeView}, comments included. The three rules below are each invisible when broken and none of
 * them is discoverable from the setter's signature.</p>
 *
 * <ol>
 *   <li><b>It has to be in the tree before a drag can show it.</b> The controller <em>promotes</em> an
 *       element into the top layer; it does not create one, and promotion needs an attached window. So a
 *       ghost built at drag time is a ghost that never appears.</li>
 *   <li><b>Out of flow and hidden must be written from Java, at IMPORTANT, at construction.</b> Not from
 *       the stylesheet — and this is the one that costs a session. {@code UIDocument.init()} runs its first
 *       layout <em>before any rule has matched</em>, and {@link UIText} latches once, on that first
 *       measurement, whether it sizes its own width. An in-flow ghost therefore latches to whatever box it
 *       happened to be parked in and keeps it forever: parked in a file panel it stretched to the panel's
 *       width and painted a stray blue bar; parked in a 20px rail it would clamp to 20px. No stylesheet
 *       rule can prevent it, because the damage is done before any stylesheet is consulted. IMPORTANT is
 *       also the origin the controller writes {@code display} at, so show/hide still take over cleanly.</li>
 *   <li><b>Register per drag, never once.</b> The controller drops its reference at the end of every drag,
 *       on purpose — a retained ghost once outlived the drag that registered it and reappeared on
 *       unrelated screens. {@link #follow} is therefore the call you make on mouse-down, every time.</li>
 * </ol>
 *
 * <h3>What it is not</h3>
 *
 * <p>Not a static {@code DragGhost.show(window, …)} that finds its own instance. That reads better at the
 * call site and needs somewhere to keep one ghost per window — a static map keyed by window, or a new
 * field on {@link UIDocument}. Neither is worth it for a saving of one field: {@link #parkIn} is also the
 * only statement of <b>who owns this element</b>, and a global would answer that question with "nobody".</p>
 */
public class DragGhost extends UIElement {

    public static final Name NAME = Name.of("dragghost");
    /**
     * How a ghost is placed relative to the pointer. Two genuinely different gestures, and neither
     * placement is right for the other.
     */
    public enum Anchor {
        /**
         * Keep the grab offset — the ghost sits exactly where the source was relative to the pointer.
         *
         * <p>Right when the ghost <em>is</em> the thing being moved, at the same size: a pill, a card, a
         * tab. Grabbing one by its corner and having it jump to centre-on-cursor is the classic tell of a
         * ghost positioned the lazy way.</p>
         */
        GRAB,
        /**
         * Sit just below and right of the pointer, ignoring where the source was grabbed.
         *
         * <p>Right when the ghost is a small stand-in for something much larger or for several things at
         * once — a full-width file row, or "3 items". Preserving the grab offset there places a two-inch
         * label a whole row-width to the left of the cursor, which reads as the ghost being detached from
         * the pointer rather than as fidelity to the grab. Every file manager anchors this way.</p>
         */
        CURSOR
    }

    /** One shared class, so a theme styles every ghost in the engine once. */
    public static final String GHOST_CLASS = "__drag-ghost__";

    /** {@code .__drag-ghost__::part(pre-icon)} in a sheet. */
    public static final String ICON_PART = "pre-icon";
    /** {@code .__drag-ghost__::part(label)}. */
    public static final String LABEL_PART = "label";

    /** On the ghost while its label sits to the <em>left</em> of the icon. @see #flipped */
    public static final String FLIPPED_CLASS = "__flipped__";

    /** On the ghost while it is carrying an icon but no label. @see #text */
    public static final String UNLABELLED_CLASS = "__unlabelled__";

    private final ShadowRoot shadow;
    private final UIElement icon = new UIElement();
    private final UIText label = new UIText("");

    public DragGhost() {
        super(NAME);
        addClass(GHOST_CLASS);
        // Never a drop target for its own drag. The controller sets this too, but only once something
        // registers the ghost -- and a ghost parked in a hittable widget is hittable until then.
        setHitTest(false);

        this.shadow = attachShadow();
        icon.set(Attribute.PART, ICON_PART);
        icon.setHitTest(false);
        label.set(Attribute.PART, LABEL_PART);
        shadow.append(icon);
        shadow.append(label);

        // RULE 2, and it must be here rather than in the stylesheet -- see the class note.
        //
        // HIDDEN IS THE ATTRIBUTE, NOT A `display` VALUE, and the difference is the whole of why no
        // drag in the application ever showed a ghost. The old engine hid with `display: none` at
        // IMPORTANT and showed with `display: flex` at the same origin -- one channel, flipped both
        // ways. The port kept the hiding half as an INLINE write and then showed the ghost with
        // `setDisplayed(true)`, which clears the `hidden` ATTRIBUTE and says nothing about the
        // cascade: the ghost came out promoted, unhidden and still resolving `display: none`, so the
        // box tree gave it no box for the other reason and there was nothing to draw. Every
        // observable said it was being shown.
        //
        // The attribute is also the stronger guarantee rule 2 is after: the box tree honours it
        // unconditionally, above any sheet, where an INLINE write is merely hard to outrank.
        setDisplayed(false);
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE));
    }

    /**
     * Puts this ghost in {@code host}'s tree, once — rule 1.
     *
     * <p>Inside the drag source's own widget is the recommended spot and not merely a convenient one:
     * {@code UIDragController.isSelfOrInsideSource} then excludes it from drop targeting for free, on top
     * of the {@code hitTest(false)} above.</p>
     *
     * <p>Idempotent, so calling it from a constructor and again from a rebuild is safe.</p>
     */
    public DragGhost parkIn(UIElement host) {
        if (host == null || getParent() == host) return this;
        // A LIGHT child of the host, not one of its parts: the ghost belongs to whoever
        // parked it and the host has no idea it is there. Putting it in the host's shadow
        // tree would make it the host's own structure, which it is not.
        host.append(this);
        return this;
    }

    /**
     * Shows {@code text} (and {@code iconName}, when there is one) under the cursor for this drag.
     *
     * <p>Call it on mouse-down, before {@code startDrag} — rule 3. A null or blank icon name simply leaves
     * the ghost a label, which is what a multi-selection drag wants: <i>"3 items"</i> has no one icon.</p>
     *
     * <p>Placed by {@link #anchoredBy}, which is a property of the widget rather than of the drag.</p>
     */
    public DragGhost follow(@Nullable UIDocument window, @Nullable String iconName, String text) {
        label.setText(text == null ? "" : text);
        setIcon(iconName);
        if (window == null) return this;
        // THE LIVE DRAG, asked for by type. The old controller was a singleton with a setGhost of its
        // own; a Drag is an InputMode now, so the ghost is handed to the gesture that will carry it.
        // Null when nothing is dragging, which is the ordinary case for the mouse-DOWN this is called
        // from -- rule 3 says to call it BEFORE the drag starts, and `Drag.start` re-reads the ghost.
        Drag live = window.input().mode(Drag.class);
        if (live != null) live.withGhost(this, ghostOffsetX(), ghostOffsetY());
        // ...AND OTHERWISE OFFERED, which is the ordinary case and used to be the end of it: this is
        // called BEFORE the drag starts (rule 3), so there is nothing to hand it to and the line above
        // did nothing at all. `Drag.start` claims the offer.
        else if (anchor == Anchor.GRAB) window.input().offerGhost(this);
        else window.input().offerGhost(this, 0f, 0f);
        return this;
    }

    private Anchor anchor = Anchor.CURSOR;

    /**
     * Where the cursor sits within the ghost, in the ghost's own space — what {@link Anchor} means,
     * resolved.
     *
     * <p>{@code CURSOR} puts the ghost's top-left just under the pointer, so the offset is zero;
     * {@code GRAB} keeps the offset the press landed at, which is the ghost's own centre once it has
     * been measured. Zero before the first layout, which is correct rather than approximate: an
     * unmeasured ghost has no centre to sit on.</p>
     */
    private float ghostOffsetX() {
        if (anchor != Anchor.GRAB) return 0f;
        return box() == null ? 0f : box().width() / 2f;
    }

    private float ghostOffsetY() {
        if (anchor != Anchor.GRAB) return 0f;
        return box() == null ? 0f : box().height() / 2f;
    }

    /**
     * How this ghost sits relative to the pointer. {@link Anchor#CURSOR} by default.
     *
     * <p><b>The choice is about what the ghost stands for, not about taste</b>, and both answers are wrong
     * in the other's case:</p>
     *
     * <ul>
     *   <li>{@code GRAB} keeps the offset the press landed at, so the ghost sits exactly where the source
     *       visually was. Right when the ghost <em>is</em> the thing being moved, at roughly its own size —
     *       a stripe button, a tab, a card. Anything else and the icon visibly jumps out from under the
     *       cursor the instant the drag starts, which reads as the ghost being a different object.</li>
     *   <li>{@code CURSOR} sits just below and right of the pointer instead. Right when the ghost is a
     *       small stand-in for something much larger or for several things — a full-width file row, or
     *       "3 items". Preserving the grab offset there puts a short label a whole row-width to the left of
     *       the cursor, which reads as the ghost having come unstuck.</li>
     * </ul>
     */
    public DragGhost anchoredBy(Anchor value) {
        this.anchor = value == null ? Anchor.CURSOR : value;
        return this;
    }

    /** @see #follow(UIDocument, String, String) */
    public DragGhost follow(@Nullable UIDocument window, String text) {
        return follow(window, null, text);
    }

    /**
     * Rewrites the label mid-drag.
     *
     * <p>For a ghost whose text is a <b>destination</b> rather than a name — IntelliJ's stripe drag says
     * "Move to Bottom Left" and rewrites it as the pointer crosses between rails and halves. The thing
     * doing the rewriting is the target under the pointer, not the source that started the drag, which is
     * why this is public and why the target reaches the live ghost through
     * {@code UIDragController.getGhost()} rather than through its own field: with two rails there is one
     * ghost and it belongs to whichever of them the drag began on.</p>
     */
    public DragGhost text(@Nullable String value) {
        boolean blank = value == null || value.isEmpty();
        label.setText(blank ? "" : value);
        // BLANK MEANS GONE, not an empty box. "No destination" is a real state -- the middle of the
        // window, where a drop is refused -- and IntelliJ shows the bare icon there. An empty capsule
        // would read as a destination whose name failed to load.
        if (blank && !hasClass(UNLABELLED_CLASS)) addClass(UNLABELLED_CLASS);
        else if (!blank && hasClass(UNLABELLED_CLASS)) removeClass(UNLABELLED_CLASS);
        return this;
    }

    /**
     * Puts the label on the <b>left</b> of the icon instead of the right.
     *
     * <h3>The label moves; the icon never does</h3>
     *
     * <p>This is why the label is positioned out of flow rather than being an ordinary flex sibling. The
     * ghost's box is the icon and nothing else, so {@link Anchor#GRAB} keeps that
     * icon exactly under the point of the button you took hold of — for the whole drag, at every edge of
     * the window. A label in flow would grow the box, and then anything that kept the box on screen would
     * be moving the icon off the cursor to do it.</p>
     *
     * <p>That was the first attempt, and it was wrong in a way worth recording: the box was clamped to the
     * window, so approaching the right-hand rail slid the icon left, out from under the pointer, while the
     * label stayed put. Exactly backwards — the icon is the thing being carried and the label is the
     * annotation, so the annotation is what gets out of the way.</p>
     */
    public DragGhost flipped(boolean value) {
        if (value && !hasClass(FLIPPED_CLASS)) addClass(FLIPPED_CLASS);
        else if (!value && hasClass(FLIPPED_CLASS)) removeClass(FLIPPED_CLASS);
        return this;
    }

    /**
     * Swaps the glyph, or clears it.
     *
     * <p>Cleared to {@link CgUiDrawable#EMPTY} rather than by detaching the slot: the ghost is reused
     * across drags, and an element removed on one drag and re-added on the next would be built into a
     * tree that is about to be laid out promoted — which is the same first-measurement hazard rule 2 is
     * about, arriving one drag late instead of one frame late.</p>
     */
    private void setIcon(@Nullable String iconName) {
        CgUiDrawable glyph = CgUiDrawable.EMPTY;
        if (iconName != null && !iconName.isEmpty()) {
            CgUiSvg resolved =
                    CgUiSvg.ofIcon(iconName);
            if (resolved != null) glyph = resolved;
        }
        CgUiDrawable applied = glyph;
        StyleGroup.inlinePipeline(icon.getStyle().getGeneralGroup(), g -> g.overlay(applied));
        boolean shown = applied != CgUiDrawable.EMPTY;
        StyleGroup.inlinePipeline(icon.getStyle().getLayoutGroup(),
                l -> l.display(shown ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

}
