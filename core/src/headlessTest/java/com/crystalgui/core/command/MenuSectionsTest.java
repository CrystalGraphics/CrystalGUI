package com.crystalgui.core.command;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link CommandRegistry#sections} — the query every menu renderer reads.
 *
 * <h3>What is worth pinning here</h3>
 *
 * <p>Not that sorting works; {@code CommandRegistryGlobalTest} already covers group-then-order. What
 * these cover is the three things {@code menu()} could not express, each of which was a whole class of
 * bug one layer up: <b>the grouping survives</b> (so a separator can be drawn at all), <b>a disabled row
 * is reported rather than dropped</b> (so a menu bar can keep a stable shape), and <b>a computed row is
 * an ordinary participant</b> (so Recent Files can exist without a command per file).</p>
 */
public class MenuSectionsTest {

    /**
     * A FRESH MENU ID PER TEST, because a submenu declaration is permanent.
     *
     * <p>{@link MenuId#submenu} records on the interned id itself, not on any registry, so
     * {@link CommandRegistry#resetForTesting} cannot undo it — and one test nesting a child left every
     * later test seeing a stray section. That is correct behaviour (a submenu is a structural fact about
     * a menu, like a class declaration) and it means a shared constant is the wrong shape for a test.</p>
     */
    private MenuId menu;

    private static int counter;

    @Before
    @After
    public void reset() {
        CommandRegistry.global().resetForTesting();
        menu = MenuId.of("test/sections/" + counter++);
    }

    private List<MenuSection> sections() {
        return CommandRegistry.global().sections(menu, CommandContext.of(null));
    }

    @Test
    public void groupsSurviveTheQuerySoASeparatorCanBeDrawn() {
        CommandRegistry.global()
                .register(Command.of("s.paste", "Paste").menu(menu, "2_clipboard", 20))
                .register(Command.of("s.new", "New").menu(menu, "1_new", 10))
                .register(Command.of("s.copy", "Copy").menu(menu, "2_clipboard", 10));

        List<MenuSection> sections = sections();
        assertEquals("two groups must come back as two sections, not one flat run", 2, sections.size());
        assertEquals("1_new", sections.get(0).group());
        assertEquals(1, sections.get(0).entries().size());
        assertEquals("2_clipboard", sections.get(1).group());
        assertEquals("order within a group still applies",
                List.of("s.copy", "s.paste"), idsOf(sections.get(1)));
    }

    /**
     * The half of G2 that a flat, filtered list could not say at all.
     *
     * <p>A menu bar must keep File ▸ Save in the same place whether or not there is anything to save. If
     * the registry filtered, the bar would have to re-derive enablement from {@link CommandRegistry#all()}
     * itself — which is exactly the second derivation this method exists to remove.</p>
     */
    @Test
    public void aDisabledCommandIsReportedNotDropped() {
        CommandRegistry.global()
                .register(Command.of("s.on", "On").menu(menu, "g", 10))
                .register(Command.of("s.off", "Off").menu(menu, "g", 20)
                        .enabledWhen(context -> false));

        List<MenuEntry> entries = sections().get(0).entries();
        assertEquals("the disabled row must still occupy its place", 2, entries.size());
        assertTrue(((MenuEntry.Item) entries.get(0)).enabled());
        assertFalse("and it must be MARKED disabled, or the renderer cannot grey it",
                ((MenuEntry.Item) entries.get(1)).enabled());
    }

    @Test
    public void aToggleReportsThatItIsCheckableAndWhichWayItPoints() {
        boolean[] state = {false};
        CommandRegistry.global().register(Command.of("s.wrap", "Wrap").menu(menu, "g", 10)
                .toggledWhen(context -> state[0]));

        MenuEntry.Item off = (MenuEntry.Item) sections().get(0).entries().get(0);
        assertTrue("a toggle reserves its mark column even while off", off.checkable());
        assertFalse(off.checked());

        state[0] = true;
        MenuEntry.Item on = (MenuEntry.Item) sections().get(0).entries().get(0);
        assertTrue("read live, not captured when the command was registered", on.checked());
    }

