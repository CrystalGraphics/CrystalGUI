package com.crystalgui.ui.service;

import com.crystalgui.core.command.Command;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.desktop.DesktopCommands;
import com.crystalgui.workbench.dock.DockCommands;
import com.crystalgui.widget.texteditor.EditorCommands;
import com.crystalgui.widget.graph.GraphCommands;
import com.crystalgui.core.undo.UndoCommands;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.ui.input.keymap.KeyStroke;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import com.crystalgui.app.editor.CrystalEditorCommands;
import com.crystalgui.workbench.chrome.menu.ChromeCommands;
import com.crystalgui.workbench.explorer.ExplorerCommands;

/**
 * <b>Every shipped {@code bindDefaults} actually parses.</b>
 *
 * <p>A binding is a string, and {@link KeyStroke#parse} throws on a name it does not know. Nothing else
 * type-checks it, so a typo — or a key whose {@code CgKeyCodes} constant is spelled after a 1984 scancode
 * rather than after what is printed on the key — is a hard crash at the moment a scene calls
 * {@code install}, with the whole widget looking finished right up until then.</p>
 *
 * <p><b>This has now happened twice.</b> {@code Backspace} was missing because LWJGL2 calls it
 * {@code KEY_BACK}, and the fix was an alias plus a comment. {@code PageDown} was missing because LWJGL2
 * calls it {@code KEY_NEXT}, and the comment had not stopped it. So the second fix is this file: every
 * command class that ships defaults binds them here, and a new one is one line away from being covered.</p>
 */
public class ShippedKeymapDefaultsTest extends UiDocumentTestBase {

    /**
     * {@code bindDefaults} directly rather than {@code install}, because parsing the strings is the whole
     * risk and the {@code install} overloads differ per widget — {@code EditorCommands} wants a
     * {@code TextEditor}, which would drag a document and a font stack in for nothing.
     */
    private static Keymap freshKeymap() {
        return new UINode().keymap();
    }

    @Test
    public void graphDefaultsParse() {
        GraphCommands.bindDefaults(freshKeymap());
    }

    @Test
    public void editorDefaultsParse() {
        EditorCommands.bindDefaults(freshKeymap());
    }

    /**
     * The other place a shipped binding string lives: {@code Command.binding(...)}.
     *
     * <p>{@code UndoCommands} has no {@code bindDefaults} any more — its chords are declared on the
     * commands, so the only thing that parses them is {@code declaredBindings()}. Asking for it here
     * covers every declared spec in every bundle registered above, which is strictly more than the
     * per-widget cases and needs no line added when a bundle grows one.</p>
     */
    @Test
    public void declaredBindingsParse() {
        CommandRegistry.global().resetForTesting();
        UndoCommands.register();
        DockCommands.register();
        DesktopCommands.resetForTesting();
        DesktopCommands.register();
        GraphCommands.register();
        EditorCommands.register();
        CrystalEditorCommands.register();
        ChromeCommands.register();
        ExplorerCommands.register();
        assertNotNull(CommandRegistry.global().declaredBindings());
    }

    /** The explorer's bare keys stay element-scoped, so they still parse through a keymap. */
    @Test
    public void explorerDefaultsParse() {
        ExplorerCommands.bindDefaults(freshKeymap());
    }

    // ── The two names that have caught us out ───────────────────────────────────────────────────

    /**
     * The page keys, by the name printed on the key.
     *
     * <p>{@code CgKeyCodes} is LWJGL2-shaped and LWJGL2 named these after the PC/AT scancodes —
     * {@code PRIOR} and {@code NEXT}. That is a backend implementation detail leaking into a user-facing
     * string, and no keymap file anyone writes will use it.</p>
     */
    @Test
    public void thePageKeysBindByTheNameOnTheKey() {
        assertEquals(KeyStroke.parse("Prior"), KeyStroke.parse("PageUp"));
        assertEquals(KeyStroke.parse("Next"), KeyStroke.parse("PageDown"));
        assertEquals(KeyStroke.parse("PageUp"), KeyStroke.parse("PgUp"));
        assertEquals(KeyStroke.parse("PageDown"), KeyStroke.parse("PgDn"));
    }

    /** The first occurrence of the same leak, kept so a table rewrite cannot quietly drop it. */
    @Test
    public void backspaceBindsByTheNameOnTheKey() {
        assertEquals(KeyStroke.parse("Back"), KeyStroke.parse("Backspace"));
    }

    /**
     * An accelerator renders under the name printed on the key, not the one LWJGL2 used.
     *
     * <p>The parsing half of this leak was fixed twice; the <b>display</b> half was still live and only
     * became visible once something rendered accelerators. The command palette did, and listed real
     * bindings as {@code Ctrl+NEXT}, {@code Ctrl+PRIOR} and {@code Ctrl+Shift+BACKSLASH} — correct
     * strokes under names nobody can act on, in the column that exists precisely to teach the shortcut.</p>
     */
    @Test
    public void acceleratorsRenderUnderTheNameOnTheKey() {
        assertEquals("Ctrl+PageDown", KeyStroke.parse("Ctrl+PageDown").toString());
        assertEquals("Ctrl+PageUp", KeyStroke.parse("Ctrl+PgUp").toString());
        assertEquals("Ctrl+Shift+\\", KeyStroke.parse("Mod+Shift+Backslash").toString());
        assertEquals("Backspace", KeyStroke.parse("Back").toString());
        assertEquals("Enter", KeyStroke.parse("Return").toString());
    }

    /** Whatever {@code toString} prints must parse back to the same stroke — otherwise a keymap file
     * written from a rendered accelerator names a key the parser rejects. */
    @Test
    public void everyRenderedAcceleratorParsesBackToItself() {
        for (String chord : new String[]{"Mod+PageDown", "Mod+PageUp", "Mod+Shift+Backslash",
                "Mod+Backslash", "Back", "Return", "Escape", "Space", "Tab", "Mod+Minus"}) {
            KeyStroke original = KeyStroke.parse(chord);
            assertEquals("'" + original + "' does not parse back to itself",
                    original, KeyStroke.parse(original.toString()));
        }
    }

    /** An alias must resolve to something — a null value would silently vanish from the table. */
    @Test
    public void everyAliasResolves() {
        for (String name : new String[]{"Enter", "Esc", "Del", "Backspace", "PageUp", "PageDown",
                "PgUp", "PgDn", "Plus"}) {
            assertNotNull("alias '" + name + "' resolves to nothing", KeyStroke.parse(name));
        }
    }
}
