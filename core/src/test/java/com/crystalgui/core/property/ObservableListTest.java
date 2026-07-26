package com.crystalgui.core.property;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ObservableListTest {

    @Test
    public void addAppendsAndEmitsInsertChange() {
        ObservableList<String> list = new ObservableList<>();
        List<ObservableList.Change<String>> changes = new ArrayList<>();
        list.onChange(changes::add);

        list.add("a");
        list.add("b");

        assertEquals(Arrays.asList("a", "b"), list.asUnmodifiableList());
        assertEquals(2, changes.size());
        assertEquals(ObservableList.ChangeType.INSERT, changes.get(0).type);
        assertEquals(0, changes.get(0).index);
        assertEquals("a", changes.get(0).newValue);
        assertNull(changes.get(0).oldValue);
        assertEquals(1, changes.get(1).index);
        assertEquals("b", changes.get(1).newValue);
    }

    @Test
    public void addAtIndexInsertsAtPosition() {
        ObservableList<String> list = new ObservableList<>();
        list.add("a");
        list.add("c");
        list.add(1, "b");

        assertEquals(Arrays.asList("a", "b", "c"), list.asUnmodifiableList());
    }

    @Test
    public void removeAtEmitsRemoveChangeWithRemovedValue() {
        ObservableList<String> list = new ObservableList<>();
        list.add("a");
        list.add("b");

        List<ObservableList.Change<String>> changes = new ArrayList<>();
        list.onChange(changes::add);

        String removed = list.removeAt(0);

        assertEquals("a", removed);
        assertEquals(Arrays.asList("b"), list.asUnmodifiableList());
        assertEquals(1, changes.size());
        assertEquals(ObservableList.ChangeType.REMOVE, changes.get(0).type);
        assertEquals(0, changes.get(0).index);
        assertEquals("a", changes.get(0).oldValue);
        assertNull(changes.get(0).newValue);
    }

    @Test
    public void removeByValueFindsAndRemovesFirstOccurrence() {
        ObservableList<String> list = new ObservableList<>();
        list.add("a");
        list.add("b");
        list.add("a");

        boolean removed = list.remove("a");

        assertTrue(removed);
        assertEquals(Arrays.asList("b", "a"), list.asUnmodifiableList());
    }

    @Test
    public void removeByValueReturnsFalseWhenNotPresent() {
        ObservableList<String> list = new ObservableList<>();
        list.add("a");
        assertFalse(list.remove("not-here"));
    }

    @Test
    public void setEmitsUpdateChangeOnlyWhenValueActuallyDiffers() {
        ObservableList<String> list = new ObservableList<>();
        list.add("a");

        List<ObservableList.Change<String>> changes = new ArrayList<>();
        list.onChange(changes::add);

        list.set(0, "a"); // same value — no-op
        assertEquals(0, changes.size());

        list.set(0, "z"); // real change
        assertEquals(1, changes.size());
        assertEquals(ObservableList.ChangeType.UPDATE, changes.get(0).type);
        assertEquals("a", changes.get(0).oldValue);
        assertEquals("z", changes.get(0).newValue);
        assertEquals(Arrays.asList("z"), list.asUnmodifiableList());
    }

    @Test
    public void clearEmitsClearChangeAndEmptiesList() {
        ObservableList<String> list = new ObservableList<>();
        list.add("a");
        list.add("b");

        List<ObservableList.Change<String>> changes = new ArrayList<>();
        list.onChange(changes::add);

        list.clear();

        assertTrue(list.isEmpty());
        assertEquals(1, changes.size());
        assertEquals(ObservableList.ChangeType.CLEAR, changes.get(0).type);
        assertEquals(-1, changes.get(0).index);
    }

    @Test
    public void clearOnAlreadyEmptyListEmitsNothing() {
        ObservableList<String> list = new ObservableList<>();
        List<ObservableList.Change<String>> changes = new ArrayList<>();
        list.onChange(changes::add);

        list.clear();

        assertEquals(0, changes.size());
    }

    @Test
    public void disconnectingListenerStopsFurtherNotifications() {
        ObservableList<String> list = new ObservableList<>();
        List<ObservableList.Change<String>> changes = new ArrayList<>();
        var connection = list.onChange(changes::add);

        list.add("a");
        connection.disconnect();
        list.add("b");

        assertEquals(1, changes.size());
        assertEquals("a", changes.get(0).newValue);
    }
}
