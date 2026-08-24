package com.crystalgui.ui.elements.workbench;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.SourceRoots;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.tree.TreeRenderer;
import com.crystalgui.ui.elements.tree.TreeRow;
import com.crystalgui.ui.elements.workbench.decoration.FileDecoration;
import com.crystalgui.ui.input.UIInputHandler;

import javax.annotation.Nullable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds and fills a file row — VS Code's {@code FilesRenderer}, and named after it.
 *
 * <h3>The rules that live here are all about RECYCLING</h3>
 *
 * <p>A template is a different file every time the view reuses it, and every defect this class has ever
 * had was a value that survived one row into the next: a data-driven class <b>swapped, never added</b>;
 * an icon read from the theme <b>per bind</b> and never captured; the badge cleared rather than left; a
 * listener attached <b>once</b>, in {@code createTemplate}, and never in {@code bind}.</p>
 *
 * <p>That last one is why the slots are built here at all. An element created during {@code bind} lands
 * after that frame's layout pass — which is how the command palette shipped squashed key chips and how
 * the editor's gutter arrows ended up toggling whichever row their slot was first used for.</p>
 *
 * @see ProjectFileTree for why a part sits beside the widget rather than behind an interface
 */
final class FilesRenderer implements TreeRenderer<CgPath> {

    private final ProjectFileTree tree;

    /**
     * A recycled row's writable parts.
     *
     * <p>Held in a map rather than reached through {@code getChildren().get(n)}: five slots addressed by
     * index is one insertion away from silently writing the badge into the label, and the indices would
     * live at the call site where nothing explains them.</p>
     */
    private final Map<UIElement, ProjectFileTree.RowParts> slots = new HashMap<>();

    FilesRenderer(ProjectFileTree tree) {
        this.tree = tree;
    }


