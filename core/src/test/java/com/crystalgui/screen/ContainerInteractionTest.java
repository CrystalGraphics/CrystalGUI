package com.crystalgui.screen;

import com.crystalgraphics.mc.container.Action;
import com.crystalgraphics.mc.container.CgContainers;
import com.crystalgraphics.mc.container.ClickType;
import com.crystalgraphics.mc.container.SlotKind;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Vanilla's container-screen gestures, each as the clicks it sends: the menu is plain data, no game and no document. */
public class ContainerInteractionTest {

    private static final int OUTSIDE = CgContainers.OUTSIDE;
    private static final int NONE = ContainerInteraction.NONE;

    private CgItemStack carried = CgItemStack.EMPTY;
    private final CgItemStack[] stacks = {CgItemStack.of("minecraft:stone", 8), CgItemStack.EMPTY, CgItemStack.of("minecraft:stone", 3),
            CgItemStack.of("minecraft:dirt", 1), CgItemStack.EMPTY};
    private final List<Action.Click> sent = new ArrayList<>();
    private long now = 1000;
    private final ContainerInteraction input = new ContainerInteraction(this::menu, slot -> menu().slot(slot).kind() != SlotKind.RESULT,
            sent::add, () -> now);

    private CgContainers.Menu menu() {
        List<CgContainers.Slot> slots = new ArrayList<>();
        for (int i = 0; i < stacks.length; i++) {
            // slot 4 is a result; slots 0-3 share one container
            CgContainers.Layout layout = new CgContainers.Layout(i == 4 ? 4 : 0, i, 8 + 18 * i, 8, i == 4 ? SlotKind.RESULT : SlotKind.CONTAINER, 64);
            slots.add(new CgContainers.Slot(i, stacks[i], layout, true));
        }
        return new CgContainers.Menu(3, "minecraft:generic_9x1", "Chest", slots, carried, new CgNbtCompound());
    }

    private void assertSent(int slot, int button, ClickType type) {
        assertEquals("one click", 1, sent.size());
        assertEquals(new Action.Click(slot, button, type), sent.get(0));
    }

    @Test
    public void aLeftClickWithAnEmptyCursorPicksUpAtOnceAndItsReleaseIsSwallowed() {
        input.press(0, 0, false);
        input.release(0, 0, false);

        assertSent(0, 0, ClickType.PICKUP);
    }

    @Test
    public void aRightClickPicksUpHalf() {
        input.press(0, 1, false);
        input.release(0, 1, false);

        assertSent(0, 1, ClickType.PICKUP);
    }

    @Test
    public void shiftClickQuickMoves() {
        input.press(0, 0, true);
        input.release(0, 0, true);

        assertSent(0, 0, ClickType.QUICK_MOVE);
    }

    @Test
    public void aMiddleClickClones() {
        input.press(0, 2, false);
        input.release(0, 2, false);

        assertSent(0, 2, ClickType.CLONE);
    }

    @Test
    public void aClickOnNothingInsideThePanelDoesNothing() {
        input.press(NONE, 0, false);
        input.release(NONE, 0, false);

        assertTrue(sent.isEmpty());
    }

    @Test
    public void aClickOutsideWithAnEmptyCursorThrows() {
        input.press(OUTSIDE, 0, false);
        input.release(OUTSIDE, 0, false);

        assertSent(OUTSIDE, 0, ClickType.THROW);
    }

    @Test
    public void aCarryingCursorPlacesOnTheReleaseAsOneClick() {
        carried = CgItemStack.of("minecraft:stone", 2);

        input.press(1, 0, false);
        assertTrue("a press while carrying only starts a drag", sent.isEmpty());
        input.release(1, 0, false);

        assertSent(1, 0, ClickType.PICKUP);
    }

    @Test
    public void aCarryingReleaseOutsideDropsTheStack() {
        carried = CgItemStack.of("minecraft:stone", 2);

        input.press(OUTSIDE, 0, false);
        input.release(OUTSIDE, 0, false);

        assertSent(OUTSIDE, 0, ClickType.PICKUP);
    }

    @Test
    public void aDragAcrossSlotsIsStartAddsAndEnd() {
        carried = CgItemStack.of("minecraft:stone", 5);

        input.press(1, 0, false);
        input.move(1);
        input.move(1);
        input.move(2);
        input.release(2, 0, false);

        assertEquals(4, sent.size());
        assertEquals(Action.Click.dragStart(0), sent.get(0));
        assertEquals("slot 1 once", Action.Click.dragAdd(1, 0), sent.get(1));
        assertEquals("slot 2 holds stone with room", Action.Click.dragAdd(2, 0), sent.get(2));
        assertEquals(Action.Click.dragEnd(0), sent.get(3));
    }

