package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.core.command.Command;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.ui.input.keymap.KeymapSheet;
import com.crystalgui.ui.input.keymap.KeyEventType;
import com.crystalgui.ui.input.keymap.KeyStroke;
import com.crystalgui.ui.input.keymap.KeymapResolver;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.1.2 — the keymap.
 *
 * <h3>The design in one line</h3>
 * <p>Bindings name a command <b>id</b> (VS Code, Blender, Unity and Unreal all agree), chords are
 * space-separated stroke sequences (VS Code), press/release is an axis (Blender's idea, not its enum),
 * and what decides whether a binding is active is <b>the focus path</b> — not a {@code when} expression
 * language, because unlike VS Code we have a real tree to walk.</p>
 *
 * <p>The rule these tests exist to protect: <b>one question, one mechanism.</b> Scope answers "does this
 * binding apply here"; {@code Command.isEnabled} answers "can it run right now"; nothing else gets a
 * vote, so nothing can disagree.</p>
 */
public class KeymapTest extends UiTestBase {

    private UIWindow window;
    private UIElement root;
    private final List<String> fired = new ArrayList<>();

    private UIElement build() {
        root = new UIElement().layout(l -> l.width(400).height(400));
        window = new UIWindow(Ui.of(root));
        window.init(800, 800);
        return root;
    }

    private Command recording(String id) {
        return Command.of(id, id).run(context -> fired.add(id));
    }

    private KeymapResolver resolver() {
        return window.getInputHandler().getKeymapResolver();
    }

    /** Drives the resolver directly. The full path through consumeKeyboardEvent needs a frame and a
     * platform input service; what these tests are about is resolution, not plumbing. */
    private boolean press(UIElement focused, String chordText) {
        boolean handled = false;
        for (KeyStroke stroke : KeyChord.parse(chordText).strokes()) {
            handled = resolver().resolve(focused, stroke, KeyEventType.PRESS, System.currentTimeMillis());
        }
        return handled;
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    @Test
    public void modifierOrderIsIrrelevantButRenderingIsCanonical() {
        KeyStroke a = KeyStroke.parse("Shift+Ctrl+P");
        KeyStroke b = KeyStroke.parse("Ctrl+Shift+P");
        assertEquals("the mask is a set, so order cannot matter", a, b);
        assertEquals("but one spelling renders, or a conflict report shows two identical-looking rows",
                "Ctrl+Shift+P", a.toString());
    }

    /** `Mod` is one token resolving per-platform, rather than VS Code's parallel `mac:` bindings — those
     * double every keymap file and let the two halves drift. */
    @Test
    public void modResolvesToTheHostPlatformsPrimaryModifier() {
        int expected = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")
                ? CgModifiers.SUPER
                : CgModifiers.CTRL;
        assertEquals(expected, KeyStroke.parse("Mod+S").modifiers());
    }

    @Test
    public void chordsAreSpaceSeparatedSequences() {
        KeyChord chord = KeyChord.parse("Mod+K Mod+S");
        assertEquals(2, chord.length());
        assertEquals(CgKeyCodes.KEY_K, chord.at(0).key());
        assertEquals(CgKeyCodes.KEY_S, chord.at(1).key());
    }

    @Test
    public void unknownKeysAndModifiersAreRejectedAtParseTime() {
        try {
            KeyStroke.parse("Mod+Nonsense");
            fail("an unknown key must not parse into a binding that can never fire");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Unknown key"));
        }
        try {
            KeyStroke.parse("Hyper+S");
            fail("an unknown modifier likewise");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Unknown modifier"));
        }
    }

    // ── Scope: the focus path, innermost first ───────────────────────────────

    /**
     * <b>The conflict the whole design exists to settle.</b>
     *
     * <p>A text field's {@code Mod+A} (select all text) must beat the window's {@code Mod+A} (select all
     * items), and neither may know the other exists. VS Code needs a {@code when} clause for this; here it
     * falls out of walking the focus path outward and taking the first match.</p>
     */
    @Test
    public void theInnermostScopeWins() {
        UIElement panel = build();
        UIElement inner = new UIElement();
        panel.addChild(inner);
        window.getCommands().register(recording("outer.selectAll")).register(recording("inner.selectAll"));
        panel.keymap().bind("Mod+A", "outer.selectAll");
        inner.keymap().bind("Mod+A", "inner.selectAll");

        assertTrue(press(inner, "Mod+A"));
        assertEquals(List.of("inner.selectAll"), fired);
    }

    /** An application-wide binding is not a special case — it is bound on the root, which is every
     * element's ancestor, so the same outward walk finds it. */
    @Test
    public void anAncestorBindingIsReachableFromADescendant() {
        UIElement rootEl = build();
        UIElement deep = new UIElement();
        UIElement deeper = new UIElement();
        rootEl.addChild(deep);
        deep.addChild(deeper);
        window.getCommands().register(recording("palette.open"));
        rootEl.keymap().bind("Mod+Shift+P", "palette.open");

        assertTrue(press(deeper, "Mod+Shift+P"));
        assertEquals(List.of("palette.open"), fired);
    }

    /**
     * <b>A binding is reachable only from inside the subtree that owns it — descendants are not
     * ancestors.</b>
     *
     * <p>Obvious stated plainly, and still the thing that made the gallery's "global" shortcut look
     * broken: it was bound on a <em>page</em>, so it fired while something inside that page held focus
     * and did nothing otherwise. The scoping was correct; the binding site was wrong. A shortcut that
     * must work with nothing focused has to live on the outermost scope there is.</p>
     */
    @Test
    public void aBindingOnADescendantIsNotReachableFromItsAncestor() {
        UIElement rootEl = build();
        UIElement page = new UIElement();
        rootEl.addChild(page);
        window.getCommands().register(recording("page.only"));
        page.keymap().bind("Mod+Shift+P", "page.only");

        assertFalse("resolving from the root must not descend into the page",
                press(rootEl, "Mod+Shift+P"));
        assertTrue(fired.isEmpty());

        assertTrue("but from inside the page it is found by walking outward",
                press(page, "Mod+Shift+P"));
        assertEquals(List.of("page.only"), fired);
    }

    @Test
    public void aSiblingsBindingIsNotVisible() {
        UIElement rootEl = build();
        UIElement a = new UIElement();
        UIElement b = new UIElement();
        rootEl.addChild(a);
        rootEl.addChild(b);
        window.getCommands().register(recording("a.only"));
        a.keymap().bind("Mod+J", "a.only");

        assertFalse("focus is in b, so a's binding must not fire", press(b, "Mod+J"));
        assertTrue(fired.isEmpty());
    }

    // ── Enablement lives on the command ──────────────────────────────────────

    /**
     * <b>One question, one mechanism.</b>
     *
     * <p>"Delete needs a selection" is a property of the command, not of the keystroke — and a greyed-out
     * menu item needs the identical answer. An earlier draft of this plan put a predicate on the
     * <em>binding</em>; that would have been a second activation mechanism alongside scoping, and two
     * mechanisms that can disagree about one question is exactly the failure to avoid.</p>
     */
    @Test
    public void aDisabledCommandDoesNotFire() {
        UIElement el = build();
        window.getCommands().register(recording("edit.delete").enabledWhen(context -> false));
        el.keymap().bind("Delete", "edit.delete");

        assertFalse(press(el, "Delete"));
        assertTrue(fired.isEmpty());
    }

    /** And a disabled inner command lets the key fall through to an outer scope that can handle it —
     * which is what makes enablement a routing decision rather than a dead end. */
    @Test
    public void aDisabledInnerCommandFallsThroughToAnOuterScope() {
        UIElement outer = build();
        UIElement inner = new UIElement();
        outer.addChild(inner);
        window.getCommands()
                .register(recording("inner.delete").enabledWhen(context -> false))
                .register(recording("outer.delete"));
        inner.keymap().bind("Delete", "inner.delete");
        outer.keymap().bind("Delete", "outer.delete");

        assertTrue(press(inner, "Delete"));
        assertEquals(List.of("outer.delete"), fired);
    }

    // ── The text-input guard ─────────────────────────────────────────────────

    /**
     * <b>A bare single-key binding must not fire while text input has focus.</b>
     *
     * <p>{@code B} selects the brush in Photoshop and types a "b" in a filename box. Without this guard
     * every tool shortcut corrupts every text field in the application — and it would look like a
     * TextField bug, not a keymap one.</p>
     */
    @Test
    public void bareKeysDoNotFireWhileTyping() {
        UIElement rootEl = build();
        TextField field = new TextField();
        rootEl.addChild(field);
        window.getCommands().register(recording("tool.brush"));
        rootEl.keymap().bind("B", "tool.brush");

        assertTrue("fixture must genuinely consume text input", field.consumesTextInput());
        assertFalse(press(field, "B"));
        assertTrue(fired.isEmpty());
    }

    /** Shift does not rescue it: `Shift+B` still types a capital B. */
    @Test
    public void shiftDoesNotCountAsASafeModifier() {
        UIElement rootEl = build();
        TextField field = new TextField();
        rootEl.addChild(field);
        window.getCommands().register(recording("tool.brush"));
        rootEl.keymap().bind("Shift+B", "tool.brush");

        assertFalse(press(field, "Shift+B"));
        assertTrue(fired.isEmpty());
    }

    /** A real modifier is unambiguous inside a text field, so it is never suppressed. */
    @Test
    public void modifiedKeysStillFireWhileTyping() {
        UIElement rootEl = build();
        TextField field = new TextField();
        rootEl.addChild(field);
        window.getCommands().register(recording("edit.save"));
        rootEl.keymap().bind("Mod+S", "edit.save");

        assertTrue(press(field, "Mod+S"));
        assertEquals(List.of("edit.save"), fired);
    }

    @Test
    public void aBareKeyCanOptInToFiringWhileTyping() {
        UIElement rootEl = build();
        TextField field = new TextField();
        rootEl.addChild(field);
        window.getCommands().register(recording("dialog.cancel"));
        rootEl.keymap().bind("Escape", "dialog.cancel").allowWhileTyping();

        assertTrue(press(field, "Escape"));
        assertEquals(List.of("dialog.cancel"), fired);
    }

    // ── Chords ───────────────────────────────────────────────────────────────

    @Test
    public void aChordFiresOnlyAfterItsFinalStroke() {
        UIElement el = build();
        window.getCommands().register(recording("edit.saveAll"));
        el.keymap().bind("Mod+K Mod+S", "edit.saveAll");

        long now = System.currentTimeMillis();
        assertTrue("the prefix is consumed, not ignored",
                resolver().resolve(el, KeyStroke.parse("Mod+K"), KeyEventType.PRESS, now));
        assertTrue(fired.isEmpty());
        assertNotNull("and is visible, so a status bar can say so", resolver().pending());

        assertTrue(resolver().resolve(el, KeyStroke.parse("Mod+S"), KeyEventType.PRESS, now));
        assertEquals(List.of("edit.saveAll"), fired);
        assertNull("pending clears once the chord completes", resolver().pending());
    }

    /** A complete match must beat a partial one, or `Mod+K` alone could never be bound alongside
     * `Mod+K Mod+S`. */
    @Test
    public void aCompleteMatchBeatsAPendingPrefix() {
        UIElement el = build();
        window.getCommands().register(recording("short")).register(recording("long"));
        el.keymap().bind("Mod+K Mod+S", "long");
        el.keymap().bind("Mod+K", "short");

        assertTrue(press(el, "Mod+K"));
        assertEquals(List.of("short"), fired);
        assertNull(resolver().pending());
    }

    @Test
    public void aStaleChordPrefixExpires() {
        UIElement el = build();
        window.getCommands().register(recording("edit.saveAll"));
        el.keymap().bind("Mod+K Mod+S", "edit.saveAll");

        resolver().resolve(el, KeyStroke.parse("Mod+K"), KeyEventType.PRESS, 0L);
        // Well past the timeout: the second stroke must start over, not complete a chord begun minutes ago.
        assertFalse(resolver().resolve(el, KeyStroke.parse("Mod+S"), KeyEventType.PRESS, 60_000L));
        assertTrue(fired.isEmpty());
    }

    /** A key that continues nothing kills the prefix — otherwise it would silently swallow the next
     * keystroke too. */
    @Test
    public void anUnrelatedKeyClearsThePrefix() {
        UIElement el = build();
        window.getCommands().register(recording("edit.saveAll"));
        el.keymap().bind("Mod+K Mod+S", "edit.saveAll");

        long now = System.currentTimeMillis();
        resolver().resolve(el, KeyStroke.parse("Mod+K"), KeyEventType.PRESS, now);
        assertFalse(resolver().resolve(el, KeyStroke.parse("Q"), KeyEventType.PRESS, now));
        assertNull(resolver().pending());
    }

    // ── Press vs release ─────────────────────────────────────────────────────

    /** The axis space-to-pan is made of: a PRESS binding starts the gesture, a RELEASE binding ends it.
     * Blender's idea; only the two constants we can actually use. */
    @Test
    public void pressAndReleaseAreSeparateBindings() {
        UIElement el = build();
        window.getCommands().register(recording("pan.begin")).register(recording("pan.end"));
        el.keymap().bind("Space", "pan.begin");
        el.keymap().bind("Space", "pan.end").on(KeyEventType.RELEASE);

        long now = System.currentTimeMillis();
        resolver().resolve(el, KeyStroke.parse("Space"), KeyEventType.PRESS, now);
        assertEquals(List.of("pan.begin"), fired);

        resolver().resolve(el, KeyStroke.parse("Space"), KeyEventType.RELEASE, now);
        assertEquals(List.of("pan.begin", "pan.end"), fired);
    }

    // ── Reverse lookup: what a menu item renders ─────────────────────────────

    /**
     * <b>The accelerator a menu shows must be the one that actually fires.</b>
     *
     * <p>So the lookup walks the focus path outward taking the innermost match — the same walk, in the
     * same order, as {@link KeymapResolver}. Any cheaper implementation (a flat registry, first-found)
     * can drift from resolution, and the failure mode is a menu confidently advertising a shortcut that
     * does something else.</p>
     *
     * <p>This is also why a chord is not stored on the {@code Command}: the same id can be bound in
     * several scopes to different chords, so "the accelerator" is meaningless without asking from
     * somewhere.</p>
     */
    @Test
    public void theAcceleratorShownIsTheOneThatWouldFire() {
        UIElement outer = build();
        UIElement inner = new UIElement();
        outer.addChild(inner);
        outer.keymap().bind("Mod+S", "edit.save");
        inner.keymap().bind("Mod+Shift+S", "edit.save");

        assertEquals("innermost wins, exactly as resolution does",
                KeyChord.parse("Mod+Shift+S"), Keymap.acceleratorFor(inner, "edit.save"));
        assertEquals(KeyChord.parse("Mod+S"), Keymap.acceleratorFor(outer, "edit.save"));
    }

    /** Unbound is an ordinary answer — most commands never are, and a menu item just renders no
     * accelerator rather than treating it as an error. */
    @Test
    public void anUnboundCommandHasNoAccelerator() {
        UIElement el = build();
        assertNull(Keymap.acceleratorFor(el, "never.bound"));
        assertNull("and a null element is answerable too", Keymap.acceleratorFor(null, "anything"));
    }

    /** A binding on a sibling is not reachable, so it must not be advertised either — the label obeys the
     * same scoping the key does. */
    @Test
    public void anUnreachableBindingIsNotAdvertised() {
        UIElement rootEl = build();
        UIElement a = new UIElement();
        UIElement b = new UIElement();
        rootEl.addChild(a);
        rootEl.addChild(b);
        a.keymap().bind("Mod+J", "a.only");

        assertNull(Keymap.acceleratorFor(b, "a.only"));
    }

    /** Release bindings are real but are not accelerators: "Space (on release)" is not something a menu
     * says, and rendering the press half of a press/release pair twice would be worse. */
    @Test
    public void releaseBindingsAreNotAccelerators() {
        UIElement el = build();
        el.keymap().bind("Space", "pan.end").on(KeyEventType.RELEASE);
        assertNull(Keymap.acceleratorFor(el, "pan.end"));
    }

    /** What a command palette lists: everything reachable, innermost shadowing outer. */
    @Test
    public void thePaletteViewListsEverythingReachableWithInnermostWinning() {
        UIElement outer = build();
        UIElement inner = new UIElement();
        outer.addChild(inner);
        outer.keymap().bind("Mod+S", "edit.save");
        outer.keymap().bind("Mod+P", "palette.open");
        inner.keymap().bind("Mod+Shift+S", "edit.save");

        var all = Keymap.acceleratorsFrom(inner);
        assertEquals(2, all.size());
        assertEquals("the inner rebinding shadows the outer one, as pressing it would",
                KeyChord.parse("Mod+Shift+S"), all.get("edit.save"));
        assertEquals(KeyChord.parse("Mod+P"), all.get("palette.open"));
    }

    // ── Sheets: bindings as data ────────────────────────────────────────────

    /**
     * <b>The half that makes the command-id indirection worth anything.</b>
     *
     * <p>Bindings name a string rather than holding a lambda precisely so a sheet can be shipped as a
     * preset or written by a user. Until this existed the indirection was cost with no benefit — every
     * binding still had to be reachable from Java.</p>
     */
    @Test
    public void aSheetBindsFromJson() {
        UIElement el = build();
        window.getCommands().register(recording("edit.save")).register(recording("edit.saveAll"));
        el.keymap().load(KeymapSheet.parse("["
                + "{\"key\": \"Mod+S\", \"command\": \"edit.save\"},"
                + "{\"key\": \"Mod+K Mod+S\", \"command\": \"edit.saveAll\"}]"));

        assertTrue(press(el, "Mod+S"));
        assertEquals(List.of("edit.save"), fired);

        press(el, "Mod+K Mod+S");
        assertEquals(List.of("edit.save", "edit.saveAll"), fired);
    }

    /**
     * <b>{@code "-command"} removes, and removes only that pairing.</b>
     *
     * <p>A user sheet is appended to the defaults rather than replacing them, so without a way to say
     * "not that one" the only way to drop a default would be to redefine the whole default sheet — which
     * then silently stops tracking any later change to it. And the removal is targeted: taking some other
     * extension's binding off the same key would be a surprise nobody could diagnose.</p>
     */
    @Test
    public void aLeadingMinusRemovesOnlyThatBinding() {
        UIElement el = build();
        window.getCommands().register(recording("a.command")).register(recording("b.command"));
        el.keymap().bind("Mod+P", "a.command");
        el.keymap().bind("Mod+P", "b.command");

        el.keymap().load(KeymapSheet.parse("[{\"key\": \"Mod+P\", \"command\": \"-a.command\"}]"));

        assertTrue(press(el, "Mod+P"));
        assertEquals("b's binding on the same chord must survive", List.of("b.command"), fired);
    }

    @Test
    public void aSheetCarriesReleaseAndTypingFlags() {
        UIElement el = build();
        window.getCommands().register(recording("pan.end")).register(recording("tool.brush"));
        el.keymap().load(KeymapSheet.parse("["
                + "{\"key\": \"Space\", \"command\": \"pan.end\", \"on\": \"release\"},"
                + "{\"key\": \"B\", \"command\": \"tool.brush\", \"whileTyping\": true}]"));

        var bindings = el.keymap().bindings();
        assertEquals(KeyEventType.RELEASE, bindings.get(0).getEventType());
        assertTrue(bindings.get(1).isAllowedWhileTyping());
    }

    /**
     * <b>One malformed entry must not cost every other binding in the file.</b>
     *
     * <p>The same call the stylesheet parser makes for a malformed declaration, and for the same reason:
     * this is a file a <em>user</em> edits, and losing an entire remapping to one typo is a far worse
     * outcome than losing the line that had the typo.</p>
     */
    @Test
    public void malformedEntriesAreSkippedNotFatal() {
        UIElement el = build();
        window.getCommands().register(recording("good.command"));
        el.keymap().load(KeymapSheet.parse("["
                + "{\"key\": \"Mod+Nonsense\", \"command\": \"bad.key\"},"
                + "{\"key\": \"\", \"command\": \"no.key\"},"
                + "{\"command\": \"missing.key\"},"
                + "{\"key\": \"Mod+G\"},"
                + "\"not an object\","
                + "{\"key\": \"Mod+G\", \"command\": \"good.command\"}]"));

        assertEquals("only the one valid entry survived", 1, el.keymap().bindings().size());
        assertTrue(press(el, "Mod+G"));
        assertEquals(List.of("good.command"), fired);
    }

    @Test
    public void invalidJsonYieldsAnEmptySheetRatherThanThrowing() {
        assertTrue(KeymapSheet.parse("{ not json at all").isEmpty());
        assertTrue("a bare object is not a keymap either", KeymapSheet.parse("{}").isEmpty());
        assertTrue(KeymapSheet.parse("").isEmpty());
    }

    /** Sheet order is significant and preserved: layering a user sheet over defaults depends on a later
     * entry being able to remove an earlier one. */
    @Test
    public void sheetOrderIsPreserved() {
        UIElement el = build();
        window.getCommands().register(recording("first"));
        el.keymap().load(KeymapSheet.parse("["
                + "{\"key\": \"Mod+D\", \"command\": \"first\"},"
                + "{\"key\": \"Mod+D\", \"command\": \"-first\"}]"));

        assertTrue("bound then removed, in that order", el.keymap().bindings().isEmpty());
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────

    /** Innermost-first would silently pick one of a duplicate pair and the other would simply never fire.
     * Reporting it is what makes that a bug rather than a mystery. */
    @Test
    public void duplicateChordsInOneScopeAreReported() {
        UIElement el = build();
        el.keymap().bind("Mod+P", "one");
        el.keymap().bind("Mod+P", "two");
        el.keymap().bind("Mod+Q", "three");

        var conflicts = el.keymap().conflicts();
        assertEquals("only the genuinely duplicated chord", 1, conflicts.size());
        assertEquals(List.of("one", "two"), conflicts.get(KeyChord.parse("Mod+P")));
    }

    /** Sheets and registries are edited separately, so a binding naming a command that no longer exists is
     * ordinary. It must warn and fall through, never throw — one stale entry taking down every other
     * binding on the same keystroke would be far worse. */
    @Test
    public void aBindingNamingAnUnregisteredCommandFallsThrough() {
        UIElement outer = build();
        UIElement inner = new UIElement();
        outer.addChild(inner);
        window.getCommands().register(recording("outer.real"));
        inner.keymap().bind("Mod+G", "does.not.exist");
        outer.keymap().bind("Mod+G", "outer.real");

        assertTrue(press(inner, "Mod+G"));
        assertEquals(List.of("outer.real"), fired);
    }

    // ── Focus ────────────────────────────────────────────────────────────────

    // ── Three bugs found by driving the gallery page ─────────────────────────

    /**
     * <b>Releasing the first stroke must not abandon a chord.</b>
     *
     * <p>The release ran the same walk as a press, matched nothing, and hit the cancel at the bottom — so
     * the prefix died the instant the key came up and {@code Mod+K Mod+S} could only be completed by
     * <em>holding</em> {@code Mod+K} down throughout. Reported from the gallery exactly that way.</p>
     *
     * <p>Chords are sequences of presses, everywhere that has them.</p>
     */
    @Test
    public void releasingTheFirstStrokeDoesNotAbandonTheChord() {
        UIElement el = build();
        window.getCommands().register(recording("edit.saveAll"));
        el.keymap().bind("Mod+K Mod+S", "edit.saveAll");

        long now = System.currentTimeMillis();
        resolver().resolve(el, KeyStroke.parse("Mod+K"), KeyEventType.PRESS, now);
        resolver().resolve(el, KeyStroke.parse("Mod+K"), KeyEventType.RELEASE, now);
        assertNotNull("the prefix must survive the key coming back up", resolver().pending());

        resolver().resolve(el, KeyStroke.parse("Mod+S"), KeyEventType.PRESS, now);
        assertEquals(List.of("edit.saveAll"), fired);
    }

    /**
     * <b>Auto-repeat must not churn the pending state.</b>
     *
     * <p>Holding {@code Mod+K} made every repeat try to extend the chord with a second {@code Mod+K};
     * nothing starts with that, so each repeat cancelled the prefix and the next re-armed it. On screen
     * the pending line flickered between "waiting" and idle several times a second.</p>
     */
    @Test
    public void autoRepeatDoesNotChurnTheChordState() {
        UIElement el = build();
        window.getCommands().register(recording("edit.saveAll"));
        el.keymap().bind("Mod+K Mod+S", "edit.saveAll");

        long now = System.currentTimeMillis();
        resolver().resolve(el, KeyStroke.parse("Mod+K"), KeyEventType.PRESS, now, false);
        for (int i = 0; i < 10; i++) {
            resolver().resolve(el, KeyStroke.parse("Mod+K"), KeyEventType.PRESS, now, true);
            assertNotNull("repeat " + i + " cleared the prefix", resolver().pending());
        }
        resolver().resolve(el, KeyStroke.parse("Mod+S"), KeyEventType.PRESS, now, false);
        assertEquals(List.of("edit.saveAll"), fired);
    }

    /** A repeat must not re-fire an ordinary binding either — a shortcut is an event, not a state, and
     * nobody wants save running thirty times because they leaned on the key. */
    @Test
    public void autoRepeatDoesNotRefireAPlainBinding() {
        UIElement el = build();
        window.getCommands().register(recording("edit.save"));
        el.keymap().bind("Mod+S", "edit.save");

        long now = System.currentTimeMillis();
        resolver().resolve(el, KeyStroke.parse("Mod+S"), KeyEventType.PRESS, now, false);
        for (int i = 0; i < 5; i++) {
            resolver().resolve(el, KeyStroke.parse("Mod+S"), KeyEventType.PRESS, now, true);
        }
        assertEquals("once, not six times", List.of("edit.save"), fired);
    }

    /**
     * <b>Letting go of Ctrl between the two strokes of a chord must not abandon it.</b>
     *
     * <p>Pressing Ctrl emits a real key-down whose key <em>is</em> Ctrl. It can never match a binding —
     * {@code Mod+S} means "S with Ctrl held", never "Ctrl" — but before it was filtered it fell through
     * the whole resolver and cancelled the pending prefix. The symptom was precise and strange: the chord
     * completed only while Ctrl stayed held down for both strokes, and releasing it between them killed
     * the chord silently.</p>
     */
    @Test
    public void aBareModifierPressDoesNotAbandonTheChord() {
        UIElement el = build();
        window.getCommands().register(recording("edit.saveAll"));
        el.keymap().bind("Mod+K Mod+S", "edit.saveAll");

        long now = System.currentTimeMillis();
        resolver().resolve(el, KeyStroke.parse("Mod+K"), KeyEventType.PRESS, now);
        // Everything comes back up, then Ctrl goes down again on its own — exactly what the platform
        // reports when you release and re-press for the second stroke.
        resolver().resolve(el, KeyStroke.parse("Mod+K"), KeyEventType.RELEASE, now);
        resolver().resolve(el, ctrlKeyStroke(), KeyEventType.RELEASE, now);
        resolver().resolve(el, ctrlKeyStroke(), KeyEventType.PRESS, now);
        assertNotNull("a bare Ctrl press must leave the prefix alone", resolver().pending());

        resolver().resolve(el, KeyStroke.parse("Mod+S"), KeyEventType.PRESS, now);
        assertEquals(List.of("edit.saveAll"), fired);
    }

    /** Ctrl going down, reported as the platform reports it: key = the modifier, modifier mask already set. */
    private static KeyStroke ctrlKeyStroke() {
        return new KeyStroke(CgKeyCodes.KEY_LCONTROL, CgModifiers.CTRL);
    }

    @Test
    public void aBareModifierNeverFiresAnything() {
        UIElement el = build();
        window.getCommands().register(recording("nope"));
        el.keymap().bind("Mod+S", "nope");

        assertFalse(resolver().resolve(el, ctrlKeyStroke(), KeyEventType.PRESS,
                System.currentTimeMillis()));
        assertTrue(fired.isEmpty());
    }

    /** A prefix begun in one panel must not complete in another — the resolver walks the NEW focus path,
     * so it would not even be the binding the user started. */
    @Test
    public void changingFocusAbandonsAPendingChord() {
        UIElement rootEl = build();
        UIElement panel = new UIElement();
        panel.setFocusPolicy(FocusPolicy.FOCUSABLE);
        rootEl.addChild(panel);
        window.getCommands().register(recording("edit.saveAll"));
        rootEl.keymap().bind("Mod+K Mod+S", "edit.saveAll");

        resolver().resolve(rootEl, KeyStroke.parse("Mod+K"), KeyEventType.PRESS, System.currentTimeMillis());
        assertNotNull(resolver().pending());

        window.getInputHandler().requestFocus(panel);
        assertNull("the prefix belonged to the scope it was begun in", resolver().pending());
    }
}
