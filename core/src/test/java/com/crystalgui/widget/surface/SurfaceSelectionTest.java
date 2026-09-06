package com.crystalgui.widget.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.select.SurfaceSelection;

/**
 * The selection model on its own: what it marks, what it announces, and the two things it drops.
 *
 * <p>The graph exercises the item half through its own tests; these cover what only the engine can be
 * asked — the secondary, {@code retain}, and one signal per operation however many items moved.</p>
 */
public class SurfaceSelectionTest {

    private final List<String> marks = new ArrayList<>();
    private int changes;

    private SurfaceSelection selection() {
        SurfaceSelection selection = new SurfaceSelection(
                (item, selected) -> marks.add(item.getId() + "=" + selected));
        selection.onChanged.connect(() -> changes++);
        return selection;
    }

    private static UIElement item(String id) {
        UIElement element = new UIElement();
        element.setId(id);
        return element;
    }

    @Test
    public void itMarksWhatChangedAndNothingElse() {
        SurfaceSelection selection = selection();
        UIElement a = item("a");
        UIElement b = item("b");

        selection.selectOnly(a);
        selection.add(b);
        selection.selectOnly(a);

        assertEquals(List.of("a=true", "b=true", "a=false", "b=false", "a=true"), marks);
    }

    /** Re-asserting the same single selection is not a change — a press on what is already picked. */
    @Test
    public void reselectingOneItemAnnouncesNothing() {
        SurfaceSelection selection = selection();
        UIElement a = item("a");

        selection.selectOnly(a);
        selection.selectOnly(a);

        assertEquals(1, changes);
    }

    /** However many items a marquee replaces, the consumers hear once. */
    @Test
    public void replacingManyIsOneAnnouncement() {
        SurfaceSelection selection = selection();
        selection.replaceWith(List.of(item("a"), item("b"), item("c")));

        assertEquals(1, changes);
        assertEquals(3, selection.items().size());
    }

    @Test
    public void itKeepsTheOrderThingsWerePicked() {
        SurfaceSelection selection = selection();
        UIElement a = item("a");
        UIElement b = item("b");
        selection.selectOnly(b);
        selection.add(a);

        assertEquals(List.of(b, a), selection.items());
    }

    /** A secondary and the items are never selected together, in either direction. */
    @Test
    public void theSecondaryAndTheItemsAreExclusive() {
        SurfaceSelection selection = selection();
        UIElement a = item("a");
        Object wire = new Object();

        selection.selectOnly(a);
        selection.selectSecondary(wire);
        assertEquals(List.of(), selection.items());
        assertEquals(wire, selection.secondary());
        assertEquals("the item was unmarked", List.of("a=true", "a=false"), marks);

        selection.selectOnly(a);
        assertNull(selection.secondary());
        assertEquals(1, selection.size());
    }

    /** What is gone stops being selected, and one signal covers both halves. */
    @Test
    public void retainDropsWhatIsGone() {
        SurfaceSelection selection = selection();
        UIElement kept = item("kept");
        UIElement gone = item("gone");
        selection.replaceWith(List.of(kept, gone));
        changes = 0;

        selection.retain(kept::equals, other -> true);

        assertEquals(List.of(kept), selection.items());
        assertEquals(1, changes);
    }

    @Test
    public void retainDropsTheSecondaryToo() {
        SurfaceSelection selection = selection();
        selection.selectSecondary(new Object());
        changes = 0;

        selection.retain(item -> true, other -> false);

        assertNull(selection.secondary());
        assertTrue(selection.isEmpty());
        assertEquals(1, changes);
    }

    /** Nothing to drop means nothing to announce — retain runs after every structural change. */
    @Test
    public void retainWithNothingGoneIsSilent() {
        SurfaceSelection selection = selection();
        selection.selectOnly(item("a"));
        changes = 0;

        selection.retain(item -> true, other -> true);

        assertEquals(0, changes);
    }

    @Test
    public void toggleAddsThenRemoves() {
        SurfaceSelection selection = selection();
        UIElement a = item("a");

        selection.toggle(a);
        assertTrue(selection.contains(a));
        selection.toggle(a);
        assertFalse(selection.contains(a));
        assertTrue(selection.isEmpty());
    }
}
