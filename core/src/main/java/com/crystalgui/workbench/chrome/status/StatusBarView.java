package com.crystalgui.workbench.chrome.status;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.core.notify.StatusBarEntryAccessor;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.service.AnchoredPlacement;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuItem;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * The line along the bottom of the workbench — IntelliJ's status bar, VS Code's {@code STATUSBAR_PART}.
 *
 * <p>The view half of VS Code's {@code statusbarPart.ts}; {@link StatusBar} is the service and
 * {@code StatusBarEntry} the value. Two classes called {@code StatusBar} in two packages would compile
 * and read as a mistake forever after, so the view carries the suffix.</p>
 *
 * <h3>It renders; it does not compute</h3>
 *
 * <p>Everything here is an entry somebody else registered. This class holds no idea of what a caret
 * position is, when a file became read-only, or which encoding is in use — it asks
 * {@link StatusBar#entries(StatusBarAlignment)} and lays them out, in the order the service already put
 * them in. The one exception is {@link #breadcrumbs()}, which is a widget rather than an entry because a
 * trail is clickable and structured; the host sets its trail.</p>
 *
 * <h3>Slots are keyed by the accessor, not by a string</h3>
 *
 * <p>Which is what makes two writers unable to collide — see {@link StatusBarEntryAccessor}. It also
 * collapses what used to be <b>three parallel maps</b> keyed by the same id (label, separator, tooltip)
 * into one {@link Slot}: the parallel version made "find the separator belonging to this label" a linear
 * scan of the label map, run once per entry per refresh, on a path the caret readout drives every frame.</p>
 *
 * <h3>Slots are updated in place, never rebuilt</h3>
 *
 * <p>The engine's rule: <b>a widget must never rebuild the elements it is being clicked on</b>. Entries
 * are written from per-frame paths — the shader graph's line-owner readout fires on every caret move — so
 * a rebuild per change would be discarding and recreating elements continuously. An entry keeps one
 * {@link UIText} for as long as it exists; only appearing, disappearing and <em>reordering</em> touch the
 * tree.</p>
 */
public class StatusBarView extends UINode {

    public static final Name NAME = Name.of("statusbarview");

    public static final String BAR_CLASS = "__status-bar__";
    /** The prose half, and where {@link #breadcrumbs()} sits. */
    public static final String LEFT_CLASS = "__left__";
    /** The glance half. */
    public static final String RIGHT_CLASS = "__right__";
    /** Takes the slack between the two groups. @see StatusBarView */
    public static final String SPACER_CLASS = "__spacer__";
    /** One rendered entry. */
    public static final String ITEM_CLASS = "__status-item__";

    /**
     * On the leading entry of each group, so the sheet can draw a divider before every entry <em>but</em>
     * that one.
     *
     * <p>Carried as a class because the selector engine has no {@code :first-child} — {@code :nth-child}
     * and the sibling combinators are deliberately unimplemented. A view that knows its own order is the
     * substitute, and it is the same substitute internal-child classes already are for pseudo-elements.</p>
     */
    public static final String FIRST_CLASS = "__first__";

    /** On an entry that runs a command when pressed. @see StatusBarEntry#command() */
    public static final String CLICKABLE_CLASS = "__clickable__";

    /** Kind, as a class — the same convention the Problems rows and notification cards use. */
    public static final String SEVERITY_PREFIX = "severity-";

    /**
     * The rule drawn before every entry but the leading one.
     *
     * <p><b>A real element, not a CSS border</b>, and that is forced rather than chosen: the paint path
     * reads {@code layout.border().left} as <em>the</em> border width and strokes a uniform box, so
     * asymmetric borders are not modelled — a {@code border-width-left} drew a rectangle around every
     * readout instead of a rule between two. {@code Breadcrumbs} already spells its separators as elements
     * for the same reason.</p>
     */
    public static final String SEPARATOR_CLASS = "__status-sep__";

    /**
     * How many children of the left group are not entry slots.
     *
     * <p>Exactly one — {@link #breadcrumbs()}, added first in the constructor and never removed — and the
     * ordering pass has to skip it or it would place the leading entry on top of the trail.</p>
     */
    private static final int LEFT_GROUP_RESERVED = 1;

    /**
     * The progress indicator sits at the head of the right group and is not a registry slot.
     *
     * <p>Same shape as {@code breadcrumbs} on the left, and for the same reason: a {@link StatusBarEntry}
     * is text, and progress needs a bar and a cancel. Reserving it here keeps {@code applyGroup} from
     * treating it as an entry and dropping it on the next refresh.</p>
     */
    private static final int RIGHT_GROUP_RESERVED = 1;

    private final UINode leftGroup = new UINode();
    private final UINode rightGroup = new UINode();
    private final UINode spacer = new UINode();
    private final Breadcrumbs breadcrumbs = new Breadcrumbs();

    /** One live slot per registered entry — see the class note on why these are keyed by the accessor. */
    private final Map<StatusBarEntryAccessor, Slot> slots = new IdentityHashMap<>();

    /** Held so the view stops listening when it leaves the tree; the service outlives every view of it. */
    private final ConnectionGroup subscriptions = new ConnectionGroup();

    private final ProgressStatusItem progress = new ProgressStatusItem();

    public StatusBarView() {
        super(NAME);
        addClass(BAR_CLASS);
        // NOT markAsInternal(). Whether this part is internal to its host is the host's decision — the
        // shell adds it with addInternalChild — and stamping it here would recurse over a subtree whose
        // own slots are added and removed publicly, which removeChild silently refuses.

        leftGroup.addClass(LEFT_CLASS);
        rightGroup.addClass(RIGHT_CLASS);
        spacer.addClass(SPACER_CLASS);
        // Nothing in the bar takes the pointer except the breadcrumbs and any entry carrying a command,
        // both of which install their own handling. Without this a press on the bar's background lands on
        // a label and goes nowhere visible, but it still moves focus off whatever the user was typing in.
        spacer.setHitTest(false);

        leftGroup.append(breadcrumbs);
        rightGroup.append(progress);
        append(leftGroup);
        append(spacer);
        append(rightGroup);

        buildHideMenu();
        refresh();
    }

    /**
     * The right-click menu that hides entries — VS Code's status bar context menu.
     *
     * <p>Ported from {@code vs/workbench/browser/parts/statusbar/statusbarPart.ts}, which builds one
     * checkable {@code ToggleStatusbarEntryVisibilityAction} per entry <b>labelled by
     * {@code entry.name}</b>. That is what the name/text split exists for: you cannot offer "hide 51:39"
     * as a checkbox, and the text it would name changes on every keystroke.</p>
     *
     * <p>Rebuilt on open rather than kept in step, because the set of entries changes constantly — a
     * document activating registers four — and a menu is read at the moment it is opened and at no other.
     * Dropped from the reference: {@code Hide Status Bar} (there is no command for it here) and the
     * extension rows.</p>
     */
    private void buildHideMenu() {
        append(hideMenu);
        hideMenu.onItemActivated.connect(item -> {
            String id = menuIds.get(item);
            if (id != null) StatusBar.setHidden(id, !StatusBar.isHidden(id));
        });
        events.getGroup(MouseEvent.Down.class).attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.RIGHT_BUTTON) return;
            UIDocument window = document();
            if (window == null) return;
            event.stopPropagation();
            populateHideMenu();
            var at = AnchoredPlacement.pointerToRoot(window,
                    event.getPosition().x(), event.getPosition().y());
            hideMenu.showAt(at.x(), at.y(), null);
        }, false, true);
    }

    /** One checkable row per registered entry, hidden ones included — or there is no way back. */
    private void populateHideMenu() {
        hideMenu.clearItems();
        menuIds.clear();
        for (StatusBarEntryAccessor accessor : StatusBar.allEntries()) {
            String id = StatusBar.idOf(accessor);
            MenuItem item = hideMenu.addCheckableItem(accessor.entry().name());
            item.setSelected(!StatusBar.isHidden(id));
            menuIds.put(item, id);
        }
    }

    /** The hide menu, so a test can activate a row rather than calling StatusBar.setHidden directly. */
    public Menu hideMenu() {
        populateHideMenu();
        return hideMenu;
    }

    private final Menu hideMenu = new Menu();

    /** Which entry each row toggles. By identity, since two entries may share a name. */
    private final Map<MenuItem, String> menuIds = new IdentityHashMap<>();

    /**
     * The path shown at the leading edge. The host sets the trail; this class never derives one.
     *
     * @see Breadcrumbs#setTrail
     */
    /** The progress indicator — one job inline, the rest behind a count. @see ProgressStatusItem */
    public ProgressStatusItem progress() {
        return progress;
    }

    public Breadcrumbs breadcrumbs() {
        return breadcrumbs;
    }

    /**
     * Subscribes while attached, and only while attached.
     *
     * <p>{@link StatusBar} is static and outlives every view of it, so a view that never unsubscribed would
     * be kept alive by the service — along with its elements and everything they reference — for the rest
     * of the process. The same leak {@code ListView} guards against by disposing when it leaves the tree.</p>
     *
     * <p><b>The window hook, not {@code onLayoutChanged}.</b> This was written against layout, which fires
     * on every pass and therefore had to guard itself with "have I already subscribed?" — a poll wearing a
     * callback, and the exact workaround {@link #onWindowChanged} was added to retire. Disconnecting
     * unconditionally also covers a move <em>between</em> windows, where both arguments are non-null and
     * the "am I attached?" question answers yes on the way in and out.</p>
     */
    @Override
    protected void disconnected() {
        subscriptions.disconnectAll();
    }

    @Override
    protected void connected() {
        subscriptions.disconnectAll();
        subscriptions.add(StatusBar.onDidChange.connect(this::refresh));
        refresh();
    }

    /** Brings the rendered slots in line with the service. Cheap when nothing changed. */
    public void refresh() {
        List<StatusBarEntryAccessor> left = StatusBar.entries(StatusBarAlignment.LEFT);
        List<StatusBarEntryAccessor> right = StatusBar.entries(StatusBarAlignment.RIGHT);

        // GONE FIRST, so an entry that moved from one group to the other does not briefly exist twice.
        dropSlotsNotIn(left, right);

        applyGroup(leftGroup, left, LEFT_GROUP_RESERVED);
        applyGroup(rightGroup, right, RIGHT_GROUP_RESERVED);
    }

    private void dropSlotsNotIn(List<StatusBarEntryAccessor> left, List<StatusBarEntryAccessor> right) {
        if (slots.isEmpty()) return;
        Set<StatusBarEntryAccessor> living = Collections.newSetFromMap(new IdentityHashMap<>());
        living.addAll(left);
        living.addAll(right);
        for (StatusBarEntryAccessor accessor : new ArrayList<>(slots.keySet())) {
            if (living.contains(accessor)) continue;
            slots.remove(accessor).removeFromTree();
        }
    }

    /**
     * Renders one group in the service's order.
     *
     * @param reserved how many leading children of the group are not slots — @see #LEFT_GROUP_RESERVED
     */
    private void applyGroup(UINode group, List<StatusBarEntryAccessor> entries, int reserved) {
        int position = reserved;
        for (int i = 0; i < entries.size(); i++) {
            StatusBarEntryAccessor accessor = entries.get(i);
            Slot slot = slots.get(accessor);
            if (slot == null) {
                slot = new Slot();
                slots.put(accessor, slot);
            }
            slot.apply(accessor.entry());
            // The leading entry's divider would be a rule against the group's own edge, with nothing on
            // the far side of it to separate from.
            slot.setLeading(i == 0);
            // PLACED, not appended: priority decides order, so an entry registered late can belong first.
            // Only moves what is actually out of position — a reorder detaches elements, and this runs on
            // the caret readout's per-frame path.
            position = slot.placeIn(group, position);
        }
    }

    /**
     * One entry's elements, together.
     *
     * <p>The divider lives with the label it precedes rather than in a map of its own, which is what makes
     * "the separator belonging to this entry" a field read instead of a scan.</p>
     */
    private static final class Slot {

        final UIText label = new UIText("");
        final UINode separator = new UINode();

        /**
         * Attached once and then only re-texted.
         *
         * <p>{@code Tooltip.attach} adds a hover listener pair every time it is called and {@code detach}
         * leaves them inert rather than removing them, so attach/detach cycling silently accumulates
         * listeners — its own javadoc records that. Attaching with the slot and updating the text instead
         * sidesteps it entirely.</p>
         */
        @Nullable
        Tooltip tip;

        /**
         * Read by the click handler <b>at press time</b>, never captured into it.
         *
         * <p>A slot outlives any one entry it shows, so a listener that closed over the command id would
         * keep running whichever command the slot was first used for — the same trap the editor's pooled
         * gutter arrows document, where an arrow kept toggling the row its slot first held.</p>
         */
        @Nullable
        String command;

        private boolean listening;
        private String appliedKind = "";

        Slot() {
            label.addClass(ITEM_CLASS);
            // HIT-TESTABLE, unlike the spacer: a tooltip is driven by mouseenter/mouseleave, and
            // setHitTest(false) applies to the whole subtree, so an unhittable label never hovers.
            separator.addClass(SEPARATOR_CLASS);
            separator.setHitTest(false);
        }

        void apply(StatusBarEntry entry) {
            // IN PLACE. setText no-ops when the string is unchanged, so an entry rewritten with the same
            // value every frame costs one comparison.
            label.setText(entry.text());
            applyTooltip(entry.hoverText());
            // SWAPPED, never added: an entry's kind changes in place — a compile that failed and then
            // succeeded rewrites the same slot — so adding would leave both classes on it.
            String kind = SEVERITY_PREFIX + entry.kind().name().toLowerCase(Locale.ROOT);
            if (!kind.equals(appliedKind)) {
                label.swapPrefixedClass(SEVERITY_PREFIX, kind);
                appliedKind = kind;
            }
            command = entry.command();
            if (command != null) {
                label.addClass(CLICKABLE_CLASS);
                if (!listening) {
                    listening = true;
                    label.onMouseDown.attachListener((element, event) -> {
                        if (command == null) return;
                        event.stopPropagation();
                        // RUN FROM THE ELEMENT PRESSED, never contextless. `run(id)` builds an EMPTY data
                        // context, so a command guarded by `enabledWhereData` — which every command that
                        // acts on a workbench or a window is — evaluates its guard against nothing, fails
                        // it, and returns false. The entry drew a pointer cursor and did nothing when
                        // clicked, which is the exact "the command exists but nothing happens" failure
                        // CommandRegistry.run already warns about one line further down.
                        //
                        // The element is what makes the walk work: a data context resolves by walking up
                        // the tree and then asking the window, and the workbench registers itself as a
                        // provider there.
                        CommandRegistry.global().run(command, CommandContext.of(element));
                    }, false, true);
                }
            } else {
                label.removeClass(CLICKABLE_CLASS);
            }
        }

        private void applyTooltip(String text) {
            if (tip == null) {
                if (text.isEmpty()) return;
                tip = Tooltip.attach(label, text);
                return;
            }
            if (text.isEmpty()) {
                // Emptied rather than detached, for the reason on the field. An empty tooltip draws nothing.
                tip.hide();
                tip.setText("");
                return;
            }
            tip.setText(text);
        }

        void setLeading(boolean leading) {
            if (leading) label.addClass(FIRST_CLASS);
            else label.removeClass(FIRST_CLASS);
            separator.setDisplayed(!leading);
        }

        /** Puts the pair at {@code index} in {@code group}, moving only what is in the wrong place. */
        int placeIn(UINode group, int index) {
            index = place(group, separator, index);
            return place(group, label, index);
        }

        /**
         * Moves {@code child} to {@code index} in {@code group}, and only if it is not already there.
         *
         * <p><b>The detach is the two-step {@code addChildAtInternal} itself uses</b>, and it has to be:
         * {@code removeChild} refuses an internal child by contract and reports it by returning false, so
         * a bare {@code removeSelf()} is a <em>silent</em> no-op for one — after which the re-add walks
         * into {@code hasChild} and throws "Cannot add the same child twice". Which is exactly what it did,
         * and only when an entry was withdrawn from ahead of another, since that is the only thing that
         * moves an existing slot.</p>
         */
        private static int place(UINode group, UINode child, int index) {
            List<UINode> children = group.children();
            int current = children.indexOf(child);
            if (current == index) return index + 1;
            if (current >= 0 && !group.remove(child)) group.remove(child);
            group.insertAt(Math.min(index, group.children().size()), child);
            return index + 1;
        }

        void removeFromTree() {
            label.removeSelf();
            separator.removeSelf();
        }
    }
}