    @Test
    public void anOrdinaryCommandIsNotCheckable() {
        CommandRegistry.global().register(Command.of("s.plain", "Plain").menu(menu, "g", 10));
        assertFalse("reserving a mark column for every row would indent labels that have no mark",
                ((MenuEntry.Item) sections().get(0).entries().get(0)).checkable());
    }

    @Test
    public void aSubmenuIsListedButNotExpanded() {
        MenuId child = MenuId.of("test/sections/child");
        menu.submenu(child, "More", "9_more", 0);
        CommandRegistry.global().register(Command.of("s.deep", "Deep").menu(child, "g", 10));

        List<MenuSection> sections = sections();
        MenuSection last = sections.get(sections.size() - 1);
        MenuEntry entry = last.entries().get(0);
        assertTrue("expanding here would resolve the whole tree", entry instanceof MenuEntry.Submenu);
        assertEquals("More", ((MenuEntry.Submenu) entry).title());
    }

    // ── Computed rows ───────────────────────────────────────────────────────────────────────────

    @Test
    public void aContributorsRowsMergeByGroupAndOrderLikeAnyOther() {
        CommandRegistry.global()
                .register(Command.of("s.a", "A").menu(menu, "1_static", 10))
                .contributeMenu(menu, (target, context) -> List.of(
                        MenuEntry.Item.of(Command.of("s.computed", "Computed"), "1_static", 20)));

        assertEquals("a computed row is an ordinary participant, not something pinned to one end",
                List.of("s.a", "s.computed"), idsOf(sections().get(0)));
    }

    @Test
    public void aContributorIsAskedEveryTimeSoItsRowsCanChange() {
        int[] calls = {0};
        CommandRegistry.global().contributeMenu(menu, (target, context) -> {
            calls[0]++;
            return List.of(MenuEntry.Item.of(Command.of("s.n" + calls[0], "Row"), "g", 10));
        });

        assertEquals(List.of("s.n1"), idsOf(sections().get(0)));
        assertEquals("computed means computed — a cached answer is how Recent Files goes stale",
                List.of("s.n2"), idsOf(sections().get(0)));
    }

    @Test
    public void aContributorIsNotAskedAboutOtherMenus() {
        int[] calls = {0};
        CommandRegistry.global().contributeMenu(menu, (target, context) -> {
            calls[0]++;
            return List.of();
        });
        CommandRegistry.global().sections(MenuId.of("test/sections/other"), CommandContext.of(null));
        assertEquals("keying by menu is what stops every open costing every contributor", 0, calls[0]);
    }

    @Test
    public void aResetForgetsContributorsAndNotOnlyCommands() {
        CommandRegistry.global().contributeMenu(menu, (target, context) ->
                List.of(MenuEntry.Item.of(Command.of("s.stale", "Stale"), "g", 10)));
        CommandRegistry.global().resetForTesting();
        assertTrue("a surviving contributor holds a lambda closing over the previous test's widgets",
                sections().isEmpty());
    }

    @Test
    public void anEmptyMenuIsAnEmptyListNotASectionWithNothingInIt() {
        assertTrue(sections().isEmpty());
    }

    /** The legacy flat view still filters, so the callers that wanted that shape are unchanged. */
    @Test
    @SuppressWarnings("deprecation")
    public void theFlatViewStillDropsDisabledCommands() {
        CommandRegistry.global()
                .register(Command.of("s.on", "On").menu(menu, "g", 10))
                .register(Command.of("s.off", "Off").menu(menu, "g", 20)
                        .enabledWhen(context -> false));
        List<Command> flat = CommandRegistry.global().menu(menu, CommandContext.of(null));
        assertEquals(1, flat.size());
        assertSame(CommandRegistry.global().get("s.on"), flat.get(0));
    }

    private static List<String> idsOf(MenuSection section) {
        return idsOf(section.entries());
    }

    private static List<String> idsOf(List<MenuEntry> entries) {
        return entries.stream()
                .filter(entry -> entry instanceof MenuEntry.Item)
                .map(entry -> ((MenuEntry.Item) entry).command().getId())
                .toList();
    }
}
