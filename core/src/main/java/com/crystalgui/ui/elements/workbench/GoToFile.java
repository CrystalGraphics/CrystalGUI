package com.crystalgui.ui.elements.workbench;

import com.crystalgui.fs.CgPath;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.chrome.QuickPick;
import com.crystalgui.ui.elements.chrome.QuickPickItem;
import com.crystalgui.ui.elements.chrome.QuickPickSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Open a file by typing its name — VS Code's {@code Ctrl+P}, IntelliJ's Go to File.
 *
 * <h3>It is the palette's widget with a different list in it</h3>
 *
 * <p>{@link QuickPick} already does the search field, the result list, the fuzzy match highlighting and
 * the keyboard handling, and {@code SearchMatcher} — ported from VS Code's {@code filters.ts} — already
 * decides what "matches" means. So this file is a list and a callback. Anything more would be a second
 * idea of how searching works, differing from the palette's in ways nobody chose.</p>
 *
 * <h3>The path is the category</h3>
 *
 * <p>The row's <b>label</b> is the file name and its <b>category</b> is the folder, which is exactly how
 * both editors present it — you search for {@code Main} and disambiguate by looking at the folder, rather
 * than searching a long string that happens to contain the folder. {@code SearchMatcher} matches the two
 * as separate fields, so typing a folder name still finds it without the query having to spell out the
 * whole path.</p>
 *
 * <h3>What it can find</h3>
 *
 * <p>Whatever {@link WorkspaceTreeSource#knownFiles()} has reached. The workbench crawls the workspace in
 * the background from the moment it opens, so the answer is usually "everything" by the time anyone
 * presses the key — and while it is still growing, a partial list is the honest thing to show. Every
 * editor with this feature shows a list that fills in; one that showed nothing until a whole project had
 * been walked would be useless on the first press, which is when it is most wanted.</p>
 */
public final class GoToFile {

    public static final String PLACEHOLDER = "Go to file";

    private GoToFile() {
    }

    /** Opens the picker over {@code workbench}'s indexed files. */
    public static QuickPick open(UIWindow window, Workbench workbench) {
        QuickPick pick = new QuickPick();
        pick.setPlaceholder(PLACEHOLDER);
        pick.setSource(QuickPickSource.of(itemsFor(workbench)));
        pick.onAccepted.connect(id -> workbench.openFile(CgPath.parse(id)));
        pick.onClosed.connect(() -> {
            pick.resultList().dispose();
            pick.removeSelf();
        });
        return pick.open(window);
    }

    /**
     * Every indexed file as a row, name first and folder second.
     *
     * <p>Public and static so a test can assert the candidate set without a window on screen — which is
     * the part worth pinning, and it needs no pixels.</p>
     */
    public static List<QuickPickItem> itemsFor(Workbench workbench) {
        List<QuickPickItem> items = new ArrayList<>();
        for (CgPath path : workbench.fileTree().source().knownFiles()) {
            CgPath parent = path.parent();
            // The ID IS THE PATH, so accepting a row needs no lookup table and cannot go stale between the
            // list being built and a row being chosen -- a file deleted in between simply fails to open,
            // and says so, rather than opening whatever has since taken its index.
            items.add(new QuickPickItem(path.toString(), path.name(),
                    parent == null ? null : parent.toString(), null));
        }
        return items;
    }
}