    @Test
    public void aDragSkipsASlotWithAnotherItemAndAResult() {
        carried = CgItemStack.of("minecraft:stone", 5);

        input.press(1, 0, false);
        input.move(3);          // dirt: not a match
        input.move(4);          // a result takes nothing
        input.move(1);
        input.release(1, 0, false);

        assertEquals(3, sent.size());
        assertEquals(Action.Click.dragAdd(1, 0), sent.get(1));
    }

    @Test
    public void aDragNeverHasMoreSlotsThanTheStackHasItems() {
        carried = CgItemStack.of("minecraft:stone", 1);

        input.press(1, 1, false);
        input.move(1);
        input.move(2);
        assertEquals(List.of(1), input.dragSlots());
        input.release(2, 1, false);

        assertEquals(Action.Click.dragStart(1), sent.get(0));
        assertEquals(3, sent.size());
    }

    @Test
    public void aMiddleDragAddsEverySlotWhatEverTheCount() {
        carried = CgItemStack.of("minecraft:stone", 1);

        input.press(1, 2, false);
        input.move(1);
        input.move(2);
        input.release(2, 2, false);

        assertEquals(4, sent.size());
        assertEquals(Action.Click.dragStart(2), sent.get(0));
    }

    @Test
    public void releasingADifferentButtonThanTheDragEndsItWithNothingSent() {
        carried = CgItemStack.of("minecraft:stone", 5);

        input.press(1, 0, false);
        input.move(1);
        input.release(1, 1, false);

        assertTrue(sent.isEmpty());
        assertFalse(input.dragging());
    }

    @Test
    public void aDoubleClickGathersWithPickupAll() {
        input.press(0, 0, false);
        input.release(0, 0, false);
        sent.clear();
        now += 100;
        carried = CgItemStack.of("minecraft:stone", 8);

        input.press(0, 0, false);
        input.release(0, 0, false);

        assertSent(0, 0, ClickType.PICKUP_ALL);
    }

    @Test
    public void twoClicksTooFarApartAreNotADoubleClick() {
        input.press(0, 0, false);
        input.release(0, 0, false);
        sent.clear();
        now += 400;

        input.press(0, 0, false);
        input.release(0, 0, false);

        assertSent(0, 0, ClickType.PICKUP);
    }

    @Test
    public void aDoubleClickOnAResultDoesNotGather() {
        input.press(4, 0, false);
        input.release(4, 0, false);
        sent.clear();
        now += 100;

        input.press(4, 0, false);
        input.release(4, 0, false);

        assertEquals(ClickType.PICKUP, sent.get(0).type());
        assertEquals(1, sent.size());
    }

    @Test
    public void aShiftDoubleClickQuickMovesEveryStackOfTheFirstOnesKindInItsContainer() {
        // a shift-click remembers what it moved
        input.press(0, 0, true);
        input.release(0, 0, true);
        sent.clear();
        now += 100;

        input.press(0, 0, true);
        input.release(0, 0, true);

        List<Integer> moved = new ArrayList<>();
        for (Action.Click c : sent) if (c.type() == ClickType.QUICK_MOVE) moved.add(c.slot());
        assertTrue("slots 0 and 2 hold stone: " + sent, moved.contains(0) && moved.contains(2));
        assertFalse("dirt is another kind", moved.contains(3));
    }

    @Test
    public void aNumberKeyOverASlotSwapsWithThatHotbarSlot() {
        assertTrue(input.hotbarKey(2, 4));

        assertSent(2, 4, ClickType.SWAP);
    }

    @Test
    public void theOffhandKeySwapsWithSlot40() {
        assertTrue(input.offhandKey(2));

        assertSent(2, 40, ClickType.SWAP);
    }

    @Test
    public void aNumberKeyWhileCarryingOrOverNothingDoesNothing() {
        assertFalse(input.hotbarKey(NONE, 1));
        carried = CgItemStack.of("minecraft:stone", 1);
        assertFalse(input.hotbarKey(2, 1));

        assertTrue(sent.isEmpty());
    }

    @Test
    public void theDropKeyThrowsOneAndWithControlTheWholeStack() {
        assertTrue(input.dropKey(0, false));
        assertTrue(input.dropKey(0, true));
        assertFalse("an empty slot has nothing to drop", input.dropKey(1, false));

        assertEquals(new Action.Click(0, 0, ClickType.THROW), sent.get(0));
        assertEquals(new Action.Click(0, 1, ClickType.THROW), sent.get(1));
        assertEquals(2, sent.size());
    }
}
