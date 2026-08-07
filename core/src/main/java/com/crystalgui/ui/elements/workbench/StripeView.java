package com.crystalgui.ui.elements.workbench;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.AnchoredPlacement;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.DragGhost;
import com.crystalgui.ui.elements.InsertionMarker;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.dock.DockArea;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import com.crystalgui.ui.elements.dock.DockRegion;
import com.crystalgui.ui.elements.dock.RegionSide;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.UIDragController;
import com.crystalgui.ui.input.UIInputHandler;

import dev.vfyjxf.taffy.style.TaffyDisplay;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One of the two rails of tool-window buttons — IntelliJ's tool window stripe, VS Code's Activity Bar.
 *
 * <h3>Two of these, and the rail is derived</h3>
 *
 * <p>A workbench mounts a {@link StripeRail#LEFT} and a {@link StripeRail#RIGHT}, and neither owns a list.
 * Each asks, for every singleton panel type, whether {@code StripeRail.of(region, side)} names it — so a
 * button appears on the correct rail by <em>being asked again</em> rather than by being moved, and the two
 * rails cannot disagree about which of them holds a type. See {@link StripeRail} for the 2x2 and for why
 * placement is the primitive end of it.</p>
 *
 * <h3>What it is a view of</h3>
 *
 * <p>One button per <b>singleton</b> panel type — which is to say per container, since a tool window that
 * registered no views is a container holding itself ({@link ViewContainerRegistry}). That filter is stated
 * on the type: {@link DockPanelDescriptor#isSingleton()} distinguishes "one instance, hidden and reshown"
 * from "a document, of which there are many". Documents never appear on a rail in either editor — they live
 * in the editor area and are reached by opening a file — so a rail listing them would grow without bound and
 * duplicate the tab strip.</p>
 *
 * <h3>Every button runs a command</h3>
 *
 * <p>The rail holds no toggling logic of its own: a click runs {@code view.<typeId>} through the
 * {@link CommandRegistry}, which is the same command a keybinding or a menu item would run. This is
 * VS Code's arrangement exactly — the Activity Bar item for the Explorer and {@code Ctrl+Shift+E} both
 * invoke {@code workbench.view.explorer} — and it is the difference between a bar that <em>presents</em> a
 * capability and one that is a second, subtly different way to reach it. The second kind is where "the
 * button works but the shortcut doesn't" comes from.</p>
 *
 * <h3>State is announced, not polled</h3>
 *
 * <p>A button is {@code :checked} while its panel is open. That fact lives in the layout, which changes for
 * reasons a rail cannot see — a panel closed from its own header, a session restored, a container moved.
 * {@code DockArea.onDidChangeLayout} and {@link ToolWindowManager#onDidChangePlacement} are the two
 * announcements; this class used to poll all of it every frame, on the argument that the walk was cheap.
 * The cheapness argument was true and beside the point — the reason to stop polling is not cost but that a
 * frame is the wrong thing to be listening to.</p>
 *
 * <h3>Deliberately not here yet</h3>
 *
 * <p>Overflow into a {@code …} menu, reordering within a group, right-click hide, and {@code Alt+1..9}. All
 * are real parts of both originals and none changes the shape above.</p>
 */
public class StripeView extends UIElement {

    /**
     * The rail itself.
     *
     * <p>Still {@code __activity-bar__} though the class is {@code StripeView} now: it is VS Code's term
     * for this exact thing, a theme's selectors are a compatibility surface, and renaming it would buy
     * nothing but a chance to miss one.</p>
     */
    public static final String BAR_CLASS = "__activity-bar__";

    /** Which rail this is, so a sheet can put the two on opposite edges. */
    public static final String LEFT_CLASS = "__stripe-left__";
    public static final String RIGHT_CLASS = "__stripe-right__";

    /** The count over a rail icon. @see ItemButton#setBadge */
    public static final String BADGE_CLASS = "__badge__";

    /** One tool-window button. */
    public static final String ITEM_CLASS = "__activity-item__";

    /**
     * The stretch between the two groups, which is what pushes the bottom one to the foot of the rail.
     *
     * <h3>A spacer, after two goes at an auto margin</h3>
     *
     * <p>{@code margin-top: auto} is the obvious spelling and it is a trap here, because auto margins
     * <b>share</b> the free space with every other auto margin in the container. On the whole bottom group
     * it spread the group evenly down the rail; moved to just the first button it was correct until the
     * insertion gap could also lead that group, at which point two of them split the space and the rail
     * came apart in three places.</p>
     *
     * <p>One element that is always present and always between the groups cannot do any of that. Nothing
     * has to work out which button leads, so nothing can get it wrong while a drag is rearranging them.</p>
     */
    public static final String SPACER_CLASS = "__stripe-spacer__";

    /**
     * The rule between an anchor's two halves — IntelliJ's {@code StripeButtonSeparator}.
     *
     * <p>Not decoration. A rail's top group shows {@code PRIMARY} and {@code SECONDARY} as two contiguous
     * runs in one stripe, and a reorder only means something <em>inside</em> one of them — so without a
     * visible boundary the rail is five buttons you cannot freely rearrange, with no way to tell which
     * four are actually a list. That reads as a broken drag, and was reported as one: dragging the fourth
     * button below the fifth is impossible when the fifth is in the other half, and starts working the
     * moment the fifth is dragged across into the same one.</p>
     *
     * <p>Shown only when both halves have something in them, since a rule above or below nothing is a
     * boundary between one thing and no things.</p>
     */
    public static final String SEPARATOR_CLASS = "__stripe-separator__";

    /** Command ids are {@code view.} plus the panel type — {@code view.project}, {@code view.problems}. */
    public static final String COMMAND_PREFIX = "view.";

    /**
     * How far a tooltip sits off the rail, in logical pixels.
     *
     * <p>In Java rather than CSS because it is an argument to placement, not a box property — nothing in
     * the cascade positions a promoted popup, and {@code AnchoredPlacement} is deliberately the only thing
     * that writes {@code left}/{@code top} on one.</p>
     */
    private static final float TOOLTIP_GAP = 4f;

    private final Workbench workbench;
    private final StripeRail rail;

    /** Panel type → its button, for the types this rail currently owns. */
    private final Map<String, ItemButton> buttons = new LinkedHashMap<>();

    /** The stretch that pushes the bottom group to the foot of the rail. @see #SPACER_CLASS */
    private final UIElement spacer = new UIElement();

    /** The rule between the anchor's two halves. @see #SEPARATOR_CLASS */
    private final UIElement separator = new UIElement();

    /** The gap a drag opens where the button would land. @see InsertionMarker */
    private final InsertionMarker insertion =
            new InsertionMarker(InsertionMarker.Axis.VERTICAL).mode(InsertionMarker.Mode.IN_FLOW);

    /** The capsule that follows the cursor while a tool window is being moved. @see DragGhost */
    private final DragGhost dragGhost = new DragGhost();

    /** The type currently in flight from this rail, so it can be left out of its own drop list. */
    @Nullable
    private String dragging;

    // ── Subscriptions and deferred work ─────────────────────────────────────────────────────────

    @Nullable
    private Connection panelSubscription;

    @Nullable
    private Connection badgeSubscription;

    @Nullable
    private Connection placementSubscription;

    /** The registry a deferred sync runs against. @see #requestSync */
    @Nullable
    private CommandRegistry commands;

    private boolean pendingSync;
    private boolean ticking;

    public StripeView(Workbench workbench, StripeRail rail) {
        this.workbench = workbench;
        this.rail = rail;
        addClass(BAR_CLASS);
        addClass(rail == StripeRail.RIGHT ? RIGHT_CLASS : LEFT_CLASS);
        markAsInternal();
        // GRAB, not CURSOR. What is being dragged is the button, at the button's own size, so the ghost's
        // icon must stay exactly under the point of the button you took hold of -- a nudge below and right
        // makes it look like a different object appeared next to the one you grabbed.
        dragGhost.anchoredBy(DragGhost.Anchor.GRAB).parkIn(this);
        insertion.parkIn(this);
        spacer.addClass(SPACER_CLASS);
        spacer.setHitTest(false);
        addInternalChild(spacer);
        separator.addClass(SEPARATOR_CLASS);
        separator.setHitTest(false);
        addInternalChild(separator);
        // NO DROP TARGET HERE. It used to accept drops on the rail itself, which meant landing a drag on a
        // twenty-pixel stripe -- aiming at the control rather than at the place. The whole workbench is
        // the target now; see RegionDropOverlay.
    }

    public StripeRail rail() {
        return rail;
    }

    /** The rail builds its own buttons; it holds nothing a caller puts there. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public static String commandIdFor(String typeId) {
        return COMMAND_PREFIX + typeId;
    }

    /**
     * The {@code :checked} half. Subscribable immediately — it needs no window and no registry.
     *
     * <p>{@code :checked} <b>is</b> "is this panel open", which is derived from the layout, so it moves
     * exactly when the layout does and only a structural change can move it.</p>
     */
    void listenToLayout(DockArea dock) {
        dock.onDidChangeLayout.connect(this::refresh);
    }

    /**
     * Follows a live drag: the gap this rail opens, and — if the drag started here — its ghost's label.
     *
     * <p>Subscribed rather than driven, which is the half of this that used to be the other way round.
     * {@link RegionDropOverlay} resolves which slot the pointer means, because it is the one thing that
     * can; what a rail <em>does</em> about that is the rail's own business, and it owns both the parts
     * involved. It used to be reached into for them.</p>
     */
    void listenToDrag(RegionDropOverlay overlay) {
        overlay.onDidChangeAim.connect(this::onAim);
    }

    /**
     * Applies an aim to this rail's own parts.
     *
     * <p><b>The ghost is only this rail's business while {@code dragging} is set</b>, which is true on
     * exactly one rail: the one the drag started from, which is the one holding the ghost. That test
     * replaced asking the drag controller which element the live ghost happened to be — a question with a
     * correct answer and no owner.</p>
     */
    private void onAim(RegionDropOverlay.Aim aim) {
        if (dragging != null) {
            dragGhost.text(aim.slot() == null ? null : RegionDropZones.labelFor(aim.slot()));
            dragGhost.flipped(isRightOfCentre(aim.screenX()));
        }
        if (aim.slot() == null) {
            hideInsertion();
            return;
        }
        showInsertion(aim.slot().region(), aim.slot().side(), aim.screenX(), aim.screenY());
    }

    /**
     * Whether a pointer is past the middle of the window, which is what flips the ghost's label.
     *
     * <p>By position rather than by whether the text would fit: the label changes length as the
     * destination changes, so a fit test flips and unflips while the pointer sits still.</p>
     */
    private boolean isRightOfCentre(float screenX) {
        UIWindow window = getAttachedWindow();
        if (window == null) return false;
        UIElement root = window.ui.rootElement;
        var cache = root.getRuntimeCache();
        return root.screenToLocal(screenX, 0f).x - cache.getX() > cache.getWidth() / 2f;
    }

    /**
     * The buttons half, which needs a command registry and therefore a window.
     *
     * <p>Split from {@link #listenToLayout} because the two halves become available at different moments,
     * and folding them together would delay the layout half for no reason.</p>
     *
     * <p><b>Catches up before subscribing.</b> A signal only reports what happens after you subscribe,
     * and the workbench registers its own panel types in its constructor — long before there is a window.
     * Subscribing without the initial pass leaves the rail permanently empty of everything that existed
     * first, which is the failure mode of every "replace a poll with an event" change; it is the one this
     * landing actually hit, and it is why the order here is written down.</p>
     */
    void listenToPanels(DockPanelRegistry<UIElement> registry, CommandRegistry commands) {
        this.commands = commands;
        // HERE, not in the constructor. The rail is a FIELD INITIALISER on Workbench, so it is built
        // before the constructor body assigns the tool-window manager -- reaching for it there is a
        // guaranteed NPE, and one that only fires once something actually constructs a workbench.
        if (badgeSubscription == null) {
            // A badge is a fact about a CONTAINER, which is why it could not exist before containers did.
            badgeSubscription = workbench.toolWindowManager().viewContainers().onDidChangeBadge
                    .connect((containerId, text) -> {
                        ItemButton button = buttons.get(containerId);
                        if (button != null) button.setBadge(text);
                    });
        }
        if (placementSubscription == null) {
            // A MOVE IS TWO RAILS' BUSINESS. The rail losing the button and the rail gaining it both
            // re-ask, which is why neither needs to be told which of them it was.
            placementSubscription = workbench.toolWindowManager().onDidChangePlacement
                    .connect(typeId -> requestSync());
        }
        // Re-attaching a workbench to a second window calls this again, and the previous subscription
        // would otherwise still be live -- syncing the OLD window's registry forever. Connections are
        // Disposable now, so dropping the last one is one line rather than a flag.
        if (panelSubscription != null) panelSubscription.disconnect();
        sync(commands);
        panelSubscription = registry.onDidRegister.connect(descriptor -> sync(commands));
    }

    /**
     * Registers a toggle command for every singleton panel type, and gives this rail the buttons it owns.
     *
     * <p>Idempotent, so it is safe to call again after a host registers more panels — which it does:
     * {@code CrystalEditor} adds its inspector and emitted-source panels after the workbench is
     * constructed.</p>
     *
     * <p><b>It removes as well as adds.</b> A type this rail used to own may have been dragged to the other
     * one, and a sync that only ever added would leave the button on both rails — two buttons for one
     * container, each lighting up when it opened. Membership is asked freshly every time rather than
     * tracked, which is what makes the answer incapable of drifting.</p>
     */
    public void sync(CommandRegistry commands) {
        this.commands = commands;
        ToolWindowManager toolWindows = workbench.toolWindowManager();
        for (DockPanelDescriptor descriptor : workbench.panels().descriptors()) {
            if (!descriptor.isSingleton()) continue;
            String typeId = descriptor.typeId();

            // The COMMAND is ensured before the ownership check, not after. Conflating the two makes "not
            // my button" mean "not my command", and the RIGHT rail would then register only the commands
            // for the panels that happen to be anchored right -- a palette missing half its entries, on a
            // rail that looks perfectly correct.
            String commandId = commandIdFor(typeId);
            if (!commands.contains(commandId)) {
                commands.register(Command.of(commandId, descriptor.title())
                                         .run(() -> workbench.togglePanel(typeId)));
            }

            DockRegion region = toolWindows.regionOf(typeId);
            RegionSide side = toolWindows.sideOf(typeId);
            ItemButton existing = buttons.get(typeId);
            if (StripeRail.of(region, side) != rail) {
                if (existing != null) {
                    // removeInternalChild, never removeSelf: removeChild deliberately REFUSES an internal
                    // child, silently and by returning false, so the button would stay in the tree while
                    // this map forgot it -- and the next sync would try to add it again and throw.
                    removeInternalChild(existing);
                    buttons.remove(typeId);
                }
                continue;
            }

            ItemButton button = existing;
            if (button == null) {
                button = buildButton(descriptor, commandId, commands);
                buttons.put(typeId, button);
            }
            // NOTHING ELSE PER BUTTON. Which group it belongs to is not stamped on it: reorder() asks
            // slotButtons, so the answer is derived at layout time from the placement rather than cached
            // on the element where it could go stale. A `__bottom-anchored__` class used to live here and
            // was read by exactly one method, which is one more copy of the truth than is needed.
        }
        reorder();
        refresh();
    }

    private ItemButton buildButton(DockPanelDescriptor descriptor, String commandId,
                                   CommandRegistry commands) {
        ItemButton button = new ItemButton(workbench, descriptor.typeId(), descriptor.title());
        button.addClass(ITEM_CLASS);
        // Not in the tab sequence. This is the ARIA roving-tabindex case the engine already models: a rail
        // of eight buttons is one Tab press to skip past, not eight -- and every one of them is reachable
        // from the command palette anyway, which is the accessible path that matters.
        button.setFocusPolicy(FocusPolicy.CLICK_NOT_TABBABLE);
        applyIcon(button, descriptor.icon());
        // The label is the tooltip, because the button is icon-only. Without it the rail is a column of
        // glyphs with no way to learn what they are -- the one complaint the New UI's icon-only stripe
        // reliably attracts.
        //
        // TO THE SIDE, not below. The rail is one button wide, so a tooltip below covers the next button
        // down -- the one you were about to read. AnchoredPlacement still flips it when there is no room,
        // which is what makes the same call correct on the right-hand rail with no extra configuration.
        button.tooltip = Tooltip.attach(button, descriptor.title())
                .setSide(rail == StripeRail.RIGHT ? AnchoredPlacement.Side.LEFT
                                                  : AnchoredPlacement.Side.RIGHT)
                .setGap(TOOLTIP_GAP);
        button.attachListener(() -> commands.run(commandId));
        installButtonDrag(button, descriptor.icon());
        addInternalChild(button);
        return button;
    }

    /**
     * Lays the rail out: the anchor's two halves, then the stretch, then the bottom group.
     *
     * <p>The whole arrangement is <b>derived from placement</b> every time rather than remembered on the
     * elements — which is what lets a button change region, half or order and simply appear in the right
     * place on the next sync.</p>
     *
     * <p>Re-parents the same instances rather than rebuilding them, and only when the order is actually
     * wrong — see {@link #requestSync()} for why this must never run inside the gesture that caused it.</p>
     */
    private void reorder() {
        List<UIElement> wanted = new ArrayList<>(buttons.size() + 1);
        // THE TOP GROUP IS TWO SLOTS, contiguous and in that order -- an anchor's two halves share one
        // stripe. Ordering the whole group by `order` instead interleaves them, so the halves stop being
        // runs and there is nowhere for a separator to go.
        List<ItemButton> primary = slotButtons(rail.topRegion(), RegionSide.PRIMARY);
        List<ItemButton> secondary = slotButtons(rail.topRegion(), RegionSide.SECONDARY);
        wanted.addAll(primary);
        if (!primary.isEmpty() && !secondary.isEmpty()) wanted.add(separator);
        wanted.addAll(secondary);
        // ALWAYS PRESENT, always between the groups -- see SPACER_CLASS. It is placed even when the bottom
        // group is empty, so opening one during a drag does not have to re-derive where the stretch goes.
        wanted.add(spacer);
        wanted.addAll(slotButtons(DockRegion.PANEL, rail.bottomSide()));

        int previous = -1;
        boolean correct = true;
        for (UIElement element : wanted) {
            int index = element.getSiblingIndex();
            if (index < 0 || index <= previous) {
                correct = false;
                break;
            }
            previous = index;
        }
        // THE SEPARATOR MAY NOT BE WANTED AT ALL, and an element left in the tree from a previous layout
        // would sit wherever it was appended. Taken out first, put back only if `wanted` names it.
        if (!wanted.contains(separator)) removeInternalChild(separator);
        if (correct) return;
        for (UIElement element : wanted) {
            removeInternalChild(element);
            addInternalChild(element);
        }
    }

    /** Whether this rail currently carries a button for {@code typeId}. */
    public boolean holds(String typeId) {
        return buttons.containsKey(typeId);
    }

    /**
     * This rail's button for a type, or null when it does not carry one.
     *
     * <p>For tests and diagnostics — the same role {@code ToolWindowManager.containerOf} plays. A drag
     * has to start somewhere real, and "press the Problems button" is not expressible without it.</p>
     */
    @Nullable
    public UIElement buttonFor(String typeId) {
        return buttons.get(typeId);
    }

    /**
     * This rail's buttons in one <b>slot</b> — one region and one half of it — in stripe order.
     *
     * <p>The unit a reorder means something in, and it is <em>narrower</em> than a rail group: a rail's top
     * group shows both halves of its anchor, because IntelliJ keeps them in one stripe with a separator
     * between. Computing an insertion index over the whole group and then applying it to one half is the
     * shape of "the drag refuses to put it where I asked" — the number is right about a list that is not
     * the list being renumbered.</p>
     *
     * <p>Sorted by {@code ToolWindowState.order()}, which is what a drop writes. Registration order agrees
     * with it until something is dragged, and a rail that kept reading registration order would ignore
     * every reorder it had just been told about.</p>
     */
    private List<ItemButton> slotButtons(DockRegion region, RegionSide side) {
        ToolWindowManager toolWindows = workbench.toolWindowManager();
        List<ItemButton> found = new ArrayList<>();
        for (ItemButton button : buttons.values()) {
            if (toolWindows.regionOf(button.typeId) == region
                    && toolWindows.sideOf(button.typeId) == side) {
                found.add(button);
            }
        }
        found.sort((a, b) -> Integer.compare(toolWindows.orderOf(a.typeId), toolWindows.orderOf(b.typeId)));
        return found;
    }

    /**
     * Shows where a drop would insert, and returns that index — or hides the marker when this rail is not
     * the target.
     *
     * <p>Driven by {@link RegionDropOverlay}, because the rail cannot see the drag: the pointer is captured
     * by the source, so a rail the drag has merely crossed is told nothing. The overlay already resolves
     * which slot the point means and is the only thing positioned to say "you are the target".</p>
     */
    int showInsertion(DockRegion region, RegionSide side, float screenX, float screenY) {
        if (StripeRail.of(region, side) != rail) {
            hideInsertion();
            return -1;
        }
        // WITHOUT THE ONE BEING CARRIED. It is hidden from the rail for the duration -- see beginDrag --
        // so it has no box to measure, and a zero-extent item in the list would make every midpoint test
        // after it answer against a cell that is not there. It is also simply not part of the list you are
        // inserting into.
        List<ItemButton> targets = slotButtons(region, side);
        targets.removeIf(button -> button.typeId.equals(dragging));
        return insertion.showFor(this, targets, screenX, screenY);
    }

    void hideInsertion() {
        insertion.hide();
    }

    /**
     * Takes the button out of the rail for the duration of a drag.
     *
     * <p>What IntelliJ does, and it is the other half of the slot reading correctly: the placeholder shows
     * the space the button would occupy, so leaving the button <em>also</em> sitting there means the rail
     * momentarily shows the same tool window twice — once where it is and once where it is going. Hiding it
     * frees exactly one cell, which is the cell the slot is drawn in.</p>
     *
     * <p>{@code display: none} rather than detaching it. Detaching the drag source is the one thing that
     * cannot be done here: {@code UIInputHandler.forgetElement} cancels a drag whose source leaves the
     * tree, so removing the button would end the gesture on its first frame.</p>
     */
    private void beginDrag(ItemButton button) {
        if (button.typeId.equals(dragging)) return;
        ToolWindowManager toolWindows = workbench.toolWindowManager();
        List<ItemButton> group =
                slotButtons(toolWindows.regionOf(button.typeId), toolWindows.sideOf(button.typeId));
        int wasAt = group.indexOf(button);
        dragging = button.typeId;
        StyleGroup.importantPipeline(button.getStyle().getLayoutGroup(),
                l -> l.display(TaffyDisplay.NONE));

        // THE GAP OPENS IN THE CELL THE BUTTON JUST LEFT, immediately, before any drop has been resolved.
        //
        // Not cosmetic -- it is what keeps the arithmetic honest. Hiding the button collapses the group by
        // one cell, so a pointer that has not moved is suddenly sitting in its NEIGHBOUR's cell, and the
        // midpoint rule answers with the neighbour's index. The symptom is a button that shuffles one place
        // down when you press and release without dragging at all, and a drag of exactly one place that
        // appears to do nothing because the two cancel.
        //
        // Putting the gap where the button was restores the group to the length it had, so at rest the
        // geometry is identical to the pre-drag layout and the index comes back unchanged.
        if (wasAt >= 0) {
            List<ItemButton> remaining = new ArrayList<>(group);
            remaining.remove(button);
            insertion.showAt(this, remaining, wasAt);
        }
    }

    /** Puts it back. Idempotent, and safe for a button this rail no longer owns. */
    private void endDrag() {
        if (dragging == null) return;
        ItemButton button = buttons.get(dragging);
        dragging = null;
        if (button == null) return;
        StyleGroup.importantPipeline(button.getStyle().getLayoutGroup(),
                l -> l.display(TaffyDisplay.FLEX));
    }

    /** The index the marker is currently showing, or {@code -1}. */
    int insertionIndex() {
        return insertion.index();
    }

    /** Lets every button's {@code :checked} state be re-evaluated if the layout has moved under it. */
    public void refresh() {
        for (ItemButton button : buttons.values()) button.revalidate();
    }

    // ── Deferred re-sync ────────────────────────────────────────────────────────────────────────

    /**
     * Re-syncs on the <b>next frame</b>, never now.
     *
     * <p>A placement change is announced from inside the drop handler of the drag that caused it, and a
     * sync re-parents buttons — including, on the losing rail, the very element the drag started from.
     * Doing that synchronously detaches the drag source mid-gesture, which is the rule this codebase has
     * paid for three times: {@code screenToLocal} goes stale and every later frame feeds the drag garbage.
     * {@code FileDecorations} routes through {@code pendingRefresh} for the same reason.</p>
     *
     * <p>The ticker is one-shot — registered on demand and dropped by returning {@code false} — so a rail
     * that nothing is moving costs no per-frame work at all.</p>
     */
    private void requestSync() {
        pendingSync = true;
        UIWindow window = getAttachedWindow();
        if (window == null || ticking) return;
        ticking = true;
        window.registerTicker(this::tickSync);
    }

    private boolean tickSync(float deltaSeconds) {
        ticking = false;
        if (pendingSync && commands != null) {
            pendingSync = false;
            sync(commands);
        }
        return false;
    }

    // ── Dragging a container between rails ──────────────────────────────────────────────────────

    /** What a stripe drag carries: the tool window being moved. */
    public record StripeDrag(String typeId) {
    }

    private void installButtonDrag(ItemButton button, @Nullable String iconName) {
        String typeId = button.typeId;
        button.events.getGroup(MouseEvent.Down.class).attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            // NEVER FROM THE KEYBOARD. Space and Enter on a focused element arrive as a synthesized
            // MouseEvent.Down carrying the PHYSICAL cursor position -- so activating a rail button from
            // the keyboard would start a drag anchored wherever the mouse happened to be resting, and one
            // that can never end, because capture is released by a real button-up that is not coming.
            if (event.getDetail() == UIInputHandler.KEYBOARD_DETAIL) return;
            UIWindow window = getAttachedWindow();
            if (window == null) return;

            // THE TOOLTIP GOES FIRST, and it will not go by itself. While a drag runs the pointer is
            // captured by its source, and capture substitutes hit testing "as if the pointer is always
            // over" that element -- so :hover stays pinned to this button for the whole gesture and its
            // tooltip sits there competing with the ghost, naming the thing you are already carrying.
            button.tooltip.hide();

            // THE ICON identifies what is being carried; the label is left EMPTY, because at drag start
            // the pointer is on the button and the only honest destination is "where it already is".
            // RegionDropOverlay writes one the moment the pointer resolves to a slot.
            dragGhost.follow(window, iconName, null);
            window.getInputHandler().getDragController().startDrag(button,
                    event.getPosition().x(), event.getPosition().y(), new StripeDrag(typeId),
                    new UIDragController.DragListener() {
                        @Override
                        public void onDragUpdate(float mx, float my, float sx, float sy,
                                                 float dx, float dy) {
                            // THE FIRST TICK IS WHERE THE DRAG BECOMES REAL, and that is why the button is
                            // hidden here rather than on mouse-down. A payload drag fires nothing until the
                            // pointer has passed the activation threshold, so a press that never really
                            // moved stays an ordinary click -- but hiding on the press made the button
                            // vanish the instant you touched it and come back if you let go. Idempotent, so
                            // the per-frame call costs a string comparison.
                            beginDrag(button);
                            // Nothing else per frame: where this would land is decided by DragEvent.Over on
                            // the WORKBENCH, dispatched against what is geometrically under the pointer.
                            // This listener is pinned to the source by capture and can never tell.
                        }

                        // THE HIGHLIGHT IS CLEARED HERE, from the drag's own ending, because these two
                        // callbacks are the only thing that covers every way a drag can stop. Clearing it
                        // in the Drop handler alone left the region lit after the tool window had already
                        // moved into it -- a successful drop is one of five endings, and the others reach
                        // neither Drop nor a Leave over the workbench.
                        @Override
                        public void onDragEnd(float mouseX, float mouseY) {
                            endDrag();
                            workbench.dropOverlay().clear();
                        }

                        @Override
                        public void onDragCancel() {
                            endDrag();
                            workbench.dropOverlay().clear();
                        }
                    });
        }, false, false);
    }

    /**
     * A button whose {@code :checked} state <b>is</b> whether its panel is open.
     *
     * <h3>Derived, never stored</h3>
     *
     * <p>{@code UIElement.isChecked()} is bound to the {@code :checked} pseudo-class, so overriding it is
     * the whole implementation — the same one line that makes {@code tab:checked} work. Deriving rather
     * than storing matters here more than usual: a panel can close for reasons the rail never sees (its own
     * header's ✕, a session restore, a container moved to the other rail), and a stored flag would be wrong
     * until something thought to correct it.</p>
     *
     * <p><b>The invalidation is the part that is easy to miss.</b> A pseudo-class is only re-evaluated when
     * something says the element's identity may have changed; without that the selector is matched once
     * and the rail stays lit for a panel that is long closed. This is the trap {@code nodeport:blank}
     * already documents. {@link #revalidate()} therefore compares against the last answer and invalidates
     * only on a change — so a settled frame costs one boolean comparison per button and touches nothing.</p>
     */
    private static final class ItemButton extends Button {

        private final Workbench workbench;
        private final String typeId;
        private final String title;
        private boolean lastKnownOpen;

        /**
         * Its hover label — kept, because a drag has to dismiss it.
         *
         * <p>Nothing else would. Pointer capture substitutes hit testing "as if the pointer is always over"
         * the drag source, so {@code :hover} stays pinned to this button for the whole gesture and the
         * tooltip sits there naming the thing already under the cursor on a ghost.</p>
         */
        private Tooltip tooltip;

        /** The count or dot over the icon — VS Code's activity badge. Absent until something sets one. */
        private final UIText badge = new UIText("");

        ItemButton(Workbench workbench, String typeId, String title) {
            super("");
            this.workbench = workbench;
            this.typeId = typeId;
            this.title = title;
            this.lastKnownOpen = workbench.isPanelOpen(typeId);
            badge.addClass(BADGE_CLASS);
            // Never a click target: the badge sits over the icon, and a press on it means the button.
            badge.setHitTest(false);
        }

        String title() {
            return title;
        }

        /**
         * Shows or clears the badge. Attached lazily, so an unbadged rail carries no extra elements.
         *
         * <p>{@code removeInternalChild}, not {@code removeSelf} — {@code removeChild} <b>refuses</b> an
         * internal child and says so by returning false, which nothing here was reading. Clearing a badge
         * therefore left it on screen <em>and</em> left {@code getParent()} non-null, so the lazy re-attach
         * below never fired again either: one stale count, permanently.</p>
         */
        void setBadge(@Nullable String text) {
            if (text == null || text.isEmpty()) {
                removeInternalChild(badge);
                return;
            }
            badge.setText(text);
            if (badge.getParent() == null) addInternalChild(badge);
        }

        @Override
        public boolean isChecked() {
            return workbench.isPanelOpen(typeId);
        }

        void revalidate() {
            boolean open = workbench.isPanelOpen(typeId);
            if (open == lastKnownOpen) return;
            lastKnownOpen = open;
            invalidateStyleMatch();
        }
    }

    private static void applyIcon(Button button, @Nullable String iconName) {
        if (iconName == null) return;
        CgUiSvg glyph = CgUiSvg.of(FileIconTheme.toResourcePath(FileIconTheme.withVariant(iconName)));
        if (glyph == null) return;
        UIElement slot = new UIElement();
        // Unhittable, so the press lands on the button rather than on its own icon -- click-focus targets
        // the exact element hit, never the nearest focusable ancestor.
        slot.setHitTest(false);
        StyleGroup.defaultPipeline(slot.getStyle().getGeneralGroup(), g -> g.overlay(glyph));
        button.setPreIcon(slot);
    }
}
