package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.ui.elements.UIText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * The line along the bottom of the workbench — IntelliJ's status bar, VS Code's {@code STATUSBAR_PART}.
 *
 * <h3>Model and view, named so</h3>
 *
 * <p>{@link StatusBar} is the <b>service</b>: items keyed per writer, replaced rather than accumulated,
 * silent when unchanged. This is the <b>view</b>. Two classes called {@code StatusBar} in two packages
 * would compile and read as a mistake forever after, so the view carries the suffix.</p>
 *
 * <h3>It renders; it does not compute</h3>
 *
 * <p>Everything here is a status item somebody else wrote, or the keying is pointless. This class holds no
 * idea of what a caret position is, when a file became read-only, or which encoding is in use — it asks
 * {@link StatusBar#items()} and lays them out. The one exception is {@link #breadcrumbs()}, which is a
 * widget rather than an item because a trail is clickable and structured; the host sets its trail.</p>
 *
 * <h3>Two ends, from the model</h3>
 *
 * <p>{@link StatusBar.Align} decides which group an item lands in. The split is real: the left half is read
 * as prose ("created notes.txt"), the right half is glanced at in a fixed place ("Ln 51, Col 39", "CRLF",
 * "UTF-8"). A spacer between them takes the slack, so the right group sits against the trailing edge at
 * every width without either group being positioned.</p>
 *
 * <h3>Slots are updated in place, never rebuilt</h3>
 *
 * <p>The engine's rule: <b>a widget must never rebuild the elements it is being clicked on</b>. Status
 * items are written from per-frame paths — the shader graph's line-owner readout fires on every caret move
 * — so a rebuild per change would be discarding and recreating elements continuously. An item keeps one
 * {@link UIText} for as long as it exists; only appearing and disappearing touches the tree.</p>
 */
public class StatusBarView extends UIElement {

    public static final String BAR_CLASS = "__status-bar__";
    /** The prose half, and where {@link #breadcrumbs()} sits. */
    public static final String LEFT_CLASS = "__left__";
    /** The glance half. */
    public static final String RIGHT_CLASS = "__right__";
    /** Takes the slack between the two groups. @see StatusBarView */
    public static final String SPACER_CLASS = "__spacer__";
    /** One rendered item. */
    public static final String ITEM_CLASS = "__status-item__";

    /**
     * On the leading item of each group, so the sheet can draw a divider before every item <em>but</em>
     * that one.
     *
     * <p>Carried as a class because the selector engine has no {@code :first-child} — {@code :nth-child}
     * and the sibling combinators are deliberately unimplemented. A view that knows its own order is the
     * substitute, and it is the same substitute internal-child classes already are for pseudo-elements.</p>
     */
    public static final String FIRST_CLASS = "__first__";

    /**
     * The rule drawn before every item but the leading one.
     *
     * <p><b>A real element, not a CSS border</b>, and that is forced rather than chosen: the paint path
     * reads {@code layout.border().left} as <em>the</em> border width and strokes a uniform box, so
     * asymmetric borders are not modelled — a {@code border-width-left} drew a rectangle around every
     * readout instead of a rule between two. {@code Breadcrumbs} already spells its separators as elements
     * for the same reason.</p>
     */
    public static final String SEPARATOR_CLASS = "__status-sep__";

    private final UIElement leftGroup = new UIElement();
    private final UIElement rightGroup = new UIElement();
    private final UIElement spacer = new UIElement();
    private final Breadcrumbs breadcrumbs = new Breadcrumbs();

    /** One live slot per item id — see the class note on why these are not rebuilt. */
    private final Map<String, UIText> slots = new HashMap<>();

    /**
     * One tooltip per slot, attached once and then only re-texted.
     *
     * <p>{@code Tooltip.attach} adds a hover listener pair every time it is called and {@code detach}
     * leaves them inert rather than removing them, so attach/detach cycling silently accumulates
     * listeners — its own javadoc records that. Attaching with the slot and updating the text instead
     * sidesteps it entirely.</p>
     */
    private final Map<String, Tooltip> tips = new HashMap<>();

    /** One divider per item, living immediately before it. @see #SEPARATOR_CLASS */
    private final Map<String, UIElement> separators = new HashMap<>();

    /** Held so the view stops listening when it leaves the tree; the service outlives every view of it. */
    private Connection subscription;

    public StatusBarView() {
        addClass(BAR_CLASS);
        // NOT markAsInternal(). Whether this part is internal to its host is the host's decision — the
        // shell adds it with addInternalChild — and stamping it here would recurse over a subtree whose
        // own slots are added and removed publicly, which removeChild silently refuses.

        leftGroup.addClass(LEFT_CLASS);
        rightGroup.addClass(RIGHT_CLASS);
        spacer.addClass(SPACER_CLASS);
        // Nothing in the bar takes the pointer except the breadcrumbs, which install their own handling.
        // Without this a press on the bar's background lands on a label and goes nowhere visible, but it
        // still moves focus off whatever the user was typing in.
        spacer.setHitTest(false);

        leftGroup.addChild(breadcrumbs);
        addInternalChild(leftGroup);
        addInternalChild(spacer);
        addInternalChild(rightGroup);

        refresh();
    }

    /**
     * The path shown at the leading edge. The host sets the trail; this class never derives one.
     *
     * @see Breadcrumbs#setTrail
     */
    public Breadcrumbs breadcrumbs() {
        return breadcrumbs;
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /**
     * Subscribes while attached, and only while attached.
     *
     * <p>{@link StatusBar} is static and outlives every view of it, so a view that never unsubscribed would
     * be kept alive by the service — along with its elements and everything they reference — for the rest
     * of the process. The same leak {@code ListView} guards against by disposing when it leaves the tree.</p>
     */
    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (getAttachedWindow() != null) {
            if (subscription == null) {
                subscription = StatusBar.onDidChange.connect(ignored -> refresh());
                refresh();
            }
        } else if (subscription != null) {
            subscription.disconnect();
            subscription = null;
        }
    }

    /** Brings the rendered slots in line with the service. Cheap when nothing changed. */
    public void refresh() {
        List<StatusBar.Item> items = StatusBar.items();

        Map<String, StatusBar.Item> live = new LinkedHashMap<>();
        for (StatusBar.Item item : items) live.put(item.id(), item);

        // GONE FIRST, so an id that moved from one group to the other does not briefly exist twice.
        for (String id : new ArrayList<>(slots.keySet())) {
            if (live.containsKey(id)) continue;
            UIText slot = slots.remove(id);
            slot.removeSelf();
            UIElement separator = separators.remove(id);
            if (separator != null) separator.removeSelf();
            tips.remove(id);
        }

        for (StatusBar.Item item : items) {
            UIText slot = slots.get(item.id());
            UIElement wanted = item.align() == StatusBar.Align.RIGHT ? rightGroup : leftGroup;
            if (slot == null) {
                slot = new UIText(item.text());
                slot.addClass(ITEM_CLASS);
                // HIT-TESTABLE, unlike the spacer: a tooltip is driven by mouseenter/mouseleave, and
                // setHitTest(false) applies to the whole subtree, so an unhittable label never hovers.
                slots.put(item.id(), slot);
                // BEFORE the slot, and added in the same breath, so the pair can never come apart.
                UIElement separator = new UIElement();
                separator.addClass(SEPARATOR_CLASS);
                separator.setHitTest(false);
                separators.put(item.id(), separator);
                wanted.addChild(separator);
                wanted.addChild(slot);
            } else {
                // IN PLACE. setText no-ops when the string is unchanged, so an item rewritten with the
                // same value every frame costs one comparison.
                slot.setText(item.text());
                // Re-parented only when the alignment actually moved -- addChild on the current parent
                // would throw, and removing to re-add would discard the element this exists to keep.
                if (slot.getParent() != wanted) {
                    slot.removeSelf();
                    UIElement separator = separators.get(item.id());
                    if (separator != null) {
                        separator.removeSelf();
                        wanted.addChild(separator);
                    }
                    wanted.addChild(slot);
                }
            }
            applyTooltip(item, slot);
        }
        markLeadingItems();
    }

    /** @see #tips */
    private void applyTooltip(StatusBar.Item item, UIText slot) {
        Tooltip tip = tips.get(item.id());
        if (tip == null) {
            if (item.tooltip() == null) return;
            tips.put(item.id(), Tooltip.attach(slot, item.tooltip()));
            return;
        }
        if (item.tooltip() == null) {
            // Emptied rather than detached, for the reason above. An empty tooltip has nothing to draw.
            tip.hide();
            tip.setText("");
            return;
        }
        tip.setText(item.tooltip());
    }

    /** Stamps the first item in each group, so the sheet knows where not to draw a divider. */
    private void markLeadingItems() {
        markLeadingItem(leftGroup);
        markLeadingItem(rightGroup);
    }

    @Nullable
    private UIElement separatorFor(UIElement slot) {
        for (Map.Entry<String, UIText> entry : slots.entrySet()) {
            if (entry.getValue() == slot) return separators.get(entry.getKey());
        }
        return null;
    }

    private void markLeadingItem(UIElement group) {
        boolean first = true;
        for (UIElement child : group.getChildren()) {
            if (!child.hasClass(ITEM_CLASS)) continue;
            if (first) child.addClass(FIRST_CLASS);
            else child.removeClass(FIRST_CLASS);
            // The leading item's divider would be a rule against the group's own edge, with nothing on
            // the far side of it to separate from.
            UIElement separator = separatorFor(child);
            if (separator != null) separator.setDisplayed(!first);
            first = false;
        }
    }
}
