package com.crystalgui.widget.overlay;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.core.attribute.AttributeClipboard;
import com.crystalgui.core.attribute.AttributeSet;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;

/**
 * <b>Paste Attributes</b> — choose which of the copied properties actually land.
 *
 * <pre>{@code
 * PasteAttributesDialog.open(from, copied, target::pasteAttributes);
 * }</pre>
 *
 * <p>Premiere's and DaVinci Resolve's window, and Excel's Paste Special before both: a checkbox per
 * property under a heading its owner chose, with a master tick per group. Everything is on by default,
 * which is what all three do — the common case is "give this one the same treatment", and the boxes are
 * there for the times it is not.</p>
 *
 * <p><b>It lists only what was copied.</b> Resolve shows its whole catalogue and greys out what is absent;
 * here the set carries exactly the properties the source had. A window offering forty rows of which three
 * are live is a worse way to answer the same question.</p>
 *
 * <h3>Modeless</h3>
 *
 * <p>The choice is about two elements that are both on screen behind it. A modal blocks looking at either
 * one, which is the first thing anybody does when a checkbox list asks them a question about their own
 * document.</p>
 */
public final class PasteAttributesDialog {

    public static final String DIALOG_CLASS = "__paste-attributes__";

    /** The From/To block, one of its lines, and the two halves of a line. */
    public static final String HEADER_CLASS = "__paste-header__";

    public static final String LINE_CLASS = "__paste-line__";

    public static final String KEY_CLASS = "__paste-key__";

    public static final String VALUE_CLASS = "__paste-value__";

    /** The rule under the header, separating what is being pasted from what of it to take. */
    public static final String RULE_CLASS = "__paste-rule__";

    /** The boxed grid of properties under a heading. */
    public static final String SECTION_CLASS = "__paste-section__";

    /** A group's master tick, the row of properties under it, and one property. */
    public static final String GROUP_CLASS = "__paste-group__";

    public static final String ITEM_CLASS = "__paste-item__";

    /** The one checkbox that is not a property. */
    public static final String REMEMBER_CLASS = "__paste-remember__";

    public static final String ACTIONS_CLASS = "__paste-actions__";

    /** The two buttons, so they can be sized together and kept off the left of the row. */
    public static final String BUTTONS_CLASS = "__paste-buttons__";

    private PasteAttributesDialog() {
    }

    /**
     * Opens the window on the document {@code from} belongs to.
     *
     * @param onApply given the chosen subset, never the whole set unless everything is ticked
     * @return the window it opened, or null when there was nothing to ask about
     */
    @Nullable
    public static Dialog open(@Nullable UIElement from, AttributeSet copied, String target,
                              Consumer<AttributeSet> onApply) {
        UIDocument window = from == null ? null : from.document();
        if (window == null || copied.isEmpty()) return null;

        Dialog dialog = new Dialog("Paste Attributes");
        dialog.addClass(DIALOG_CLASS);

        // FROM AND TO, both named. Resolve's header, and the reason for the second line is that a paste
        // acts on something the window is sitting over: saying only where it came from leaves the more
        // consequential half -- what is about to change -- to be inferred from what was selected.
        UIElement header = new UIElement();
        header.addClass(HEADER_CLASS);
        StyleGroup.defaultPipeline(header.getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.COLUMN));
        header.append(line("From", copied.source()), line("To", target));
        dialog.getContent().append(header);

        UIElement rule = new UIElement();
        rule.addClass(RULE_CLASS);
        dialog.getContent().append(rule);

        // PAIRED, not two parallel lists. The boxes are built group by group while the entries arrive in
        // the order the carrier listed them, so an index into one is not an index into the other -- Apply
        // would tick the property that happened to share a position with the box.
        Map<Checkbox, AttributeSet.Entry> chosenBy = new LinkedHashMap<>();
        Map<Checkbox, List<Checkbox>> owned = new LinkedHashMap<>();

        for (String group : copied.groups()) {
            // THE HEADING SITS ABOVE THE BOX, not inside it. Resolve's arrangement, and it is the one that
            // survives a wrapped row: put the master tick in with its properties and it becomes the first
            // item of the grid, indistinguishable at a glance from the things it governs.
            Checkbox master = new Checkbox(group);
            master.addClass(GROUP_CLASS);
            master.setChecked(true);
            dialog.getContent().append(master);

            // THE SECTION IS THE GRID. A wrapped row three across, because a list running straight down
            // makes a window as tall as the source element has properties.
            UIElement section = new UIElement();
            section.addClass(SECTION_CLASS);
            StyleGroup.defaultPipeline(section.getStyle().getLayoutGroup(),
                    l -> l.flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP));
            dialog.getContent().append(section);