    @Override
    public UIElement createTemplate() {
        UIElement row = new UIElement();
        row.addClass(ProjectFileTree.ROW_CLASS);

        // FOUR SLOTS, BUILT ONCE HERE. Not in bind(): an element created during bind lands after the
        // layout pass that frame, which is how the command palette's key chips shipped squashed and
        // how the editor's gutter arrows ended up toggling whichever row their slot was first used
        // for. A recycled row keeps its slots and bind() only ever writes into them.
        UIElement twisty = new UIElement();
        twisty.addClass(ProjectFileTree.TWISTY_CLASS);
        UIElement icon = new UIElement();
        icon.addClass(ProjectFileTree.ICON_CLASS);
        UIText label = new UIText("");
        UIText badge = new UIText("");
        badge.addClass(ProjectFileTree.BADGE_CLASS);
        // THE INLINE EDITOR, built here and hidden -- VS Code's FilesRenderer.renderInputBox, which
        // puts a real input INTO the row rather than opening a dialog over it.
        //
        // In createTemplate for the reason the four slots above are: an element created during bind
        // lands after that frame's layout pass. It is also the only way this can work at all, since
        // the edit begins from a KEY PRESS on the row -- building the field then would rebuild the
        // element the press is being dispatched through.
        TextField editor = new TextField();
        editor.addClass(ProjectFileTree.EDITOR_CLASS);

        // Every part refuses the click so the press lands on the row. Click targeting takes the exact
        // element hit and never walks up to a handler-bearing ancestor, which is why every composite
        // in this engine does this.
        // THE TWISTY IS THE ONE PART THAT KEEPS THE POINTER. Everything else refuses it so the press
        // lands on the row -- click targeting takes the exact element hit and never walks up to a
        // handler-bearing ancestor. The chevron is a control in its own right, which is what lets a
        // folder fold on ONE click while the row still needs two. bind() turns it off again for a
        // file, where there is nothing to fold.
        icon.setHitTest(false);
        label.setHitTest(false);
        badge.setHitTest(false);

        // THE LABEL MUST REPORT ITS OWN WIDTH, or there is nothing for the row to overflow with and
        // the horizontal range is always exactly the viewport. UIText latches whether it self-sizes
        // from its FIRST measurement, which happens before any rule here has matched -- so it has to
        // be told, in Java, at construction. Same call, same reason, as the Blackboard's type column.
        label.forceSelfSizeWidth();
        // The BADGE follows the name rather than the row's trailing edge while the list scrolls
        // sideways -- see the stylesheet. `margin-left: auto` puts it at the row's right edge by
        // construction, which is off-screen the moment the row is wider than the viewport: badges
        // simply vanished until scrolled to, and the row's measured content width was its own width,
        // so the label could never be seen to stick out past it at all.

        row.addChild(twisty);
        row.addChild(icon);
        row.addChild(label);
        row.addChild(badge);
        row.addChild(editor);
        slots.put(row, new ProjectFileTree.RowParts(twisty, icon, label, badge, editor));
        tree.editing().installEditor(row, editor);
        // A FOLDER TOGGLES ON ONE CLICK; A FILE OPENS ON TWO. Not one rule for both, and the
        // difference is not a compromise -- the two rows mean different things.
        //
        // Opening a file is destructive of attention: it takes a tab and the focus, which is exactly
        // what double-click protects against and why preview tabs exist in editors that do not have
        // it. Expanding a folder costs nothing and is undone by clicking again, so making it wait for
        // a second click just makes the tree feel broken -- which is precisely how it was reported,
        // after a first pass put the double-click gate in front of both.
        //
        // VS Code's explorer draws the line in the same place. IntelliJ wants the chevron for a single
        // click, which is only better once the chevron is its own hit target; ours is still part of
        // the label's text.
        // ONE CLICK ON THE CHEVRON FOLDS, which is IntelliJ's rule and the half this panel was
        // missing -- its own comment said so: "IntelliJ wants the chevron for a single click, which is
        // only better once the chevron is its own hit target". It is one now.
        //
        // Deliberately does NOT select. A chevron press is about the fold and nothing else, so it
        // leaves the selection alone -- and because the row's own listeners are target-phase only, a
        // press that lands here reaches neither the row's double-click nor its drag.
        twisty.onMouseDown.attachListener((element, event) -> {
            if (event.getDetail() == UIInputHandler.KEYBOARD_DETAIL) return;
            CgPath item = tree.itemForRow(row);
            if (item == null || !tree.source().isDirectory(item)) return;
            tree.treeView().setExpanded(item, !tree.treeView().isExpanded(item));
            // Deferred, for the reason activate() spells out: this runs from the press that folded
            // the row, and refreshing recycles every realised row including the one under the pointer.
            tree.requestRefresh();
        }, false, false);

        tree.dnd().installRowDrag(row);
        row.onMouseDown.attachListener((element, event) -> {
            CgPath item = tree.itemForRow(row);
            if (item == null) return;
            // DOUBLE CLICK FOR BOTH, folders included. A folder used to toggle on a single click,
            // which is VS Code's rule and reads well until the tree also has to support selecting --
            // there, one click has to mean "this is the row I am talking about", because a press is
            // how you aim Delete, Rename, a drag, or a Shift-range. Folding on that same press means
            // you cannot select a folder without also opening it, and every attempt to Shift-click a
            // range across one re-flattens the model mid-gesture.
            //
            // IntelliJ, whose Project view this panel is modelled on, resolves it exactly this way:
            // the chevron folds on one click, the ROW folds on two. Ours has no separate chevron hit
            // target yet -- the +/- is part of the label's text -- so the row's double click is the
            // whole affordance for now.
            if (event.getDetail() >= 2) tree.activate(item);
        }, false, false);
        return row;
    }

