package com.crystalgui.core.command;

import com.crystalgui.core.undo.UndoCommands;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.widget.collection.tree.TreeEditCommands;
import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.widget.texteditor.EditorCommands;
import com.crystalgui.workbench.app.WorkbenchApplicationCommands;
import com.crystalgui.workbench.chrome.problems.ProblemsCommands;
import com.crystalgui.workbench.explorer.ExplorerCommands;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A command's {@link Command#icon} names a file, and naming one that is not there fails <b>silently</b>:
 * {@code CgUiSvg.ofIcon} answers null, {@code MenuItem.drawIcon} substitutes {@code CgUiDrawable.EMPTY},
 * and the row draws a reserved, empty column. Nothing logs, nothing throws, and the menu looks like one
 * that was never given an icon at all — so a typo in an id survives every other check in the build.
 *
 * <p>Written against {@link CommandRegistry#all()} rather than a list of the ids in use, so an icon added
 * later is covered without this class being edited. The registry is global and other tests populate it;
 * that is harmless here, since every assertion only looks at commands that carry an icon.</p>
 */
public class CommandIconsResolveTest {

    @BeforeClass
    public static void declareTheCommandsThatCarryIcons() {
        // contribute() is guarded per contributor class, so these are idempotent against a warm registry.
        UndoCommands.register();
        ClipboardCommands.register();
        ExplorerCommands.register();
        WorkbenchApplicationCommands.register();
        EditorCommands.register();
        // Every tree's menu -- the explorer's, the Hierarchy's, a TreeView's own -- is fed these six by
        // TreeEditing.contributeMenu rather than by a .menu() on the command, which is why they are not
        // reachable from any MenuId and have to be named here.
        TreeEditCommands.register();
        ProblemsCommands.register();
    }

    /** Both marks of a {@link Command#whenToggled} command, since only one of them is {@code getIcon()}. */
    private static List<String> marksOf(Command command) {
        List<String> marks = new ArrayList<>(2);
        if (command.getIcon() != null) marks.add(command.getIcon());
        String off = command.iconFor(CommandContext.of(null));
        if (off != null && !marks.contains(off)) marks.add(off);
        return marks;
    }

    @Test
    public void everyIconACommandNamesResolves() {
        List<String> unresolved = new ArrayList<>();
        int carried = 0;
        for (Command command : CommandRegistry.global().all()) {
            for (String mark : marksOf(command)) {
                carried++;
                if (CgUiSvg.ofIcon(mark) == null) unresolved.add(command.getId() + " -> " + mark);
            }
        }
        // Guards the guard: if registration silently stopped happening, every list below would be empty
        // and this class would pass while asserting nothing.
        assertTrue("no registered command carried an icon -- registration did not run", carried > 0);
        assertEquals("icon ids that resolve to no file", List.of(), unresolved);
    }

    /**
     * A menu mark has to do what its label does — dim on {@code menuitem:disabled}, take the theme's
     * foreground — and the only mechanism for that is {@code currentColor}, which the engine leaves
     * unresolved in its cached ops and binds at draw time. An icon shipped with the upstream
     * {@code #6C707E} baked in renders the same grey on every background and in every row state.
     *
     * <p>A kind glyph is exempt and says so through {@link ActionIcons#carriesItsOwnPalette}: a file-type
     * or node badge is a drawing of a <i>thing</i>, and Java's red-brown is how it is recognised.</p>
     */
    @Test
    public void everyIconACommandNamesTakesItsColourFromTheCascade() {
        List<String> baked = new ArrayList<>();
        for (Command command : CommandRegistry.global().all()) {
            for (String mark : marksOf(command)) {
                if (ActionIcons.carriesItsOwnPalette(mark)) continue;
                CgUiSvg glyph = CgUiSvg.ofIcon(mark);
                if (glyph == null) continue;           // reported by the test above
                SvgDocument document = glyph.getDocument();
                if (document != null && !document.usesCurrentColor()) {
                    baked.add(command.getId() + " -> " + mark);
                }
            }
        }
        assertEquals("icons with a hard-coded fill, which cannot follow the row", List.of(), baked);
    }

    /**
     * The exemption above has to stay honest in both directions: a mark listed in
     * {@link ActionIcons#COLOURED} must genuinely be artwork. One that has been converted to
     * {@code currentColor} since — or was listed by mistake — would sit there suppressing the check on
     * an icon that no longer needs it, which is the failure mode an exception list always has.
     */
    @Test
    public void everyColouredMarkIsActuallyColoured() {
        List<String> tintable = new ArrayList<>();
        for (String icon : ActionIcons.COLOURED) {
            CgUiSvg glyph = CgUiSvg.ofIcon(icon);
            SvgDocument document = glyph == null ? null : glyph.getDocument();
            if (document != null && document.usesCurrentColor()) tintable.add(icon);
        }
        assertEquals("listed as coloured but follows the cascade -- drop it from COLOURED",
                List.of(), tintable);
    }

    /**
     * A {@link Command#whenToggled} row names <b>what pressing it does</b>, so it reads <i>Hide</i> with a
     * struck-through eye over a layer that is currently visible. Getting this backwards produces an open
     * eye offering to open an eye, which looks like a bug in the icon and is really a bug in the model —
     * so both halves are pinned here rather than left to a screenshot.
     */
    @Test
    public void aToggledRowNamesTheActionNotTheState() {
        Command overVisible = visibility(true);
        assertEquals("a visible layer offers to hide it", "Hide", overVisible.labelFor(CommandContext.of(null)));
        assertEquals(ActionIcons.HIDE, overVisible.iconFor(CommandContext.of(null)));

        Command overHidden = visibility(false);
        assertEquals("a hidden layer offers to show it", "Show", overHidden.labelFor(CommandContext.of(null)));
        assertEquals(ActionIcons.SHOW, overHidden.iconFor(CommandContext.of(null)));

        // Still a toggle for every other reader -- only the menu row trades its tick for a mark.
        assertTrue(overVisible.isCheckable());
        assertTrue(overVisible.hasToggledPresentation());

        Command plain = Command.of("test.plain", "Plain").icon(ActionIcons.COPY);
        assertTrue("an ordinary icon is not a second presentation", !plain.hasToggledPresentation());
        assertEquals("Plain", plain.labelFor(CommandContext.of(null)));
    }

    /** The Layer stack's Visible command, over a layer that is or is not currently shown. */
    private static Command visibility(boolean shown) {
        return Command.of("test.visible", "Show")
                .icon(ActionIcons.SHOW)
                .toggledWhen(context -> shown)
                .whenToggled(ActionIcons.HIDE, "Hide");
    }
}