            List<Checkbox> members = new ArrayList<>();
            for (AttributeSet.Entry entry : copied.entries()) {
                if (!entry.slot().group().equals(group)) continue;
                Checkbox item = new Checkbox(entry.slot().label());
                item.addClass(ITEM_CLASS);
                item.setChecked(true);
                chosenBy.put(item, entry);
                members.add(item);
                section.append(item);
            }
            owned.put(master, members);
        }

        wireGroups(owned);

        Checkbox remember = new Checkbox("Don't show until next copy");
        remember.addClass(REMEMBER_CLASS);

        // ONE ROW: the standing choice on the left, the two ways out on the right. Resolve's, and it is
        // also the only arrangement in which "Don't show until next copy" reads as belonging to Apply
        // rather than to the last group of checkboxes above it.
        UIElement actions = new UIElement();
        actions.addClass(ACTIONS_CLASS);
        StyleGroup.defaultPipeline(actions.getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.ROW));
        dialog.getContent().append(actions);
        actions.append(remember);

        UIElement buttons = new UIElement();
        buttons.addClass(BUTTONS_CLASS);
        StyleGroup.defaultPipeline(buttons.getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.ROW));
        actions.append(buttons);
        Button apply = new Button("Apply");
        Button cancel = new Button("Cancel");
        buttons.append(apply, cancel);

        apply.onPressed.connect(() -> {
            Set<String> wanted = new HashSet<>();
            for (Map.Entry<Checkbox, AttributeSet.Entry> pair : chosenBy.entrySet()) {
                if (pair.getKey().isChecked()) wanted.add(pair.getValue().slot().id());
            }
            AttributeSet chosen = copied.keeping(slot -> wanted.contains(slot.id()));
            if (remember.isChecked()) AttributeClipboard.remember(chosen);
            dialog.close();
            if (!chosen.isEmpty()) onApply.accept(chosen);
        });
        cancel.onPressed.connect(dialog::close);

        window.addOverlay(dialog, from);
        dialog.removeWhenClosed();
        dialog.show();
        // AFTER show, per Dialog's own instruction: its focusing steps take the first focusable
        // descendant, which here is the first group's tick -- so the window opened with a checkbox
        // outlined and Space would have cleared a whole group. Apply is what the person came to do.
        window.focus().requestFocus(apply);
        return dialog;
    }

    /** One header line: a dimmed key and the name it is about. */
    private static UIElement line(String key, String value) {
        UIElement row = new UIElement();
        row.addClass(LINE_CLASS);
        StyleGroup.defaultPipeline(row.getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.ROW));
        UIText label = new UIText(key);
        label.addClass(KEY_CLASS);
        UIText name = new UIText(value);
        name.addClass(VALUE_CLASS);
        row.append(label, name);
        return row;
    }

    /**
     * A master sets its own, and a member corrects its master.
     *
     * <p><b>Behind one guard, because {@code setChecked} announces.</b> Without it the two directions feed
     * each other: a master ticking its first member fires that member's listener, which recomputes the
     * master from a list only partly written, which fires the master's listener again. The visible result
     * was a group that set some of its boxes and not others, and a single member clearing the whole
     * group.</p>
     *
     * <p>Two directions are still wanted rather than one — a group tick left standing while its last
     * member is cleared says something untrue about what Apply will do.</p>
     */
    static void wireGroups(Map<Checkbox, List<Checkbox>> owned) {
        boolean[] settling = {false};
        for (Map.Entry<Checkbox, List<Checkbox>> group : owned.entrySet()) {
            Checkbox master = group.getKey();
            List<Checkbox> members = group.getValue();

            master.attachListener(value -> {
                if (settling[0]) return;
                settling[0] = true;
                try {
                    for (Checkbox item : members) item.setChecked(Boolean.TRUE.equals(value));
                } finally {
                    settling[0] = false;
                }
            });
            for (Checkbox item : members) {
                item.attachListener(ignored -> {
                    if (settling[0]) return;
                    settling[0] = true;
                    try {
                        boolean all = true;
                        for (Checkbox each : members) all &= each.isChecked();
                        master.setChecked(all);
                    } finally {
                        settling[0] = false;
                    }
                });
            }
        }
    }
}
