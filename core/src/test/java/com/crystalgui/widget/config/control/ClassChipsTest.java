package com.crystalgui.widget.config.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgKeyCodes;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.widget.control.TextField;

/** <b>L4.9 — the class editor's own rules</b>: what goes in, what comes out, and what is offered. */
public class ClassChipsTest extends UiDocumentTestBase {

    private final Property<List<String>> value = Property.of(List.of("title"));
    private ClassChips chips;

    @Before
    public void build() {
        chips = new ClassChips(ConfigDescriptor.of("classes", "classes", ConfigDescriptor.Kind.ARRAY), value.get());
        chips.setAccepts(name -> !name.startsWith("__"));
        chips.setSuggestions(() -> List.of("wide", "window", "narrow", "title", "__engine__"));
        chips.bind(value);
        document.append(chips);
        frame();
    }

    @Test
    public void typedNamesGoInSeveralAtATimeOnceEach() {
        chips.add("wide  narrow wide title __engine__");

        assertEquals(List.of("title", "wide", "narrow"), value.get());
        assertEquals(List.of("title", "wide", "narrow"), chips.chipNames());
    }

    @Test
    public void aChipIsRemovedAndBackspaceInAnEmptyPromptTakesTheLast() {
        chips.add("wide narrow");
        chips.remove("wide");
        assertEquals(List.of("title", "narrow"), value.get());

        key(chips.prompt(), CgKeyCodes.KEY_BACK);
        assertEquals(List.of("title"), value.get());
    }

    @Test
    public void enterAddsWhatWasTyped() {
        chips.prompt().setText("hero");
        key(chips.prompt(), CgKeyCodes.KEY_RETURN);

        assertEquals(List.of("title", "hero"), value.get());
        assertEquals("", chips.prompt().getText());
    }

    /** Prefix matches first, then names containing it; nothing present, refused or already typed in full. */
    @Test
    public void suggestionsRankPrefixBeforeContainsAndSkipWhatCannotBeAdded() {
        assertEquals("prefix first, then containing", List.of("wide", "window", "narrow"), chips.suggestionsFor("w"));
        assertEquals("the last word is what completes", List.of("narrow"), chips.suggestionsFor("wide nar"));
        assertEquals("title is already a chip", List.of(), chips.suggestionsFor("tit"));
        assertEquals(List.of("window"), chips.suggestionsFor("ndo"));
        assertEquals(List.of(), chips.suggestionsFor("__eng"));
        assertTrue(chips.suggestionsFor("").isEmpty());
    }

    @Test
    public void aFlaggedNameIsDrawnFlagged() {
        chips.setFlagged(name -> name.equals("title"));

        assertTrue(chips.isFlagged("title"));
        chips.add("wide");
        assertFalse(chips.isFlagged("wide"));
    }

    private void key(TextField target, int keyCode) {
        document.input().send(target, new KeyboardEvent.Down(target, keyCode, '\0', false, 0, 0L));
        frame();
    }
}
