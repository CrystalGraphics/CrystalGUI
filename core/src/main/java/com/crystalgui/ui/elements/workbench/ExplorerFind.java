package com.crystalgui.ui.elements.workbench;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.fs.CgPath;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.tree.TreeRow;
import dev.vfyjxf.taffy.style.TaffyDisplay;

/**
 * The explorer's search — VS Code's {@code ExplorerFindProvider}, plus the widget it is missing.
 *
 * <h3>Two modes, and a bar that says which</h3>
 *
 * <p>VS Code's provider has both a <b>filter</b> mode (phantom items, non-matching rows removed) and a
 * <b>highlight</b> mode ({@code ExplorerFindHighlightTree}, descendant counts, nothing restructured).
 * What it also has and this panel did not is somewhere on screen saying a search is running — and
 * {@code ProjectFileTree}'s own javadoc had already named the consequence: "a filter with nothing saying
 * it is on is a tree that has mysteriously lost half its files".</p>
 *
 * <p>Which rows exist is the <em>model's</em> question and stays in {@link WorkspaceTreeSource}. What is
 * here is the bar, the mode switch, the per-row marking, and the keys that drive them.</p>
 *
 * @see ProjectFileTree for why a part sits beside the widget rather than behind an interface
 */
final class ExplorerFind {

    private final ProjectFileTree tree;

    ExplorerFind(ProjectFileTree tree) {
        this.tree = tree;
    }

    private final UIElement findBar = new UIElement();
    private final UIText findQuery = new UIText("");
    private final UIText findCount = new UIText("");
    private final Button findMode = new Button("Highlight");

    /**
     * The bar that says a search is on.
     *
     * <h3>Why this is the feature and the modes are the detail</h3>
     *
     * <p>This panel's own javadoc named the defect before the bar existed: "a filter with nothing saying
     * it is on is a tree that has mysteriously lost half its files, which is IntelliJ's one real weakness
     * with speed search." Typing narrowed the tree and nothing on screen explained why. Two modes are
     * worth having; <b>being able to see that one of them is running</b> is what was actually missing.</p>
     */
    void build() {
        findBar.addClass(ProjectFileTree.FIND_BAR_CLASS);
        findQuery.setHitTest(false);
        findCount.addClass(ProjectFileTree.FIND_COUNT_CLASS);
        findCount.setHitTest(false);
        findMode.addClass(ProjectFileTree.FIND_MODE_CLASS);
        findMode.onPressed.connect(this::toggleFindMode);
        findBar.addChild(findQuery);
        findBar.addChild(findCount);
        findBar.addChild(findMode);
        // FIRST, not appended. The tree is already in `content` and grows, so adding the bar after it
        // put the bar below the list and overlapping its last row -- VS Code's find widget is at the top
        // of the view and there is nothing for it to cover there.
        tree.contentBox().addChildAt(findBar, 0);
        apply();
    }

    /** Filter <-> Highlight. Kept on the source, which is what both the rows and the model read. */
    void toggleFindMode() {
        tree.source().setFindMode(tree.source().findMode() == WorkspaceTreeSource.FindMode.HIGHLIGHT
                ? WorkspaceTreeSource.FindMode.FILTER
                : WorkspaceTreeSource.FindMode.HIGHLIGHT);
        tree.treeView().refresh();
        apply();
    }

    /** Shows or hides the bar, and writes what it says. */
    void apply() {
        boolean searching = !tree.filter().isEmpty();
        StyleGroup.importantPipeline(findBar.getStyle().getLayoutGroup(),
                l -> l.display(searching ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        if (!searching) return;
        findQuery.setText(tree.filter());
        findMode.setText(tree.source().findMode() == WorkspaceTreeSource.FindMode.FILTER
                ? "Filter" : "Highlight");
        int matches = 0;
        for (TreeRow<CgPath> row : tree.treeView().visibleRows()) {
            if (tree.source().isMatch(row.item())) matches++;
        }
        // WHAT IS ON SCREEN, not what the workspace holds. The tree is listed a directory at a time, so a
        // total would be a number about the parts that happen to have been opened -- which reads as a
        // search result and is not one. See WorkspaceTreeSource.descendantMatches.
        findCount.setText(matches == 0 ? "no matches here" : matches + " shown");
    }

    /**
     * Marks a row for the live search — Highlight mode only.
     *
     * <p>Three states rather than two, and the third is the one that makes highlight usable: a row that
     * matches is <b>marked</b>, a row that contains a match is left alone so the path to it stays
     * readable, and a row that is neither is <b>dimmed</b>. Marking alone leaves the eye hunting through
     * full-strength noise; removing the rest is the other mode.</p>
     *
     * <p>Classes are SWAPPED, never merely added: a template is a different row every time the view
     * recycles it, and a stale mark would leave the previous file's highlight on this one.</p>
     */
    void applyMarks(UIElement row, ProjectFileTree.RowParts parts, CgPath item, TreeRow<CgPath> treeRow) {
        boolean searching = !tree.filter().isEmpty()
                && tree.source().findMode() == WorkspaceTreeSource.FindMode.HIGHLIGHT;
        boolean match = searching && tree.source().isMatch(item);
        int beneath = searching && treeRow.expandable() ? tree.source().descendantMatches(item) : 0;

        if (match) row.addClass(ProjectFileTree.MATCH_CLASS);
        else row.removeClass(ProjectFileTree.MATCH_CLASS);
        if (searching && !match && beneath == 0) row.addClass(ProjectFileTree.DIMMED_CLASS);
        else row.removeClass(ProjectFileTree.DIMMED_CLASS);

        // THE FOLDER COUNT GOES IN THE BADGE, which already exists and already sits beside the name. A
        // second element would be a slot that is empty in every other state the row can be in.
        if (beneath > 0) parts.badge().setText(String.valueOf(beneath));
        else if (searching) parts.badge().setText("");
    }

    /**
     * Type-to-filter, IntelliJ's speed search.
     *
     * <p>Bound here rather than as commands, and that is the exception rather than a lapse: this is not
     * <em>an</em> action, it is every printable character meaning "narrow to this". A command per letter is
     * not a thing, and a keymap that owned the alphabet would collide with every other binding in the
     * panel.</p>
     *
     * <p><b>Escape clears before it does anything else.</b> A filter you cannot see is a tree that has lost
     * files, so the way out has to be the key everyone already tries.</p>
     */
    void installTypeToFilter() {
        tree.treeView().onKeyDown.attachListener((element, event) -> {
            if (event.getModifiers() != 0) return;     // Ctrl+C is a command, not a letter
            int key = event.getKeyCode();
            if (key == CgKeyCodes.KEY_ESCAPE) {
                if (tree.filter().isEmpty()) return;
                tree.setFilter("");
                event.stopPropagation();
                return;
            }
            if (key == CgKeyCodes.KEY_BACK) {
                if (tree.filter().isEmpty()) return;
                tree.setFilter(tree.filter().substring(0, tree.filter().length() - 1));
                event.stopPropagation();
                return;
            }
            char typed = event.getCharacter();
            // Printable only. A tree that filtered on Delete would eat the delete key, and the arrows have
            // to keep moving the selection.
            if (typed >= ' ' && typed != 127) {
                tree.setFilter(tree.filter() + typed);
                event.stopPropagation();
            }
        }, false, true);
    }

}
