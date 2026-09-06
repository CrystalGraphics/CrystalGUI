package com.crystalgui.workbench;

import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.workbench.explorer.ExplorerCommands;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>Asking whether a command is enabled must never throw.</b>
 *
 * <p>The command palette calls {@code isEnabled} on <em>every</em> registered command each time it opens,
 * so one predicate that assumes something is in the data context takes the whole palette down rather than
 * listing its own command as disabled. That is what happened: the explorer's <em>New File</em> read
 * {@code PROJECT_TREE} without a guard, and Ctrl+Shift+P crashed whenever focus was anywhere but the file
 * tree — a builder canvas, an editor, the desktop.</p>
 *
 * <p>An empty context is not a contrived case; it is what every command sees when the palette opens from
 * somewhere that answers none of its keys. "Not applicable" is an ordinary answer and the only correct
 * one.</p>
 */
public class CommandEnablementIsTotalTest extends UiDocumentTestBase {

    @Test
    public void everyCommandAnswersEnablementFromAContextThatKnowsNothing() {
        // The explorer's, which is where the reported crash was. Contributions are global and lazy, so a
        // command nobody registered cannot be asked about.
        CommandRegistry.global().resetForTesting();
        ExplorerCommands.register();

        UIElement bare = new UIElement();
        document.append(bare);
        document.update(W, H);

        List<String> broke = new ArrayList<>();
        for (Command command : CommandRegistry.global().all()) {
            try {
                command.isEnabled(CommandContext.of(bare));
            } catch (RuntimeException thrown) {
                broke.add(command.getId() + " — " + thrown);
            }
        }
        if (!broke.isEmpty()) {
            fail("enablement threw for " + broke.size() + " command(s):\n  " + String.join("\n  ", broke));
        }
    }
}
