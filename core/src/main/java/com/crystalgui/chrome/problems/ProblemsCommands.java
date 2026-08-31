package com.crystalgui.chrome.problems;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;

import com.crystalgui.text.diagnostic.ProblemNode;
import javax.annotation.Nullable;

/**
 * What the Problems panel's context menu offers — IntelliJ's three rows.
 *
 * <h3>Commands rather than handlers, and the reason is not ceremony</h3>
 *
 * <p>{@code MenuBuilder} is the only thing in this codebase that turns commands into menu rows, and six
 * rules live there that were each learned from a bug — separators that are never leading or doubled, an
 * unregistered command still getting a disabled row, enablement re-checked at activation, the command
 * re-resolved through the registry when it runs. A menu built from bare handlers gets some subset of
 * those right and drifts from the other menus within a release.</p>
 *
 * <p>It also means these are reachable from the palette and rebindable, which is the half people notice:
 * "Jump to Source" is a command with a key, not a thing the panel does when clicked.</p>
 *
 * <h3>The subject is the right-clicked row, resolved through the data seam</h3>
 *
 * <p>A context menu resolves against what was <em>clicked</em>, so each of these walks outward from the
 * menu's element to find the panel and then asks it which row the menu was opened on. That is why
 * {@link ProblemsPanel#PROBLEMS_PANEL} exists — without it a command would have to be handed the panel
 * at registration, and there is more than one panel.</p>
 */
public final class ProblemsCommands {

    private static final String PREFIX = "problems.";

    /** Alt+Enter, the same key the editor uses — it is the same list, reached from the other end. */
    public static final String SHOW_QUICK_FIXES = PREFIX + "showQuickFixes";

    /** F5, IntelliJ's binding. Opens the file and reveals the row. */
    public static final String JUMP_TO_SOURCE = PREFIX + "jumpToSource";

    private ProblemsCommands() {
    }

    public static void register() {
        CommandRegistry.global().contribute(ProblemsCommands.class, registry -> {
            registry.register(Command.of(SHOW_QUICK_FIXES, "Show Quick-Fixes")
                    .binding("Alt+Enter")
                    .run(context -> {
                        ProblemsPanel panel = panelOf(context);
                        if (panel != null) panel.showQuickFixesForContext();
                    })
                    .enabledWhen(ProblemsCommands::hasProblemRow));

            registry.register(Command.of(JUMP_TO_SOURCE, "Jump to Source")
                    .binding("F5")
                    .run(context -> {
                        ProblemsPanel panel = panelOf(context);
                        if (panel == null) return;
                        var node = panel.contextProblem();
                        if (node != null && !node.isFile()) panel.onProblemChosen.emit(node);
                    })
                    .enabledWhen(ProblemsCommands::hasProblemRow));
        });
    }

    @Nullable
    private static ProblemsPanel panelOf(CommandContext context) {
        return context.data().get(ProblemsPanel.PROBLEMS_PANEL);
    }

    /**
     * Enabled only over a real problem.
     *
     * <p>Dimmed rather than hidden when it is not, which is the registry's rule everywhere: a menu whose
     * rows appear and vanish is a menu whose rows are never in the same place twice, and the palette
     * already paid for the alternative by listing one command in nine.</p>
     */
    private static boolean hasProblemRow(CommandContext context) {
        ProblemsPanel panel = panelOf(context);
        if (panel == null) return false;
        ProblemNode node = panel.contextProblem();
        return node != null && !node.isFile() && node.diagnostic() != null;
    }
}
