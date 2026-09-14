package com.crystalgui.core.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;

/** A typed record in a store: survives a re-read, announces only real changes, never destroys what it cannot read. */
public class ConfigRecordTest {

    record Shelf(boolean rows, String pinned) {
        static final Shelf EMPTY = new Shelf(false, "");

        static final Codec<Shelf> CODEC = new Codec<>() {
            @Override
            public <T> T encode(DynamicOps<T> ops, Shelf shelf) {
                return new StateMap<>(ops).putBool("rows", shelf.rows()).putString("pinned", shelf.pinned()).encode();
            }

            @Override
            public <T> Shelf decode(DynamicOps<T> ops, T input) {
                StateMap<T> map = new StateMap<>(ops, input);
                return new Shelf(map.getBool("rows", false), map.getString("pinned", ""));
            }
        };
    }

    @Test
    public void aChangeSurvivesANewRecordOnTheSameStore() {
        InMemoryConfigStorage store = new InMemoryConfigStorage();
        ConfigRecord.in(store, "shelf.json", Shelf.CODEC, Shelf.EMPTY).update(s -> new Shelf(true, "button"));

        assertEquals(new Shelf(true, "button"), ConfigRecord.in(store, "shelf.json", Shelf.CODEC, Shelf.EMPTY).get());
    }

    @Test
    public void anEqualValueWritesAndAnnouncesNothing() {
        InMemoryConfigStorage store = new InMemoryConfigStorage();
        ConfigRecord<Shelf> shelf = ConfigRecord.in(store, "shelf.json", Shelf.CODEC, Shelf.EMPTY);
        List<Shelf> heard = new ArrayList<>();
        shelf.onChanged.connect(heard::add);

        shelf.set(Shelf.EMPTY);
        assertTrue(heard.isEmpty());
        assertNull(store.read("shelf.json"));

        shelf.set(new Shelf(true, ""));
        assertEquals(List.of(new Shelf(true, "")), heard);
    }

    @Test
    public void anUnreadableFileGivesTheDefaultAndIsLeftAloneUntilAChange() {
        InMemoryConfigStorage store = new InMemoryConfigStorage();
        store.write("shelf.json", "{ \"rows\": tru");

        ConfigRecord<Shelf> shelf = ConfigRecord.in(store, "shelf.json", Shelf.CODEC, Shelf.EMPTY);
        assertEquals(Shelf.EMPTY, shelf.get());
        assertEquals("a file being edited by hand was overwritten by a read", "{ \"rows\": tru", store.read("shelf.json"));
    }

    @Test
    public void withNoStoreItIsKeptForTheSession() {
        ConfigRecord<Shelf> shelf = ConfigRecord.in(null, "shelf.json", Shelf.CODEC, Shelf.EMPTY);
        shelf.set(new Shelf(true, "x"));

        assertEquals(new Shelf(true, "x"), shelf.get());
    }
}
