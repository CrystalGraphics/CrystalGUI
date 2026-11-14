package com.crystalgui.screen;

import com.crystalgraphics.mc.container.Action;
import com.crystalgraphics.mc.container.CgContainers;
import com.crystalgraphics.mc.container.ClickType;
import com.crystalgraphics.mc.container.SlotKind;
import com.crystalgraphics.mc.item.CgItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What a player's mouse and keys do to the slots of a container: vanilla's {@code AbstractContainerScreen} gestures
 * (click, shift-click, middle-click, drag across slots, double-click, number keys, the drop key) turned into the
 * {@link Action.Click}s vanilla's screen sends, once, for every container screen. It owns no widget and no game call.
 *
 * <pre>{@code
 * ContainerInteraction input = new ContainerInteraction(view::menu, slot -> true, view::act, System::currentTimeMillis);
 *
 * slotElement.onMouseDown.subscribe((el, e) -> input.press(slotIndex, e.getButtonId(), shift), false, true);
 * slotElement.onMouseEnter.subscribe((el, e) -> { if (held) input.move(slotIndex); }, false, true);
 * slotElement.onMouseUp.subscribe((el, e) -> input.release(slotIndex, e.getButtonId(), shift), false, true);
 * // outside the panel: press/release with CgContainers.OUTSIDE; inside it but over no slot: ContainerInteraction.NONE
 * input.hotbarKey(hoveredSlot, 2);                                // number key 3
 * input.dropKey(hoveredSlot, ctrl);
 * }</pre>
 *
 * <ul>
 *   <li>A slot argument is the menu's slot index, {@link #NONE} over no slot inside the panel, or
 *       {@link CgContainers#OUTSIDE} outside it.</li>
 *   <li>An empty cursor: a press clicks at once (a pick-up, a shift-click's quick move, a middle-click's clone) and its
 *       release is swallowed. A carrying cursor: a press starts a drag, {@link #move} adds each slot the cursor
 *       crosses that can take it, and the release sends the drag's three clicks, or one ordinary click if no slot was
 *       crossed.</li>
 *   <li>A second left press on the same slot within 250 ms makes its release a double-click (gather every match onto the
 *       cursor); with shift it quick-moves every stack of the first one's kind in that container.</li>
 *   <li>Where vanilla would send a click on slot -1 (a release over no slot while carrying) nothing is sent: the server
 *       ignores it.</li>
 *   <li>{@code mayPlace} here is asked of the game's slot in vanilla; a screen on a document thread cannot ask, so it
 *       passes what it knows (a result slot takes nothing) and the server, which checks every slot of a drag itself,
 *       settles the rest.</li>
 * </ul>
 */
public final class ContainerInteraction {

    /** Inside the panel, over no slot. */
    public static final int NONE = -1;

    private static final long DOUBLE_CLICK_MILLIS = 250;

    private final Supplier<CgContainers.Menu> menu;
    private final IntPredicate mayPlace;
    private final Consumer<Action.Click> out;
    private final LongSupplier clock;

    private int lastSlot = -2;
    private long lastTime;
    private int lastButton = -1;
    private boolean doubleClick;
    private boolean skipNextRelease;
    private boolean dragging;
    private int dragButton;
    private int dragType;
    private final List<Integer> dragSlots = new ArrayList<>();
    private CgItemStack lastQuickMoved = CgItemStack.EMPTY;

    /**
     * @param menu     the menu as it now stands, null when none
     * @param mayPlace whether the slot at an index can take the cursor's stack
     * @param out      where the clicks go
     * @param clock    milliseconds
     */
    public ContainerInteraction(Supplier<CgContainers.Menu> menu, IntPredicate mayPlace, Consumer<Action.Click> out, LongSupplier clock) {
        this.menu = menu;
        this.mayPlace = mayPlace;
        this.out = out;
        this.clock = clock;
    }

    /** The slots a drag has crossed so far, for a preview. Empty when not dragging. */
    public List<Integer> dragSlots() {
        return Collections.unmodifiableList(dragSlots);
    }

    public boolean dragging() {
        return dragging;
    }

    /** A mouse button went down over {@code slot}. Buttons: 0 left, 1 right, 2 middle. */
    public void press(int slot, int button, boolean shift) {
        CgContainers.Menu menu = this.menu.get();
        if (menu == null) return;
        long now = clock.getAsLong();
        int key = key(slot);
        doubleClick = key == lastSlot && now - lastTime < DOUBLE_CLICK_MILLIS && button == lastButton;
        skipNextRelease = false;
        if (button == 0 || button == 1 || button == 2) {
            if (slot != NONE) {
                if (dragging) {
                    // a second button during a drag changes nothing, as in vanilla
                } else if (menu.carried().isEmpty()) {
                    if (button == 2) {
                        click(slot, button, ClickType.CLONE);
                    } else {
                        boolean quick = slot != CgContainers.OUTSIDE && shift;
                        if (quick) lastQuickMoved = menu.slot(slot).stack();
                        click(slot, button, quick ? ClickType.QUICK_MOVE : slot == CgContainers.OUTSIDE ? ClickType.THROW : ClickType.PICKUP);
                    }
                    skipNextRelease = true;
                } else {
                    dragging = true;
                    dragButton = button;
                    dragType = button;
                    dragSlots.clear();
                }
            }
        }
        lastSlot = key;
        lastTime = now;
        lastButton = button;
    }

    /** The cursor moved onto {@code slot} with a button held: a carried stack is shared over each slot it crosses. */
    public void move(int slot) {
        CgContainers.Menu menu = this.menu.get();
        if (menu == null || !dragging || slot < 0 || slot >= menu.slots().size()) return;
        CgItemStack carried = menu.carried();
        if (carried.isEmpty() || dragSlots.contains(slot)) return;
        if ((carried.count() > dragSlots.size() || dragType == 2) && canReplace(menu.slot(slot), carried) && mayPlace.test(slot)) {
            dragSlots.add(slot);
        }
    }

    /** A mouse button came up over {@code slot}. */
    public void release(int slot, int button, boolean shift) {
        CgContainers.Menu menu = this.menu.get();
        if (menu == null) {
            reset();
            return;
        }
        CgItemStack carried = menu.carried();
        if (doubleClick && slot >= 0 && button == 0 && menu.slot(slot).kind() != SlotKind.RESULT) {
            if (shift) {
                if (!lastQuickMoved.isEmpty()) {
                    int group = menu.slot(slot).layout().container();
                    for (CgContainers.Slot other : menu.slots()) {
                        if (other.layout().container() == group && other.canTake() && !other.stack().isEmpty() && canReplace(other, lastQuickMoved)) {
                            click(other.index(), button, ClickType.QUICK_MOVE);
                        }
                    }
                }
            } else {
                click(slot, button, ClickType.PICKUP_ALL);
            }
            doubleClick = false;
            lastTime = 0;
        } else {
            if (dragging && dragButton != button) {
                reset();
                skipNextRelease = true;
                return;
            }
            if (skipNextRelease) {
                skipNextRelease = false;
                reset();
                return;
            }
            if (dragging && !dragSlots.isEmpty()) {
                out.accept(Action.Click.dragStart(dragType));
                for (int s : dragSlots) out.accept(Action.Click.dragAdd(s, dragType));
                out.accept(Action.Click.dragEnd(dragType));
            } else if (!carried.isEmpty() && slot != NONE) {
                if (button == 2) {
                    click(slot, button, ClickType.CLONE);
                } else {
                    boolean quick = slot != CgContainers.OUTSIDE && shift;
                    if (quick) lastQuickMoved = slot >= 0 && !menu.slot(slot).stack().isEmpty() ? menu.slot(slot).stack() : CgItemStack.EMPTY;
                    click(slot, button, quick ? ClickType.QUICK_MOVE : ClickType.PICKUP);
                }
            }
        }
        if (carried.isEmpty()) lastTime = 0;
        reset();
    }

    /** A number key (0-8 for keys 1-9) while hovering {@code slot}: swap it with that hotbar slot. Answers whether it acted. */
    public boolean hotbarKey(int slot, int n) {
        return swap(slot, n);
    }

    /** The offhand key while hovering {@code slot}: swap it with the offhand. */
    public boolean offhandKey(int slot) {
        return swap(slot, 40);
    }

    private boolean swap(int slot, int button) {
        CgContainers.Menu menu = this.menu.get();
        if (menu == null || slot < 0 || !menu.carried().isEmpty()) return false;
        click(slot, button, ClickType.SWAP);
        return true;
    }

    /** The drop key while hovering {@code slot}: one item, or with control the whole stack. Answers whether it acted. */
    public boolean dropKey(int slot, boolean control) {
        CgContainers.Menu menu = this.menu.get();
        if (menu == null || slot < 0 || menu.slot(slot).stack().isEmpty()) return false;
        click(slot, control ? 1 : 0, ClickType.THROW);
        return true;
    }

    private void reset() {
        dragging = false;
        dragSlots.clear();
    }

    private void click(int slot, int button, ClickType type) {
        out.accept(new Action.Click(slot, button, type));
    }

    private static int key(int slot) {
        return slot >= 0 ? slot : -1;
    }

    /** Vanilla's {@code canItemQuickReplace} with the stack size mattering: an empty slot, or the same item with room. */
    private static boolean canReplace(CgContainers.Slot slot, CgItemStack stack) {
        if (slot.stack().isEmpty()) return true;
        return slot.stack().stacksWith(stack) && slot.stack().count() <= slot.layout().maxStack();
    }
}