    @Override
    public void bind(CgPath item, TreeRow<CgPath> row, int index, UIElement template) {
        tree.rowItems().put(template, item);
        ProjectFileTree.RowParts parts = slots.get(template);
        if (parts == null) return;

        // ONE QUESTION, asked of the source: a project's name, a plain name, or the whole chain a
        // compacted row stands for. The view cannot work the last one out -- by the time a row exists
        // the swallowed directories are not in the tree at all.
        tree.editing().applyEditing(template, parts, item);
        tree.find().applyMarks(template, parts, item, row.expandable());
        String name = tree.source().rowLabel(item);
        // No manual indent and no "+ "/"- " prefix any more: TreeView already writes padding-left from
        // the depth and puts __expanded__/__collapsed__/__leaf__ on the row, so doing either here
        // indented every row TWICE and spelled the twisty in text where CSS can draw it.
        parts.label().setText(name);

        // Icon and filetype class are read from the theme PER BIND, never captured, because a template
        // is a different row every time it is recycled.
        boolean directory = row.expandable();
        // A FILE HAS NO CHEVRON TO PRESS, so its twisty gives the pointer back to the row -- otherwise
        // a click that happened to land in the leading slot would do nothing at all, which reads as a
        // dead strip down the left of the panel.
        parts.twisty().setHitTest(directory);
        FileIconTheme theme = FileIconTheme.getDefault();
        // THE ROLE FIRST, and only for a directory. A module, a source root and a package wear one folder
        // glyph otherwise -- which makes `src/main/java` look like an ordinary directory that happens to
        // be nested deeply, and that is the one thing a reader scanning a tree is looking for.
        SourceRoots.Role role = directory ? tree.source().roleOf(item) : null;
        // THE ITEM'S OWN NAME, never the row label: a compacted row reads "main/java/com" and asking
        // the theme about that string would look up an extension of "/com".
        CgUiDrawable glyph = role == null || role == SourceRoots.Role.FOLDER
                ? theme.drawableFor(item.name(), directory, row.expanded())
                : roleGlyph(role);
        // EMPTY, never null: null is how the cascade spells "nobody set this", so writing it would
        // leave the previous file's icon in place on a recycled row rather than clearing it.
        //
        // DEFAULT origin, matching what TreeView already does for the row's indent. The theme JSON is
        // a default the cascade can beat -- write it INLINE and `.filetype-java { overlay: icon(...) }`
        // in a stylesheet silently does nothing, which makes the icon the one part of a row a theme
        // cannot touch.
        StyleGroup.defaultPipeline(parts.icon().getStyle().getGeneralGroup(),
                g -> g.overlay(glyph == null ? CgUiDrawable.EMPTY : glyph));
        ProjectFileTree.swapPrefixedClass(parts.icon(), ProjectFileTree.FILETYPE_PREFIX, theme.classFor(name, directory));
        // SWAPPED, never added: a template is a different row every time the view reuses it, so leaving
        // the previous row's role on the element lets the cascade resolve whichever rule happens to win.
        ProjectFileTree.swapPrefixedClass(parts.icon(), ProjectFileTree.NODEROLE_PREFIX,
                role == null ? null : ProjectFileTree.NODEROLE_PREFIX
                        + role.name().toLowerCase(Locale.ROOT).replace('_', '-'));

        FileDecoration decoration = tree.getDecorations().resolve(item, directory);
        ProjectFileTree.swapPrefixedClass(template, ProjectFileTree.DECORATION_PREFIX,
                decoration == null ? null : decoration.styleClass());
        parts.badge().setText(decoration == null || decoration.letter() == null
                ? "" : decoration.letter());
    }

    /**
     * The glyph for a role, built once each.
     *
     * <p>Held rather than resolved per bind: {@code ofIcon} allocates a drawable per call, and this runs
     * for every realised row on every refresh -- a listing arriving, a decoration changing, auto-reveal
     * following the active tab. There are three of them and they never change.</p>
     */
    @Nullable
    private static CgUiDrawable roleGlyph(SourceRoots.Role role) {
        CgUiDrawable cached = ROLE_GLYPHS.get(role);
        if (cached != null) return cached;
        CgUiDrawable drawn = CgUiSvg.ofIcon(ROLE_ICONS.get(role));
        if (drawn != null) ROLE_GLYPHS.put(role, drawn);
        return drawn;
    }

    private static final Map<SourceRoots.Role, String> ROLE_ICONS = Map.of(
            SourceRoots.Role.MODULE, "crystalgui:nodes/java/module",
            SourceRoots.Role.SOURCE_ROOT, "crystalgui:nodes/java/sourceRoot",
            SourceRoots.Role.PACKAGE, "crystalgui:nodes/java/package");

    private static final Map<SourceRoots.Role, CgUiDrawable> ROLE_GLYPHS =
            new EnumMap<>(SourceRoots.Role.class);

    @Override
    public void unbind(UIElement template) {
        tree.rowItems().remove(template);
    }
}
