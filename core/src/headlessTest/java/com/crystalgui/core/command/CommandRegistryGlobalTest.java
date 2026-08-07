package com.crystalgui.core.command;

import com.crystalgui.core.data.DataKey;
import com.crystalgui.ui.UIElement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Commands: declared once, globally, and resolved against whatever context they are given.
 *
 * <p>Headless, and that is itself an assertion — a command must be registrable and enumerable
 * <b>before any window exists</b>, which is the whole difference from the per-window registration
 * this replaces.</p>
 */
public class CommandRegistryGlobalTest {

    private static final DataKey<String> SUBJECT = DataKey.create("action.test.subject", String.class);

    private final List<String> ran = new ArrayList<>();

    private static UIElement answering(String answer) {
        return new UIElement() {
            @Override
            public Object getData(DataKey<?> key) {
                if (key == SUBJECT) return answer;
                return super.getData(key);
            }
        };
    }

    @Before
    @After
    public void reset() {
        CommandRegistry.global().resetForTesting();
        ran.clear();
    }

    /**
     * <b>Registered without a window, a widget or a frame.</b> This is the one that fails against the
     * old model: commands lived on a {@code UIWindow}, and one widget installed them from a frame
     * ticker — so they did not exist until a frame after it attached.
     */
    @Test
    public void anActionExistsBeforeAnyWindowDoes() {
        register(Command.of("test.thing", "Thing").run(() -> ran.add("thing")));
        assertTrue(CommandRegistry.global().contains("test.thing"));
        assertNotNull(CommandRegistry.global().get("test.thing"));
        assertEquals(1, CommandRegistry.global().all().size());
    }

    /**
     * A later registration <b>replaces</b> an earlier one, and says so in the log.
     *
     * <p>Not the behaviour I first wrote — a parallel registry ignored duplicates — but the one this
     * class already documented, with a reason: replacement is how a theme or a mod overrides a built-in,
     * the same way re-adding a stylesheet is allowed. Silently shadowing is what the log is for.</p>
     */
    @Test
    public void aLaterRegistrationReplacesAnEarlierOne() {
        register(Command.of("test.dup", "First").run(() -> ran.add("first")));
        register(Command.of("test.dup", "Second").run(() -> ran.add("second")));

        assertEquals("Second", CommandRegistry.global().get("test.dup").getLabel());
        run("test.dup", CommandContext.of(null));
        assertEquals(List.of("second"), ran);
    }

    /**
     * <b>One action, two contexts, two answers.</b> This is what lets a menu built over a graph and a
     * palette opened over a tree show different things without either knowing about the other.
     */
    @Test
    public void enablementIsEvaluatedAgainstThePassedContext() {
        Command action = Command.of("test.subject", "Needs a subject")
                .enabledWhereData(context -> context.has(SUBJECT))
                ;

        assertTrue(action.isEnabled(ctx(answering("yes"))));
        assertFalse(action.isEnabled(ctx(new UIElement())));
        assertFalse(action.isEnabled(CommandContext.of(null)));
    }

    /** A disabled action does nothing and says so — how a keystroke that does not apply here stays quiet. */
    @Test
    public void runningADisabledActionDoesNothing() {
        register(Command.of("test.guarded", "Guarded")
                .enabledWhereData(context -> context.has(SUBJECT))
                .run(() -> ran.add("ran")));

        assertFalse(run("test.guarded", CommandContext.of(null)));
        assertTrue(ran.isEmpty());

        assertTrue(run("test.guarded", ctx(answering("yes"))));
        assertEquals(List.of("ran"), ran);
    }

    /** An unknown id and a disabled action are the same answer from the caller's side: nothing happened. */
    @Test
    public void anUnknownIdIsFalseRatherThanAThrow() {
        assertFalse(run("test.nothing", CommandContext.of(null)));
    }

