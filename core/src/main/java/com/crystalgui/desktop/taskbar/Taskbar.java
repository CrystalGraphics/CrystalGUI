package com.crystalgui.desktop.taskbar;

import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.DesktopCommands;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.launcher.LauncherButton;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.window.SystemMenu;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.window.WindowIcon;
import com.crystalgui.desktop.window.WindowRegistry;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.service.AnchoredPlacement;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.ui.service.Input;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.command.Commands;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;
import com.crystalgraphics.platform.input.CgMouseCodes;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The strip along the bottom of the {@link Desktop} — one entry per live window
 * ({@code plan/shell-windowing.md}, W4).
 *
 * <p><b>It is the {@link WindowRegistry}, rendered.</b> Never a second list: a window joins on open and
 * leaves only on destroy, so the strip shows what <em>exists</em> rather than what is on screen, and a
 * minimised window's entry is how it comes back. That is Windows' rule, and it is the reason hiding is
 * safe to offer at all — <b>minimise with no discoverable way back is worse than no minimise</b>.</p>
 *
 * <h3>Where it sits, and why that is a decision</h3>
 * <p><b>Bottom, with the entries centred</b> — a strip in the same place, and roughly the same shape, as
 * Minecraft's hotbar. That is deliberate: it is where a player's eye already lives, so the compositor's
 * one permanent landmark costs no new habit. The band spans the full width for <em>layout</em> (which
 * is what keeps the work area trivial — see {@link Desktop}) while only the island in the middle paints,
 * so the corners stay free for whatever a shell later wants in them.</p>
 *
 * <p><b>It covers the real hotbar, deliberately.</b> The strip paints only while our own screen is up
 * ({@code CgUiScreen.Mode.DESKTOP} — the HUD and overlay modes paint pinned windows alone),
 * and with a screen up the hotbar cannot be used: the cursor is the screen's. Minecraft draws the
 * in-game HUD <em>before</em> every screen, so the hotbar is behind chat and inventories too, and a
 * full-width, near-opaque bar hides it where a translucent island the same size merely blurred it into
 * blotches behind the labels. What genuinely has to respect the hotbar is W14's pinned windows, which
 * paint over the running game. (This used to say the game was paused; it is not: the desktop's
 * screen, CrystalGraphics' {@code CgScreens}, pauses nothing.)</p>
 *
 * <h3>Entries are reconciled, never rebuilt</h3>
 * <p>Refresh runs on every registry change, including activation — so rebuilding the strip would destroy
 * the element that is being clicked, which is the trap that froze the table header. Entries are kept per
 * window and updated in place; only a window that has genuinely gone loses one.</p>
 */
public class Taskbar extends UIElement {

    /** The cascade identity `ua/desktop.css` names. @see com.crystalgui.ui.dom.Name */
    public static final Name NAME = Name.of("taskbar");

    /** The centred island the entries live in — the part that actually paints. */
    public static final String ENTRIES_CLASS = "__entries__";
    /** One per live window. */
    public static final String ENTRY_CLASS = "__entry__";
    /** On the entry whose window is active. A class, like every other state the compositor flips. */
    public static final String ACTIVE_CLASS = "__active__";
    /** On a pinned application's entry while it has no window: a launch button, with no running pill. */
    public static final String LAUNCH_CLASS = "__launch__";
    /** On a combined button standing for more than one window, for a theme that marks it. @see TaskbarCombine */
    public static final String MULTIPLE_CLASS = "__multiple__";
    /** On the entry of a window that is currently hidden. */
    public static final String HIDDEN_CLASS = "__hidden__";
    /** The entry's icon slot, hidden until the window declares one. */
    public static final String ICON_CLASS = "__icon__";
    /** On the entry of a window that opened without focus and has not been looked at yet. */
    public static final String ATTENTION_CLASS = "__attention__";
    /** The entry's badge slot — an unsaved count, an error count. Absent until the window declares one. */
    public static final String BADGE_CLASS = "__badge__";
    /** On an entry whose window is reporting progress; the fill below is only laid out while it is on. */
    public static final String BUSY_CLASS = "__busy__";
    /** The bar drawn behind an entry's label while its window reports progress. */
    public static final String PROGRESS_CLASS = "__progress__";
    /**
     * The pill under every entry — Windows' running indicator. Every live window has one; the active
     * window's is wider and takes the accent, which is the one place the strip says "this one" in colour.
     * An internal child of the entry, built in its constructor, so it exists before the entry is attached.
     */
    public static final String INDICATOR_CLASS = "__indicator__";

    /**
     * The indicator's part name — what a sheet addresses it by.
     *
     * <p>It lives in the Button's shadow tree, so a descendant selector from {@code taskbar} reaches
     * nothing: {@code taskbar .__entry__::part(indicator)} is the spelling, and the class beside it
     * is kept only so the two engines' sheets can share a name while both run.</p>
     */
    public static final String INDICATOR_PART = "indicator";
    /**
     * The hairline along the bar's top edge. An ELEMENT rather than a border, because a one-sided
     * {@code border-top-width} draws nothing here and the left-hand spelling draws all four edges — the
     * documented trap — and a single edge is how {@code statusbarview} spells its separators too.
     */
    public static final String START_CLASS = "__start-button__";

    public static final String EDGE_CLASS = "__edge__";

    /** The show-desktop strip at the bar's right edge. */
    public static final String SHOW_DESKTOP_CLASS = "__show-desktop__";
    /** The hairline along that strip's left side, in its shadow tree. */
    public static final String SHOW_DESKTOP_LINE_PART = "line";

    private final UIElement entries;

    private final Button showDesktop;

    /** What CAN run, beside the strip that shows what IS running. @see #start() */
    private final LauncherButton start;

    /**
     * An entry collapsing out of the row — it is no longer in {@link #entryOf} and is not a target.
     * @see TaskbarEntryExit
     */
    public static final String EXITING_CLASS = "__exiting__";

    /**
     * On an entry for as long as it is ramping, in either direction — the sheet clips it while it is
     * narrower than its own label.
     *
     * <p>A CLASS rather than an inline {@code overflow} written from Java, and not merely for the
     * no-properties-in-widgets rule: nothing in the sheet sets {@code overflow} on an entry, so an inline
     * write is the ONLY candidate the property has, and withdrawing it at the end left the property with
     * no value at any origin. {@code getComputed} answers null for that, and the {@code overflow}
     * listener hands its argument straight to a switch — {@code NullPointerException} out of
     * {@code resolveTouched}, on the frame an arriving entry settled. @see TaskbarEntryMotion
     */
    /** An arriving entry while it is measured: invisible, since it has no box to carry an opacity yet. */
    public static final String MEASURING_CLASS = "__measuring__";

    public static final String ANIMATING_CLASS = "__animating__";

    /**
     * On an entry with nothing to write beside its icon.
     *
     * <p>An entry is an icon and a label in a row, so an empty label leaves the icon sitting against
     * the leading padding with the gap and the trailing padding still spent after it -- 22px of
     * space around a 16px icon, most of it on one side. A window may legitimately have no title, so
     * this is a shape an entry has rather than a state to avoid: the sheet centres the icon and
     * closes the padding up around it.</p>
     */
    public static final String ICONIC_CLASS = "__iconic__";

    /** On the entries row while an entry is dragged along it: the sheet lets the dragged entry paint past the row. */
    public static final String REORDERING_CLASS = "__reordering__";

    /** How long the pointer rests on a button before its name shows: Windows' one second. */
    private static final float TOOLTIP_DELAY_SECONDS = 1f;

    /** On the tooltips naming the bar's buttons, which the sheet draws light, as Windows' are. */
    public static final String TIP_CLASS = "__taskbar-tip__";

    /** A button's name after a second's rest, sitting on the bar's top edge over it, as Windows' does. */
    private Tooltip nameOnTheBar(Tooltip tooltip) {
        tooltip.addClass(TIP_CLASS);
        return tooltip.setDelay(TOOLTIP_DELAY_SECONDS).setSide(AnchoredPlacement.Side.TOP).setEdgeOf(this);
    }

    /** Slot → its entry: a window, or an {@link AppKey} for an application's own button. Insertion-ordered. */
    private final Map<Object, Entry> entryOf = new LinkedHashMap<>();

    /**
     * Entries mid-collapse, by the window they stood for, so a window that comes back can cancel one.
     *
     * <p>SEPARATE from {@link #entering} rather than one map of motions, because the two are cancelled by
     * opposite events: a frame appearing in the registry cancels its collapse, and a frame leaving cancels
     * its arrival. One map would have to ask which direction it held on every refresh — and a refresh runs
     * on every activation and every title change, so getting it backwards kills every opening animation on
     * the next thing that happens.</p>
     */
    private final Map<Object, TaskbarEntryMotion> exiting = new LinkedHashMap<>();

    /** Entries mid-arrival. @see #exiting */
    private final Map<Object, TaskbarEntryMotion> entering = new LinkedHashMap<>();

    /**
     * Built by {@link Desktop}; public because a tag needs a factory.
     *
     * <p>A widget's cascade identity is its <b>tag</b>, so an unregistered {@code Taskbar} could not be
     * styled by {@code taskbar { ... }} at all — the lesson {@code Dropdown extends Button} paid for by
     * laying out at zero height until {@code default.css} named it. Which is why the desktop is found by
     * walking up rather than handed in: a factory has nothing to hand.</p>
     */
    public Taskbar() {
        super(NAME);
        // `desktop()` walks parents, so it can only answer once this is in a tree -- which is exactly
        // when the supplier runs. @see UINode#whileConnected
        whileConnected(() -> desktop().registry().onDidChange.connect(this::refresh));
        whileConnected(() -> desktop().pins().onDidChange.connect(this::refresh));
        whileConnected(() -> desktop().desktopSettings().onDidChange.connect(this::refresh));
        // WHEN FULL depends on the bar's width, which changes with no registry event. Owned by the bar, so it
        // stops when the bar leaves the tree and starts again when it comes back.
        onConnected(() -> {
            UIDocument document = document();
            if (document != null) document.animation().everyIfAbsent(this, widthWatch);
        });
        // An application installed late gives its pin a button
        whileConnected(() -> desktop().applications().onDidChange.connect(this::refresh));
        onConnected(this::arrive);
        // A PRESS ON BARE BAR FOCUSES THE BAR, unringed and never a Tab stop itself, so Tab goes on to Start, as Windows'.
        setFocusPolicy(FocusPolicy.CLICK_NOT_TABBABLE);
        // THE HAIRLINE FIRST, so it paints under the entries rather than over them. Absolute, so the row
        // below still centres its island as if the edge were not there.
        edge.addClass(EDGE_CLASS);
        edge.setHitTest(false);
        append(edge);


        // THE START BUTTON, before the entries and outside their island: it is the one thing on this
        // strip that is NOT a running window, and putting it inside the island would make it cascade,
        // animate and re-centre along with them. It names no application -- the panel reads the
        // desktop's own registry -- which is what lets a launcher live in `desktop` at all.
        start = new LauncherButton();
        start.addClass(START_CLASS);
        append(start);
        nameOnTheBar(Tooltip.attach(start, "Start"));

        entries = new UIElement();
        entries.addClass(ENTRIES_CLASS);
        append(entries);
        reorder = new TaskbarReorder(this, entries);

        // SHOW DESKTOP at the bar's right edge, Windows': absolute, so the entries stay centred.
        showDesktop = new ShowDesktopButton();
        showDesktop.addClass(SHOW_DESKTOP_CLASS);
        showDesktop.attachListener(() -> {
            Desktop desktop = desktop();
            if (desktop != null) desktop.toggleShowDesktop();
        });
        append(showDesktop);
        nameOnTheBar(Tooltip.attach(showDesktop, "Show desktop"));

        // THE STRIP'S OWN CONTEXT MENU, for the commands about the SET of windows.
        //
        // A right-click on an ENTRY never reaches here, because the entry's own handler consumes it and
        // opens the jump list instead -- which is the distinction the two menus exist for. This one is
        // about the SET of windows; that one names a window. Nor is it the start button's.
        ContextMenu.attach(this, Commands.process(),
                pressed -> UINode.isShadowIncludingInclusiveAncestor(start, pressed)
                        ? null : ContextMenu.of(MenuId.TASKBAR_CONTEXT));
        // BOTH CHILDREN BEFORE ATTACH. Adding one later means inserting a Taffy node into a parent
        // whose children are mid-registration -- `Index (is 1) should be < child_count (0)` -- which is
        // exactly what building the previews lazily from createEntry did, since refresh() runs from
        // onWindowChanged. @see UIElement#taffyChildIndex
        previews = new TaskbarPreviews(this);
    }

    /** Dragging an entry along the bar. @see TaskbarReorder */
    private final TaskbarReorder reorder;

    /** The entries in the row, in drawn order, without those still closing. */
    List<UIElement> entryRow() {
        List<UIElement> row = new ArrayList<>();
        for (UIElement child : entries.children()) {
            if (!child.hasClass(EXITING_CLASS)) row.add(child);
        }
        return row;
    }

    /** An application's own button: its pin with no window, or its combined button. Null when it has none. */
    @Nullable
    public Button pinEntryFor(ApplicationKind kind) {
        return entryOf.get(new AppKey(kind.id()));
    }

    /** The windows an entry stands for: its own, or every one of its application's when combined. */
    List<WindowFrame> windowsOf(UIElement entry) {
        return entry instanceof Entry known ? known.shown() : List.of();
    }

    /** Holds the hover previews down while an entry is being dragged, by {@code drag}. */
    void previewsSuppressed(Object drag, boolean suppressed) {
        previews.setSuppressed(drag, suppressed);
    }

    /** The start button, and through it the launcher. */
    public LauncherButton start() {
        return start;
    }

    /** The strip at the bar's right edge that minimises every window, and puts them back. */
    public Button showDesktopButton() {
        return showDesktop;
    }

    /** The hover previews. One panel that moves between entries. @see TaskbarPreviews */
    private final TaskbarPreviews previews;


    /** The hover preview panel, for the designer: it carries the same tone as the bar. */
    WindowPreview previewPanel() {
        return previews.panel();
    }

    /**
     * Turns hover previews off while something else owns the space above an entry — a jump list.
     *
     * <p>Explicit rather than relying on the preview's own mouse-down dismissal, and that is not
     * belt-and-braces: {@code stopPropagation()} halts the remaining listeners <b>on the same element and
     * phase</b>, so the jump list's own handler — attached first — was silencing the preview's dismissal
     * outright. Depending on the order two listeners happen to be registered in is exactly the coupling
     * that produced a preview sitting on top of the menu that replaced it.</p>
     *
     * <pre>{@code
     * taskbar.setPreviewsSuppressed(menu, true);    // as the jump list opens
     * taskbar.setPreviewsSuppressed(menu, false);   // as it closes: previews return once nothing else holds them
     * }</pre>
     */
    public void setPreviewsSuppressed(Object by, boolean suppressed) {
        previews.setSuppressed(by, suppressed);
    }

    /**
     * Whether previews are currently silenced.
     *
     * <p>Exposed for one assertion that has no other observable: "the strip can preview again" is the
     * absence of a panel plus the absence of a reason for it, and a test can otherwise only wait and
     * conclude nothing appeared — which is equally true of a strip that is broken for good.</p>
     */
    public boolean previewsSuppressedForTesting() {
        return previews.isSuppressed();
    }

    /** A taskbar owns its entries; they follow the registry. */

    /**
     * Subscribes while attached and only while attached — {@code StatusBarView}'s pattern, for the same
     * reason: the registry outlives any taskbar that has left the tree.
     */
    @Override
    protected void connected() {
        super.connected();
    }

    /** The desktop this strip belongs to — always its parent, and null only while detached. */
    @Nullable
    Desktop desktop() {
        for (UIElement element = parentElement(); element != null; element = element.parentElement()) {
            if (element instanceof Desktop) return (Desktop) element;
        }
        return null;
    }

    /**
     * Brings the strip in line with the registry and the pins. Cheap when nothing changed, and safe to call
     * from a handler running on an entry — nothing is detached unless what it stood for is genuinely gone.
     *
     * <p>Pins first, in their order: a pinned application's windows sit in its slot, and with none running
     * the slot is a launch button. Then every other window, in open order. A pin's entry is HANDED to its
     * application's first window when it starts and back when the last one closes, so launching from a pin
     * and closing back to it keeps the one button in place, as Windows' does.</p>
     */
    public void refresh() {
        Desktop desktop = desktop();
        if (desktop == null) return;
        Box bar = box();
        refreshedWidth = bar == null ? -1f : bar.width();
        List<Object> wanted = slots(desktop);

        handOver(desktop, wanted);

        // GONE FIRST, so an entry whose window was destroyed cannot be re-used below.
        List<Object> stale = new ArrayList<>();
        for (Object key : entryOf.keySet()) {
            if (!wanted.contains(key)) stale.add(key);
        }
        for (Object key : stale) {
            Entry entry = entryOf.remove(key);
            if (entry == null) continue;
            // CLOSED WHILE IT WAS STILL ARRIVING: settle the arrival first, or both motions write the same
            // cap every frame and the picture is whichever ran last. Cancelling also hands the entry back
            // at full width, which is what the collapse below is about to measure.
            TaskbarEntryMotion arriving = entering.remove(key);
            if (arriving != null) arriving.cancel();

            Runnable detach = () -> {
                exiting.remove(key);
                entries.remove(entry);
            };
            // NEVER REALLY THERE: a window that joined bare and was named an application's in the same tick
            // got an entry for that moment, and its application's button is the one that stays. A window that
            // closes while arriving still collapses.
            String absorbedBy = key instanceof WindowFrame window ? applicationId(window) : null;
            if (arriving != null && absorbedBy != null && wanted.contains(new AppKey(absorbedBy))) {
                detach.run();
                continue;
            }
            // THE ROW CLOSES UP rather than the button blinking out and everything after it jumping.
            // SYNCHRONOUS with animations off -- "off" has to turn off the waiting too, or every caller
            // pays the frames the collapse takes in a mode where nothing is animating. @see WindowAnimator
            if (!WindowAnimator.isEnabled()) {
                detach.run();
                continue;
            }
            entry.addClass(EXITING_CLASS);
            TaskbarEntryMotion collapse = TaskbarEntryMotion.closing(entry, detach);
            if (collapse.start()) exiting.put(key, collapse);
            else detach.run();
        }

        // THE WINDOW THE ACTIVE ONE IS REPRESENTED BY, which for a tool window is its owner: a tool
        // window has no entry of its own, so comparing against it directly lights nothing and the
        // application the panel belongs to reads as unfocused while its own panel is in front.
        WindowFrame focused = desktop.activeWindow();
        WindowFrame active = focused == null ? null : focused.taskbarSubject();
        int index = 0;
        for (Object key : wanted) {
            // BACK BEFORE IT FINISHED LEAVING. Dropped now rather than revived: the collapse is 150ms and
            // a window returning inside it is rare, while a half-width entry handed back to the strip is
            // a pinned width nothing would ever clear. @see TaskbarEntryExit
            TaskbarEntryMotion returning = exiting.remove(key);
            if (returning != null) returning.cancel();

            Entry entry = entryOf.get(key);
            if (entry == null) {
                entry = key instanceof WindowFrame frame
                        ? new Entry(frame, null)
                        : new Entry(null, kindOf(desktop, (AppKey) key));
                wire(desktop, entry);
                entryOf.put(key, entry);
                entries.append(entry);
                // ...AND THE ROW OPENS FOR IT, the collapse in reverse. Only for an entry built HERE: this
                // loop runs on every activation and every title change, so anything keyed on the frame
                // rather than on the entry being new would restart the arrival over and over.
                // Not while the bar comes on screen: the entries rise into it with it.
                if (WindowAnimator.isEnabled() && !arriving) {
                    TaskbarEntryMotion open = TaskbarEntryMotion.opening(entry, () -> entering.remove(key));
                    if (open.start()) entering.put(key, open);
                }
            }
            // SLOT ORDER, and stable: a bar whose entries jump on every activation is the "never in the
            // same place twice" menu bug wearing a strip. An entry mid-collapse holds its place, so the
            // running index steps over it; `insertAt` is a move, never a remove and an insert.
            // @see UIElement#insertAt
            List<UIElement> row = entries.children();
            while (index < row.size() && row.get(index).hasClass(EXITING_CLASS)) index++;
            if (entries.indexOf(entry) != index) entries.insertAt(index, entry);
            index++;

            entry.windows = key instanceof AppKey app ? List.copyOf(groups.get(app)) : List.of();
            WindowFrame frame = entry.frame;
            setClass(entry, MULTIPLE_CLASS, entry.windows.size() > 1);
            if (frame != null) {
                entry.setText(frame.getTitle());
                setClass(entry, ICONIC_CLASS, frame.getTitle().isEmpty());
                applyIcon(entry, frame.iconName(), frame.iconTitle());
                setClass(entry, LAUNCH_CLASS, false);
                setClass(entry, ACTIVE_CLASS, frame == active);
                setClass(entry, HIDDEN_CLASS, frame.state() == WindowState.HIDDEN);
                setAttention(entry, frame.isDemandingAttention());
                applyBadge(entry, frame.badge());
                applyProgress(entry, frame.progress());
                entry.tooltip.setText("");
            } else if (entry.kind != null && entry.windows.isEmpty()) {
                entry.setText(entry.kind.displayName());
                setClass(entry, ICONIC_CLASS, false);
                applyIcon(entry, entry.kind.icon(), entry.kind.displayName());
                setClass(entry, LAUNCH_CLASS, true);
                setClass(entry, ACTIVE_CLASS, false);
                setClass(entry, HIDDEN_CLASS, false);
                setAttention(entry, false);
                applyBadge(entry, null);
                applyProgress(entry, -1f);
                entry.tooltip.setText(entry.kind.displayName());
            } else if (entry.kind != null) {
                // COMBINED: the application, lit if any of its windows is, put away only if all of them are
                WindowFrame first = entry.windows.get(0);
                boolean lit = false, allHidden = true, attention = false;
                String badge = null;
                float progress = -1f;
                for (WindowFrame each : entry.windows) {
                    lit |= each == active;
                    allHidden &= each.state() == WindowState.HIDDEN;
                    attention |= each.isDemandingAttention();
                    if (badge == null) badge = each.badge();
                    if (progress < 0f) progress = each.progress();
                }
                entry.setText(entry.kind.displayName());
                setClass(entry, ICONIC_CLASS, false);
                applyIcon(entry, first.iconName(), first.iconTitle());
                setClass(entry, LAUNCH_CLASS, false);
                setClass(entry, ACTIVE_CLASS, lit);
                setClass(entry, HIDDEN_CLASS, allHidden);
                setAttention(entry, attention);
                applyBadge(entry, badge);
                applyProgress(entry, progress);
                entry.tooltip.setText("");
            }
        }
    }

    /**
     * What the bar shows, in order: each pin's slot, then every unpinned application and window. An
     * application's windows share one {@link AppKey} slot when combined, and take one slot each when not; a
     * pin with no window is an {@link AppKey} slot too. Fills {@link #groups} with each {@code AppKey}'s windows.
     */
    private List<Object> slots(Desktop desktop) {
        List<WindowFrame> live = desktop.registry().taskbarOrder();
        List<String> pinned = desktop.pins().ids();
        // Each run is one application's windows (a pin's may be empty), or one window with no application
        List<String> runIds = new ArrayList<>();
        List<List<WindowFrame>> runs = new ArrayList<>();
        for (String id : pinned) {
            List<WindowFrame> windows = new ArrayList<>();
            for (WindowFrame frame : live) {
                if (id.equals(applicationId(frame))) windows.add(frame);
            }
            // NOT INSTALLED this launch: kept in the pins, drawn as nothing
            if (windows.isEmpty() && desktop.applications().byId(id) == null) continue;
            runIds.add(id);
            runs.add(windows);
        }
        for (WindowFrame frame : live) {
            String id = applicationId(frame);
            if (id != null && pinned.contains(id)) continue;
            int at = id == null ? -1 : runIds.lastIndexOf(id);
            if (at >= 0) {
                runs.get(at).add(frame);
            } else {
                runIds.add(id);
                runs.add(new ArrayList<>(List.of(frame)));
            }
        }

        boolean combine = combines(desktop.desktopSettings().taskbarCombine(), runs);
        combined = combine;
        groups.clear();
        if (!combine) return uncombinedSlots(pinned, runIds, runs);
        List<Object> out = new ArrayList<>(runs.size());
        for (int i = 0; i < runs.size(); i++) {
            String id = runIds.get(i);
            List<WindowFrame> windows = runs.get(i);
            if (id != null) {
                AppKey key = new AppKey(id);
                groups.put(key, windows);
                out.add(key);
            } else {
                out.addAll(windows);
            }
        }
        return out;
    }

    /**
     * The order of an uncombined bar, where every button has a place of its own and moves alone: the session's
     * order, kept from refresh to refresh. A newcomer goes after its application's other buttons; a pinned
     * application's first window takes its pin's place, and the pin comes back where its last window was.
     */
    private List<Object> uncombinedSlots(List<String> pinned, List<String> runIds, List<List<WindowFrame>> runs) {
        Set<Object> want = new LinkedHashSet<>();
        for (int i = 0; i < runs.size(); i++) {
            String id = runIds.get(i);
            List<WindowFrame> windows = runs.get(i);
            if (id != null && windows.isEmpty()) {
                AppKey pin = new AppKey(id);
                groups.put(pin, windows);
                want.add(pin);
            } else {
                want.addAll(windows);
            }
        }
        List<Object> order = new ArrayList<>(want.size());
        for (Object key : sessionOrder) {
            if (want.contains(key)) {
                if (!order.contains(key)) order.add(key);
                continue;
            }
            String id = idOf(key);
            if (id == null) continue;
            // A PINNED APPLICATION'S LAST WINDOW CLOSED: its pin comes back where it was
            AppKey pin = new AppKey(id);
            if (want.contains(pin)) {
                if (!order.contains(pin)) order.add(pin);
                continue;
            }
            // ...AND ITS FIRST WINDOW TAKES THE PIN'S PLACE, even one the bar placed a moment ago: a window
            // joins before its application is named, so it was appended at the end as nobody's
            if (key instanceof AppKey) {
                for (Object wanted : want) {
                    if (wanted instanceof WindowFrame && id.equals(idOf(wanted)) && !order.contains(wanted)) {
                        order.add(wanted);
                        break;
                    }
                }
            }
        }
        for (Object key : want) {
            if (order.contains(key)) continue;
            String id = idOf(key);
            if (id != null && key instanceof WindowFrame) {
                int pinAt = order.indexOf(new AppKey(id));
                if (pinAt >= 0) {
                    order.set(pinAt, key);
                    continue;
                }
            }
            int at = -1;
            for (int i = 0; id != null && i < order.size(); i++) {
                if (id.equals(idOf(order.get(i)))) at = i + 1;
            }
            if (at < 0 && id != null && pinned.contains(id)) at = pinnedPlace(order, pinned, id);
            if (at < 0) order.add(key);
            else order.add(at, key);
        }
        sessionOrder.clear();
        sessionOrder.addAll(order);
        return order;
    }

    /** Where a pinned application with nothing on the bar yet goes: before the next pin's buttons, in pin order. */
    private static int pinnedPlace(List<Object> order, List<String> pinned, String id) {
        int lastPinned = -1;
        for (int j = pinned.indexOf(id) + 1; j < pinned.size(); j++) {
            for (int i = 0; i < order.size(); i++) {
                if (pinned.get(j).equals(idOf(order.get(i)))) return i;
            }
        }
        for (int i = 0; i < order.size(); i++) {
            String each = idOf(order.get(i));
            if (each != null && pinned.contains(each)) lastPinned = i;
        }
        return lastPinned + 1;
    }

    /**
     * Whether applications share a button now. When full means a button per window would not fit the bar,
     * measured from the last frame: the count asked about is the uncombined one, so the answer cannot flip
     * back and forth on its own result.
     */
    private boolean combines(TaskbarCombine mode, List<List<WindowFrame>> runs) {
        if (mode == TaskbarCombine.ALWAYS) return true;
        if (mode == TaskbarCombine.NEVER) return false;
        int buttons = 0;
        for (List<WindowFrame> run : runs) buttons += Math.max(1, run.size());
        Box bar = box(), startBox = start.box();
        List<UIElement> row = entryRow();
        Box sample = row.isEmpty() ? null : row.get(0).box();
        if (bar == null || sample == null) return false;
        float available = bar.width() - bar.padding().left - bar.padding().right
                - (startBox == null ? 0f : startBox.width() + startBox.margin().right);
        // One button's step along the row, gap included, as the sheet laid it out
        Box next = row.size() >= 2 ? row.get(1).box() : null;
        float step = next != null ? next.x() - sample.x() : sample.width();
        return buttons * step > available;
    }

    /**
     * One application's button is handed between its slots rather than one collapsing while another opens in
     * the same place: a pin's to its first window and back, and a combined button to a window's and back as
     * combining turns on and off.
     */
    private void handOver(Desktop desktop, List<Object> wanted) {
        for (Object key : wanted) {
            String id = idOf(key);
            if (id == null) continue;
            // AN ENTRY STILL ARRIVING gives way too: a window joins the registry before its application is
            // set, so the refresh in between built it an entry of its own beside the pin.
            // An application's own button never gives way: it is the one that stays.
            Entry current = entryOf.get(key);
            if (current != null && (!entering.containsKey(key) || key instanceof AppKey)) continue;
            Object from = null;
            for (Object held : entryOf.keySet()) {
                if (!wanted.contains(held) && id.equals(idOf(held))) {
                    from = held;
                    break;
                }
            }
            if (from == null) continue;
            if (current != null) {
                entering.remove(key).cancel();
                entryOf.remove(key);
                entries.remove(current);
            }
            Entry entry = entryOf.remove(from);
            TaskbarEntryMotion arriving = entering.remove(from);
            if (arriving != null) arriving.cancel();
            if (key instanceof WindowFrame frame) entry.standFor(frame, null);
            else entry.standFor(null, kindOf(desktop, (AppKey) key));
            entryOf.put(key, entry);
        }
    }

    /** The application id a slot belongs to, or null for a window without one. */
    @Nullable
    private static String idOf(Object key) {
        return key instanceof AppKey app ? app.id() : applicationId((WindowFrame) key);
    }

    @Nullable
    private static String applicationId(WindowFrame frame) {
        ApplicationKind kind = frame.application();
        return kind == null ? null : kind.id();
    }

    /** The application an {@link AppKey} slot stands for: installed, or else named by its first window. */
    @Nullable
    private ApplicationKind kindOf(Desktop desktop, AppKey key) {
        ApplicationKind installed = desktop.applications().byId(key.id());
        if (installed != null) return installed;
        List<WindowFrame> windows = groups.get(key);
        return windows == null || windows.isEmpty() ? null : windows.get(0).application();
    }

    /**
     * An application's own slot, beside the windows in {@link #entryOf}: a pin with no window, or a combined
     * button for every window it has open.
     */
    private record AppKey(String id) {
    }

    /** The bar's width at the last refresh, so a resize under When full can recombine. */
    private float refreshedWidth = -1f;

    /** Held, so the service can tell it is already live. Cheap: a width read a frame. */
    private final Animation.Hook widthWatch = delta -> {
        Desktop desktop = desktop();
        Box bar = box();
        if (desktop == null || bar == null) return desktop != null;
        if (desktop.desktopSettings().taskbarCombine() == TaskbarCombine.WHEN_FULL
                && Math.abs(bar.width() - refreshedWidth) > 0.5f) refresh();
        return true;
    };

    /** Whether this refresh combined applications' windows onto one button. */
    private boolean combined = true;

    /** An uncombined bar's order this session, by slot key. @see #uncombinedSlots */
    private final List<Object> sessionOrder = new ArrayList<>();

    /** Each {@link AppKey} slot's windows this refresh, in open order. */
    private final Map<AppKey, List<WindowFrame>> groups = new HashMap<>();

    /**
     * The window's badge, in the entry's post-icon slot.
     *
     * <p>Built on first use rather than always, which is the {@code SearchField} lesson: a permanent
     * child hidden with {@code display: none} still counts for the parent's {@code gap}, so every
     * entry in the strip would have gained a gap for a badge almost none of them has. Not existing is
     * the only spelling of "costs nothing" that actually is.</p>
     */
    private void applyBadge(Button entry, @Nullable String badge) {
        UIElement slot = entry.getPostIcon();
        if (badge == null) {
            if (slot != null) slot.setDisplayed(false);
            return;
        }
        if (slot == null) {
            slot = new UIText("");
            slot.addClass(BADGE_CLASS);
            slot.setHitTest(false);
            entry.setPostIcon(slot);
        }
        slot.setDisplayed(true);
        if (slot instanceof UIText text) text.setText(badge);
    }

    /**
     * The window's progress, as a fill behind the entry's label.
     *
     * <p>A width in percent, which is the one thing a progress bar can be: the entry is sized by its own
     * content and writing a pixel width onto the fill would pin it to whatever the entry measured when
     * the job started — the documented "animate the content, never the container" trap, from the side
     * where the container is the thing that must stay free.</p>
     */
    private void applyProgress(Button entry, float progress) {
        boolean busy = progress >= 0f;
        setClass(entry, BUSY_CLASS, busy);
        UIElement fill = entry.getUnderlay();
        if (!busy) {
            if (fill != null) fill.setDisplayed(false);
            return;
        }
        if (fill == null) {
            fill = new UIElement();
            fill.addClass(PROGRESS_CLASS);
            // A BUTTON REFUSES PUBLIC CHILDREN, like every composite -- so this goes in the widget's own
            // slot rather than being parented onto it. setUnderlay is what makes an absolutely
            // positioned fill measure against the entry's box instead of against the strip's.
            entry.setUnderlay(fill);
        }
        fill.setDisplayed(true);
        float percent = Math.max(0f, Math.min(1f, progress)) * 100f;
        StyleGroup.inlinePipeline(fill.getStyle().getLayoutGroup(), l -> l.widthPercent(percent));
    }

    /**
     * One entry's gestures.
     *
     * <p><b>Click semantics are Windows'</b>, and the third case is the one people miss: a click on the
     * entry of the window you are already in <em>minimises</em> it. Without that the strip is a
     * one-way trip — every entry restores, nothing puts anything away — and the gesture that makes a
     * taskbar feel like a taskbar is the toggle. A pin with no window launches its application.</p>
     *
     * <p>Every handler reads the entry's subject when it runs, never one captured here: a pin's entry is
     * handed to a window and back.</p>
     */
    private void wire(Desktop desktop, Entry entry) {
        entry.addClass(ENTRY_CLASS);
        // ITS NAME AFTER A SECOND'S REST, Windows' tooltip on an icon-only bar. Text only while the entry is a
        // pin: a running window's preview names it half a second in, and an empty tooltip never shows.
        entry.tooltip = nameOnTheBar(Tooltip.attach(entry, ""));
        // CENTRED OVER THE ENTRY, not at the pointer: attach anchors at the press, which for a strip
        // along the bottom of the screen puts a menu at the very edge, left-aligned from wherever the press
        // landed. Windows' jump list is centred over its button, as the hover previews are.
        entry.onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.RIGHT_BUTTON) {
                SystemMenu.dismissJumpList(entry.frame, element);
                return;
            }
            event.stopPropagation();
            // ALREADY UP: a second right-click on its own entry leaves it, as Windows does
            if (SystemMenu.isJumpListOpen(entry.subject(), element)) return;
            Menu jumpList = SystemMenu.showJumpList(entry.subject(), element);
            // LIT WHILE ITS LIST IS UP, as Windows': the pointer leaves the entry for the list, and the entry still says whose
            // it is. Its pill stays the one it had: the window you were in is still the one you are in.
            if (jumpList == null) return;
            entry.addClass(MENU_OPEN_CLASS);
            jumpList.onClosed.connect(() -> entry.removeClass(MENU_OPEN_CLASS));
        }, false, false);
        previews.watch(entry, entry::shown);
        // DRAG TO REORDER, from a left press. Never from the keyboard: Space and Enter arrive as a synthesized
        // press at the physical cursor, which would start a drag anchored wherever the mouse rests.
        entry.onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON || event.getDetail() == Input.KEYBOARD_DETAIL) return;
            reorder.press(element, event.getPosition().x(), event.getPosition().y());
        }, false, false);
        entry.attachListener(() -> {
            // The release that ended a drag is not a click
            if (reorder.swallowClick()) return;
            WindowFrame frame = entry.frame;
            if (frame == null && entry.windows.size() > 1) {
                // SEVERAL WINDOWS: their previews, to pick one, as Windows 11's combined button
                previews.showNow(entry);
                return;
            }
            if (frame == null) frame = entry.windows.isEmpty() ? null : entry.windows.get(0);
            if (frame == null) {
                if (entry.kind != null) DesktopCommands.open(desktop, entry.kind);
                return;
            }
            frame.clearAttention();
            if (frame.state() == WindowState.HIDDEN || desktop.activeWindow() != frame) {
                // POINTER activation, so focus lands without a ring: the user pointed at this. Restoring
                // a hidden window is part of what activate() means -- see Desktop.activate.
                desktop.activate(frame, false);
            } else {
                // THE GESTURE, not the state change -- WindowFrame.minimize plays the flight into this
                // very entry and hides at the end of it.
                frame.minimize();
            }
        });
        // MIDDLE-CLICK CLOSES, as it does on every taskbar and on every browser tab. Through
        // requestClose, never destroy: the window's own policy decides what closing means, so a
        // HIDE_ON_CLOSE window is put away and a dirty one still gets to refuse. On a pin it launches,
        // as Windows' middle-click starts another instance.
        //
        // On mouse-DOWN rather than the press pair, because a Button's activation is button-agnostic --
        // it fires for any button, so routing this through onPressed would close the window on an
        // ordinary left click as well.
        entry.onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.MIDDLE_BUTTON) return;
            event.stopPropagation();
            // A WINDOW'S OWN BUTTON CLOSES IT; an application's opens another, as Windows' middle-click does
            if (entry.frame != null) entry.frame.requestClose();
            else if (entry.kind != null) DesktopCommands.open(desktop, entry.kind);
        }, false, false);
    }

    /**
     * Moves a dragged entry to just before {@code before}, or to the end of its own run when that is null.
     *
     * <p>A pinned application's entry reorders the pins, which are saved; an unpinned window moves among
     * the unpinned for this session. Neither crosses into the other: dragging a window among the pins does
     * not pin it.</p>
     */
    void move(UIElement dragged, @Nullable UIElement before) {
        Desktop desktop = desktop();
        if (desktop == null || !(dragged instanceof Entry entry)) return;
        if (!combined) {
            moveAlone(desktop, entry, before);
            return;
        }
        String pinned = pinnedIdOf(desktop, entry);
        String beforePin = before instanceof Entry other ? pinnedIdOf(desktop, other) : null;
        if (pinned != null) {
            // Past the last pin, into the unpinned run: the end of the pins
            desktop.pins().move(pinned, beforePin);
        } else if (beforePin == null && entry.anyWindow() != null) {
            WindowFrame after = before instanceof Entry other ? other.anyWindow() : null;
            desktop.registry().moveInTaskbar(entry.anyWindow(), after);
        }
    }

    /** {@code successor}'s button where {@code was}'s is: an application's window taking its starting window's place. */
    public void takePlace(WindowFrame successor, WindowFrame was) {
        int at = sessionOrder.indexOf(was);
        if (at < 0) return;
        sessionOrder.remove(successor);
        sessionOrder.set(sessionOrder.indexOf(was), successor);
    }

    /**
     * An uncombined bar's move: the one button, in the session's order. A pinned application's buttons set the
     * pins' order by where their first button now sits, so the pins are saved as they were arranged.
     */
    private void moveAlone(Desktop desktop, Entry entry, @Nullable UIElement before) {
        Object key = keyOf(entry);
        if (key == null) return;
        Object beforeKey = before instanceof Entry other ? keyOf(other) : null;
        sessionOrder.remove(key);
        int at = beforeKey == null ? -1 : sessionOrder.indexOf(beforeKey);
        sessionOrder.add(at < 0 ? sessionOrder.size() : at, key);

        List<String> pins = desktop.pins().ids();
        List<String> arranged = new ArrayList<>();
        for (Object slot : sessionOrder) {
            String id = idOf(slot);
            if (id != null && pins.contains(id) && !arranged.contains(id)) arranged.add(id);
        }
        // A pin not on the bar (not installed this launch) keeps its place after the rest
        for (String id : pins) if (!arranged.contains(id)) arranged.add(id);
        if (!arranged.equals(pins)) desktop.pins().restore(arranged);
        else refresh();
    }

    @Nullable
    private Object keyOf(Entry entry) {
        for (Map.Entry<Object, Entry> held : entryOf.entrySet()) {
            if (held.getValue() == entry) return held.getKey();
        }
        return null;
    }

    /** The button after {@code entry}'s application's last one, or null at the end of the row. */
    @Nullable
    UIElement afterGroupOf(UIElement entry) {
        List<UIElement> row = entryRow();
        Object group = groupOf(entry);
        int last = -1;
        for (int i = 0; i < row.size(); i++) {
            if (groupOf(row.get(i)).equals(group)) last = i;
        }
        return last >= 0 && last + 1 < row.size() ? row.get(last + 1) : null;
    }

    /**
     * What an entry moves with: its application's id, or the entry itself for a window without one. Entries of
     * one application sit together and are dragged as one, while combined.
     */
    Object groupOf(UIElement entry) {
        if (entry instanceof Entry known) {
            // UNCOMBINED, every button is a group of its own: it moves alone
            if (!combined) return known;
            String id = known.applicationId();
            if (id != null) return id;
        }
        return entry;
    }

    @Nullable
    private static String pinnedIdOf(Desktop desktop, Entry entry) {
        String id = entry.applicationId();
        return id != null && desktop.pins().ids().contains(id) ? id : null;
    }

    /**
     * The window's icon, as a {@link WindowIcon} tile in the entry's pre-icon slot.
     *
     * <p><b>Every entry has one</b>, icon or not — a window without an icon gets its initial on the
     * neutral tile rather than an entry that starts at its label. The slot is built on the entry's first
     * refresh and kept, which is the same pattern the badge follows, and it is safe here for the reason
     * the badge's is: {@code setPreIcon} on an attached entry is a structural change the widget makes
     * about itself, not a child added from inside a parent's own attach.</p>
     */
    private void applyIcon(Button entry, @Nullable String iconName, String title) {
        UIElement slot = entry.getPreIcon();
        WindowIcon tile;
        if (slot instanceof WindowIcon existing) {
            tile = existing;
        } else {
            tile = new WindowIcon();
            tile.addClass(ICON_CLASS);
            entry.setPreIcon(tile);
        }
        tile.show(iconName, title);
    }

    /** On an attention entry between its pulses: the sheet fades it out. @see #setAttention */
    public static final String FLASH_OFF_CLASS = "__flash-off__";
    /** On an attention entry once its pulses are over: the sheet settles it to the tint it holds. */
    public static final String FLASH_HELD_CLASS = "__flash-held__";
    /**
     * Windows' flash, measured (FlashWindowEx, seven toggles): eleven pulses (counted on screen), one every 1.41 s,
     * each lit for 500 ms of it, then held lit at four fifths. The sheet's transitions are its rise and
     * fall: lit over 467 ms, unlit over 567 ms, held over 400 ms.
     */
    private static final long FLASH_PERIOD_NANOS = 1_413_000_000L, FLASH_LIT_NANOS = 500_000_000L,
            FLASH_SETTLE_NANOS = 667_000_000L;
    private static final int PULSES = 11;
    /** On an entry whose attention just ended: the sheet fades the orange out over {@link #FLASH_ENDING_NANOS}. */
    public static final String FLASH_ENDING_CLASS = "__flash-ending__";
    private static final long FLASH_ENDING_NANOS = 450_000_000L;

    /**
     * Lights {@code entry} for a window wanting attention, pulsing it as Windows' {@code FlashWindowEx} does a window
     * that finished in the background, then holding it lit; off, its pulsing stops with it.
     */
    private void setAttention(UIElement entry, boolean on) {
        boolean was = entry.hasClass(ATTENTION_CLASS);
        setClass(entry, ATTENTION_CLASS, on);
        if (!on) {
            entry.removeClass(FLASH_OFF_CLASS);
            entry.removeClass(FLASH_HELD_CLASS);
            UIDocument ending = entry.document();
            if (was && ending != null && WindowAnimator.isEnabled()) {
                // The orange fades out to the entry's own state instead of cutting
                entry.addClass(FLASH_ENDING_CLASS);
                long begun = System.nanoTime();
                ending.animation().every(entry, delta -> {
                    if (entry.hasClass(ATTENTION_CLASS) || System.nanoTime() - begun >= FLASH_ENDING_NANOS) {
                        entry.removeClass(FLASH_ENDING_CLASS);
                        return false;
                    }
                    return true;
                });
            }
        }
        if (!on || was) return;
        UIDocument document = entry.document();
        if (document == null || !WindowAnimator.isEnabled()) {
            entry.addClass(FLASH_HELD_CLASS);
            return;
        }
        // UNLIT FOR ITS FIRST STYLED FRAME, so the first pulse rises like the rest: an entry first resolved lit has
        // nothing to transition from and shows full orange at once.
        entry.addClass(FLASH_OFF_CLASS);
        boolean[] styled = {false};
        long start = System.nanoTime();
        document.animation().every(entry, delta -> {
            if (!entry.hasClass(ATTENTION_CLASS)) return false;
            if (!styled[0]) {
                styled[0] = true;
                return true;
            }
            long since = System.nanoTime() - start;
            long pulse = since / FLASH_PERIOD_NANOS, into = since % FLASH_PERIOD_NANOS;
            if (pulse >= PULSES - 1 && into >= FLASH_SETTLE_NANOS || pulse >= PULSES) {
                entry.removeClass(FLASH_OFF_CLASS);
                entry.addClass(FLASH_HELD_CLASS);
                return false;
            }
            // The last pulse is not put out: it settles from lit to held
            setClass(entry, FLASH_OFF_CLASS, pulse < PULSES - 1 && into >= FLASH_LIT_NANOS);
            return true;
        });
    }

    private static void setClass(UIElement element, String cls, boolean on) {
        if (on) element.addClass(cls);
        else element.removeClass(cls);
    }

    /** Hides the whole strip — what fullscreen (W13) does, and the reason the work area is a layout box
     * rather than a subtraction somebody has to remember. */
    public void setBarVisible(boolean visible) {
        if (visible == barShown) return;
        barShown = visible;
        stopMotion();
        if (visible) {
            removeClass(LEAVING_CLASS);
            setDisplayed(true);
            playEnter();
            return;
        }
        // OUT OF FLOW WHILE IT LEAVES, so the work area takes the bar's row at once and the bar slides away over it.
        addClass(LEAVING_CLASS);
        motion = TaskbarMotion.leave(this, edge, buttons(), false, this::gone);
    }

    /**
     * The desktop is closing: the bar slides off and stays off until {@link #comeOnScreen}, then {@code done}, at once
     * when it is not on screen or animations are off.
     */
    public void leaveScreen(Runnable done) {
        stopMotion();
        if (!barShown || box() == null) {
            done.run();
            return;
        }
        motion = TaskbarMotion.leave(this, edge, buttons(), true, done);
    }

    /** The desktop is opening again: the bar comes in, its buttons rising into it. */
    public void comeOnScreen() {
        if (barShown) playEnter();
        else stopMotion();
    }

    /** On an entry while its jump list is open, which the sheet lights as the active entry hovered. */
    public static final String MENU_OPEN_CLASS = "__menu-open__";

    /** On a button while it rises in with the bar, which the sheet gives a picture of its own. */
    public static final String RISING_CLASS = "__rising__";

    /** On the bar while it slides off screen, which the sheet takes out of flow. */
    public static final String LEAVING_CLASS = "__leaving__";

    /** Whether the bar is meant to be on screen: false from the moment it starts leaving. */
    private boolean barShown = true;
    /** True for the refresh of the bar coming on screen, whose entries rise in with it rather than opening the row. */
    private boolean arriving;
    @Nullable
    private TaskbarMotion motion;
    /** The light along the bar's top, which draws in and out with it. */
    private final UIElement edge = new UIElement();

    /** Joined the tree: brought up to date, and comes on screen. */
    private void arrive() {
        arriving = barShown && WindowAnimator.isEnabled();
        try {
            refresh();
        } finally {
            arriving = false;
        }
        stopMotion();
        // LEFT THE TREE WHILE LEAVING, whose end never ran.
        if (!barShown) gone();
        else playEnter();
    }

    private void playEnter() {
        stopMotion();
        motion = TaskbarMotion.enter(this, edge, buttons());
    }

    /** The start button, then the entries: the order they rise in. */
    private List<UIElement> buttons() {
        List<UIElement> buttons = new ArrayList<>();
        buttons.add(start);
        buttons.addAll(entryRow());
        return buttons;
    }

    private void gone() {
        removeClass(LEAVING_CLASS);
        // `setDisplayed`, the engine's own attribute: the box tree gives a hidden node no box.
        setDisplayed(false);
    }

    private void stopMotion() {
        if (motion == null) return;
        motion.stop();
        motion = null;
    }

    /** The window a hover preview is currently showing, or null. @see TaskbarPreviews */
    @Nullable
    WindowFrame previewedWindow() {
        return previews.showingFrame();
    }

    /**
     * The strip {@code element} is in, or null — its nearest taskbar ancestor.
     *
     * <p>The same walk {@link WindowFrame#of} does, and here for the same reason: it is a question about
     * a widget, so it belongs beside the widget rather than in whichever consumer needed it first. Both
     * take the DOM chain, which for anything that can be <b>promoted</b> is the only chain that answers
     * truthfully — promotion moves a Taffy node and never a DOM parent.</p>
     *
     * <p><b>It does not answer for a menu the strip opened</b>, which is the case worth stating because
     * it looks like it should. A promoted popover's DOM parent is the overlay host and not the element
     * it was invoked from, so walking out of a jump list reaches the root without ever meeting the
     * taskbar — {@code SystemMenu} therefore remembers the strip it suppressed rather than re-deriving
     * it on the way out.</p>
     */
    @Nullable
    public static Taskbar of(@Nullable UIElement element) {
        for (UIElement el = element; el != null; el = el.parentElement()) {
            if (el instanceof Taskbar) return (Taskbar) el;
        }
        return null;
    }

    /** The entry for {@code frame}, or null. */
    @Nullable
    public Button entryFor(WindowFrame frame) {
        Entry own = entryOf.get(frame);
        if (own != null) return own;
        // Combined: the application's button stands for it
        for (Entry entry : entryOf.values()) {
            if (entry.windows.contains(frame)) return entry;
        }
        return null;
    }

    /** The island the entries sit in. */
    public UIElement entries() {
        return entries;
    }

    /** A bare button with a hairline in its shadow tree, which a Button refuses as a public child. */
    private static final class ShowDesktopButton extends Button {
        ShowDesktopButton() {
            super(Button.NAME, "");
            UIElement line = new UIElement();
            line.set(Attribute.PART, SHOW_DESKTOP_LINE_PART);
            line.setHitTest(false);
            shadow().append(line);
        }
    }

    /**
     * One entry, which knows what it stands for: a window, or a pinned application with none.
     *
     * <h3>An entry is NOT inside the window it represents, and that is the whole reason this exists</h3>
     *
     * <p>{@code DataContext}'s walk goes outward from the element a command was invoked on. For a caption
     * button that reaches the frame in two steps; from a taskbar entry it reaches the taskbar, then the
     * desktop — which answers with the <b>active</b> window. So right-clicking a background entry and
     * choosing Close would close whatever window was in front instead. Answering
     * {@link WindowFrame#WINDOW_FRAME} itself puts the entry ahead of the desktop in the walk, and
     * {@link UiDataKeys#APPLICATION_KIND} names what Pin acts on.</p>
     */
    private static final class Entry extends Button implements DataProvider {

        @Nullable
        private WindowFrame frame;
        /** The application an application's own button stands for; null on a window's own button. */
        @Nullable
        private ApplicationKind kind;
        /** An application's button: its windows this refresh, empty for a pin with none. */
        private List<WindowFrame> windows = List.of();
        /** Its name on hover, set once it is wired. */
        private Tooltip tooltip;

        Entry(@Nullable WindowFrame frame, @Nullable ApplicationKind kind) {
            // BUTTON'S OWN NAME, which is the one case where inheriting a supertype's tag is right: without
            // it an Entry matches none of the `button` rules that make it look like a control.
            super(Button.NAME, frame != null ? frame.getTitle() : kind != null ? kind.displayName() : "");
            this.frame = frame;
            this.kind = kind;
            // THE RUNNING INDICATOR, built here and never later: a Button refuses public children, and
            // an internal one added from inside the strip's refresh would be inserted into a parent whose
            // children are mid-registration. Absolute in the sheet, centred by `left: 50%` plus a
            // translate. In the SHADOW TREE: a Button's shadow root ends in a slot, so a light child would
            // land in the flow after the label.
            UIElement indicator = new UIElement();
            indicator.addClass(INDICATOR_CLASS);
            indicator.set(Attribute.PART, INDICATOR_PART);
            indicator.setHitTest(false);
            shadow().append(indicator);
        }

        /** Hands this entry to a window, or to its application's own button. */
        void standFor(@Nullable WindowFrame frame, @Nullable ApplicationKind kind) {
            this.frame = frame;
            this.kind = kind;
            if (frame != null) windows = List.of();
        }

        /** What its previews show: the window, or every window of its application. */
        List<WindowFrame> shown() {
            return frame != null ? List.of(frame) : windows;
        }

        @Nullable
        WindowFrame anyWindow() {
            return frame != null ? frame : windows.isEmpty() ? null : windows.get(0);
        }

        /** The window a menu acts on: its own, or the one of its application's that was active last. */
        @Nullable
        WindowFrame subject() {
            if (frame != null || windows.isEmpty()) return frame;
            Desktop desktop = windows.get(0).desktop();
            if (desktop == null) return windows.get(0);
            for (WindowFrame recent : desktop.registry().mruOrder()) {
                if (windows.contains(recent)) return recent;
            }
            return windows.get(0);
        }

        @Nullable
        String applicationId() {
            if (kind != null) return kind.id();
            return frame == null ? null : Taskbar.applicationId(frame);
        }

        @Override
        @Nullable
        public Object getData(DataKey<?> key) {
            if (key == WindowFrame.WINDOW_FRAME) return subject();
            if (key == UiDataKeys.APPLICATION_KIND) return frame != null ? frame.application() : kind;
            if (key == UiDataKeys.WINDOWS) return shown();
            return null;
        }

    }
}
