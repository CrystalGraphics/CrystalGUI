package com.crystalgui.workbench.explorer;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.ResourceContentProvider;
import com.crystalgui.fs.ResourceRegistry;
import com.crystalgui.fs.project.SourceRoots;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.display.SymbolIcon;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.workbench.decoration.FileDecoration;
import com.crystalgui.ui.service.Input;

import javax.annotation.Nullable;

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
        // A SYMBOL ICON, because a `.java` row is a DECLARATION and the rest are not.
        //
        // The same widget the completion popup and a library tab build, so a class glyph cannot come to
        // mean one thing in a tab and another in the tree -- and it carries the `static`/`final` marks,
        // which are stacked layers rather than a picture and so cannot travel as an icon name. A row that
        // is not a declaration calls `showNothing()`; see bind().
        SymbolIcon icon = new SymbolIcon();
        icon.addClass(ProjectFileTree.ICON_CLASS);
        // THE TOOLTIP IS THE ROW'S, and the icon is a REGION of it.
        //
        // Not attached to the icon, which would need the icon to be hittable for a pointer to reach it --
        // and a hittable part swallows the press meant for the row. That is worse here than it sounds:
        // click-focus targets the exact element hit rather than the nearest focusable ancestor, and an
        // icon is not focusable, so `emitMouseDown` would blur the tree and hand focus to nothing. The
        // dock's tabs solved the identical problem the identical way. @see Tooltip#addRegion
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
        // The BADGE follows the name rather than the row's trailing edge while the list scrolls
        // sideways -- see the stylesheet. `margin-left: auto` puts it at the row's right edge by
        // construction, which is off-screen the moment the row is wider than the viewport: badges
        // simply vanished until scrolled to, and the row's measured content width was its own width,
        // so the label could never be seen to stick out past it at all.

        row.append(twisty);
        row.append(icon);
        row.append(label);
        row.append(badge);
        row.append(editor);
        // ATTACHED ONCE, here rather than in bind: `Tooltip.attach` ADDS a listener pair rather than
        // replacing one, so a row that was bound twice would show two tooltips.
        tips.put(row, Tooltip.attach(row, ""));
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
            if (event.getDetail() == Input.KEYBOARD_DETAIL) return;
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
        // A module, a source root and a package wear one folder glyph otherwise -- which makes
        // `src/main/java` look like an ordinary directory that happens to be nested deeply, and that is
        // the one thing a reader scanning a tree is looking for.
        //
        // THE ROLE IS A CLASS AND NOTHING ELSE. The glyph for it is `noderole-*` in the stylesheet, beside
        // the `completion-kind-*` vocabulary every other icon in the application is drawn from, and it
        // beats the theme's inline default because a rule outranks the DEFAULT origin written below. A
        // Java table of icon NAMES here would be the exact failure `SymbolIcon` was written to remove --
        // its javadoc says so: two tables saying one thing, and the wrong one looks like a tab with an
        // icon rather than a tab with the wrong icon.
        SourceRoots.Role role = directory ? tree.source().roleOf(item) : null;
        // THE ITEM'S OWN NAME, never the row label: a compacted row reads "main/java/com" and asking
        // the theme about that string would look up an extension of "/com".
        CgUiDrawable glyph = theme.drawableFor(item.name(), directory, row.expanded());
        // WHAT THE FILE DECLARES, through the SAME seam a library tab asks -- `symbolOf` on the
        // resource's provider. `LibrarySources` answers it for `library://`, which is why a
        // `FlexDirection.class` tab draws an enum; `ProjectSourceSymbols` answers it for `project://`.
        // The tree asks the question and never the engine, so neither can grow its own opinion.
        SymbolInfo declared = directory ? null : declaredIn(item);
        if (declared != null) {
            parts.icon().show(declared.kind(), declared.modifiers());
        } else {
            // NOT `show(null, ...)`, which draws the UNKNOWN glyph as a background -- under the
            // file-type overlay written below, that is two pictures on one row. @see SymbolIcon
            parts.icon().showNothing();
        }
        // THE FILE-TYPE ICON ONLY WHEN THERE IS NO SYMBOL. They are different CSS properties -- a kind
        // paints `background`, a file type paints `overlay` -- so writing both draws both.
        //
        // EMPTY, never null: null is how the cascade spells "nobody set this", so writing it would leave
        // the previous file's icon in place on a recycled row rather than clearing it.
        //
        // DEFAULT origin, matching what TreeView already does for the row's indent. The theme JSON is a
        // default the cascade can beat -- write it INLINE and `.filetype-java { overlay: icon(...) }` in
        // a stylesheet silently does nothing, which makes the icon the one part of a row a theme cannot
        // touch.
        CgUiDrawable painted = declared != null ? CgUiDrawable.EMPTY
                : (glyph == null ? CgUiDrawable.EMPTY : glyph);
        StyleGroup.defaultPipeline(parts.icon().getStyle().getGeneralGroup(),
                g -> g.overlay(painted));
        // A DECLARATION'S WORDS, OR A DIRECTORY'S. Both from `SymbolIcon`, which is where every "what
        // is this node, in words" answer lives -- so the picture and the sentence cannot drift apart.
        String described = declared != null
                ? SymbolIcon.describe(declared.kind(), declared.modifiers())
                : SymbolIcon.describe(role);
        Tooltip tip = tips.get(template);
        if (tip != null) {
            // NOTHING FOR THE ROW ITSELF. A tab needs its path -- it is a bare name with no structure
            // around it -- and a tree row is the structure, so repeating the path is noise over the
            // thing that already says it. The ICON is the only part with something of its own to say,
            // and an empty tooltip now stays hidden rather than drawing a bare box. @see Tooltip
            tip.addRegion(parts.icon(), described == null ? "" : described);
        }
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
     * What this file declares, or null.
     *
     * <p>Asked of the resource's own provider, which is how a tab already answers the same question. A
     * row outside a source root, one whose text nobody has read yet, or a file of a language with no
     * provider all answer null and keep the file-type icon -- the three-tier degradation the language
     * stack is built on rather than a fallback invented here.</p>
     */
    @Nullable
    private static SymbolInfo declaredIn(CgPath file) {
        Resource resource = Resource.of(file);
        ResourceContentProvider provider = ResourceRegistry.providerFor(resource);
        if (provider == null) return null;
        SymbolInfo symbol = provider.symbolOf(resource);
        return symbol == null || symbol.kind() == null ? null : symbol;
    }

    /** One tooltip per template, attached once. @see #createTemplate */
    private final Map<UIElement, Tooltip> tips = new HashMap<>();

    @Override
    public void unbind(UIElement template) {
        tree.rowItems().remove(template);
    }
}
