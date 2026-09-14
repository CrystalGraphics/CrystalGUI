package com.crystalgui.app.uibuilder.insert;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.canvas.DropResolver;
import com.crystalgui.app.uibuilder.document.TreeDropRules;
import com.crystalgui.app.uibuilder.glyph.KindGlyphs;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.dnd.SortPlacement;

/**
 * Where an insert lands: into {@code parent} at child {@code index}, said as a designer reads it — inside a
 * container, or before or after a node.
 *
 * <pre>{@code
 * List<InsertTarget> places = InsertTarget.around(root, selected, null);   // Shift+Space: inside, before, after
 * places.get(0).word() + " " + places.get(0).name();                       // "Inside Card #profile"
 * }</pre>
 *
 * @param reference what the words name: the container for {@link Relation#INSIDE}, the sibling otherwise
 */
public record InsertTarget(UIElement parent, int index, Relation relation, UIElement reference) {

    public enum Relation {
        INSIDE("Inside"), BEFORE("Before"), AFTER("After");

        private final String word;

        Relation(String word) {
            this.word = word;
        }

        public String word() {
            return word;
        }
    }

    /**
     * The places around {@code anchor}, deduplicated, first the one an insert takes by default.
     *
     * <ul>
     *   <li>{@code resolved}, a pointer's drop, comes first when there is one: the place a drag to that point
     *       would take.</li>
     *   <li>Then inside {@code anchor} at its end when it takes children, then before it and after it when it has
     *       a parent that does — inside before after, the order Tab walks.</li>
     *   <li>With no pointer, a node that takes no children is placed after itself by default.</li>
     * </ul>
     */
    public static List<InsertTarget> around(UIElement root, UIElement anchor, @Nullable DropResolver.Drop resolved) {
        List<InsertTarget> out = new ArrayList<>();
        if (resolved != null) add(out, of(resolved));
        boolean container = TreeDropRules.isContainer(root, anchor);
        UIElement parent = anchor == root ? null : anchor.parentElement();
        boolean sibling = parent != null && TreeDropRules.isContainer(root, parent);
        if (container) add(out, new InsertTarget(anchor, anchor.children().size(), Relation.INSIDE, anchor));
        if (sibling) {
            int at = parent.indexOf(anchor);
            if (resolved == null && !container) {
                add(out, new InsertTarget(parent, at + 1, Relation.AFTER, anchor));
                add(out, new InsertTarget(parent, at, Relation.BEFORE, anchor));
            } else {
                add(out, new InsertTarget(parent, at, Relation.BEFORE, anchor));
                add(out, new InsertTarget(parent, at + 1, Relation.AFTER, anchor));
            }
        }
        return out;
    }

    /** A pointer's drop, said against the child it lands beside. */
    static InsertTarget of(DropResolver.Drop drop) {
        DropResolver.Against against = drop.against();
        if (against == null) return new InsertTarget(drop.target(), drop.index(), Relation.INSIDE, drop.target());
        Relation relation = against.side() == SortPlacement.Side.BEFORE ? Relation.BEFORE : Relation.AFTER;
        return new InsertTarget(drop.target(), drop.index(), relation, against.child());
    }

    /** {@code Inside}, {@code Before} or {@code After}. */
    public String word() {
        return relation.word();
    }

    /** What {@link #reference} is called: its kind's words, and its id when it has one. */
    public String name() {
        String words = KindGlyphs.of(reference).words();
        String id = reference.id();
        return id.isEmpty() ? words : words + " #" + id;
    }

    private static void add(List<InsertTarget> out, InsertTarget target) {
        for (InsertTarget kept : out) {
            if (kept.parent() == target.parent() && kept.index() == target.index()) return;
        }
        out.add(target);
    }
}