    /** The body reads its subject from the context rather than from a captured field. */
    @Test
    public void theBodyResolvesItsSubjectFromContext() {
        register(Command.of("test.echo", "Echo")
                .enabledWhereData(context -> context.has(SUBJECT))
                .runWithData(context -> ran.add(context.require(SUBJECT))));

        run("test.echo", ctx(answering("alpha")));
        run("test.echo", ctx(answering("beta")));
        assertEquals(List.of("alpha", "beta"), ran);
    }

    /** Group then order, so unrelated contributors interleave predictably. */
    @Test
    public void menuContributionsComeBackInGroupThenOrder() {
        register(
                Command.of("m.c", "C").menu(MenuId.GRAPH_CONTEXT, "modify", 20),
                Command.of("m.a", "A").menu(MenuId.GRAPH_CONTEXT, "edit", 10),
                Command.of("m.b", "B").menu(MenuId.GRAPH_CONTEXT, "modify", 10));

        List<Command> menu = menu(MenuId.GRAPH_CONTEXT, CommandContext.of(null));
        assertEquals(List.of("m.a", "m.b", "m.c"), menu.stream().map(Command::getId).toList());
    }

    /**
     * Registration order must not decide menu order — that is the property that makes contributing to
     * somebody else's menu safe.
     */
    @Test
    public void registrationOrderDoesNotDecideMenuOrder() {
        register(Command.of("m.late", "Late").menu(MenuId.GRAPH_CONTEXT, "edit", 1));
        register(Command.of("m.early", "Early").menu(MenuId.GRAPH_CONTEXT, "edit", 0));

        assertEquals(List.of("m.early", "m.late"),
                menu(MenuId.GRAPH_CONTEXT, CommandContext.of(null))
                        .stream().map(Command::getId).toList());
    }

    /** A context menu omits what cannot apply; a palette asks {@code all()} and greys instead. */
    @Test
    public void aMenuOmitsDisabledActions() {
        register(
                Command.of("m.always", "Always").menu(MenuId.GRAPH_CONTEXT, "g", 0),
                Command.of("m.never", "Never")
                        .enabledWhereData(context -> context.has(SUBJECT))
                        .menu(MenuId.GRAPH_CONTEXT, "g", 1));

        assertEquals(List.of("m.always"),
                menu(MenuId.GRAPH_CONTEXT, CommandContext.of(null))
                        .stream().map(Command::getId).toList());
        assertEquals(2, menu(MenuId.GRAPH_CONTEXT,
                ctx(answering("yes"))).size());
    }

    /** An action in one menu does not leak into another. */
    @Test
    public void menusAreSeparate() {
        register(Command.of("m.graph", "G").menu(MenuId.GRAPH_CONTEXT, "g", 0));
        assertEquals(1, menu(MenuId.GRAPH_CONTEXT, CommandContext.of(null)).size());
        assertTrue(menu(MenuId.EXPLORER_CONTEXT, CommandContext.of(null)).isEmpty());
    }

    /** Bindings travel with the declaration, so they are discoverable without the widget existing. */
    @Test
    public void bindingsAreDeclaredWithTheAction() {
        register(Command.of("test.bound", "Bound").binding("Delete", "Backspace"));
        assertEquals(List.of("Delete", "Backspace"), CommandRegistry.global().get("test.bound").bindings());
    }

    /** Menus are interned, so a menu named twice is one menu. */
    @Test
    public void menuIdsAreInterned() {
        assertEquals(MenuId.GRAPH_CONTEXT, MenuId.of("graph/context"));
        assertNull(CommandRegistry.global().get("nothing"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static void register(Command... commands) {
        for (Command command : commands) CommandRegistry.global().register(command);
    }

    private static CommandContext ctx(UIElement source) {
        return CommandContext.of(source);
    }

    private static boolean run(String id, CommandContext context) {
        return CommandRegistry.global().run(id, context);
    }

    private static List<Command> menu(MenuId menu, CommandContext context) {
        return CommandRegistry.global().menu(menu, context);
    }

}
