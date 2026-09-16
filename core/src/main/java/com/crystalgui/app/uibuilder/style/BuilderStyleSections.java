package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.BuilderSelection;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.inspect.MatchedRules;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ConfigForm;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.config.inspector.InspectorSection;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * The Styles tab: where an edit lands, and what the thing it lands in declares.
 *
 * <pre>{@code
 * for (InspectorSection section : BuilderStyleSections.all()) registry.register(section);
 * }</pre>
 *
 * <p>Three sections, in this order. <b>Targets</b> is the chip row — inline, then every rule that matched,
 * one of them the write target. <b>Declarations</b> is what that target declares, grouped by family, each
 * family offering the rest of its properties behind a {@code +}. <b>Cascade</b> is the read-only history:
 * every rule that reached this element and which of its values lost.</p>
 *
 * <p>Only what the target <em>declares</em> is listed. A pane that listed every property at its computed
 * value would bury the three lines the rule actually holds, which is the thing a person came to change.</p>
 */
public final class BuilderStyleSections {

    public static final String STYLE_TAB = "Style";

    public static final String TARGETS_CLASS = "__style-targets__";
    public static final String TARGET_CLASS = "__style-target__";
    public static final String ACTIVE_CLASS = "__active__";
    public static final String READ_ONLY_CLASS = "__read-only__";
    public static final String FAMILY_HEAD_CLASS = "__style-family__";
    public static final String FAMILY_LABEL_CLASS = "__style-family-label__";
    public static final String ADD_CLASS = "__style-add__";
    public static final String DISABLED_CLASS = "__inactive__";
    public static final String OVERRIDDEN_CLASS = "__overridden__";
    public static final String ROW_ACTION_CLASS = "__style-row-action__";
    public static final String TARGET_ACTION_CLASS = "__style-target-action__";

    private BuilderStyleSections() {
    }

    /** The sections, for the one registration the builder makes. */
    public static List<InspectorSection> all() {
        // The labs belong to properties rather than to a panel, so they are registered with the sections
        // that will ask for them rather than by whoever happens to open a builder first.
        StyleLabs.register();
        return List.of(new TargetsSection(), new DeclarationsSection(), new CascadeSection());
    }

    // ── Shared ──────────────────────────────────────────────────────────────

    @Nullable
    private static BuilderSelection selection(DataContext context) {
        return context.get(BuilderEditor.BUILDER_SELECTION);
    }

    @Nullable
    private static UIElement node(DataContext context) {
        BuilderSelection selection = selection(context);
        return selection == null ? null : selection.node();
    }

    @Nullable
    private static SheetDocuments sheets(DataContext context) {
        BuilderEditor editor = context.get(BuilderEditor.UI_BUILDER);
        return editor == null ? null : editor.sheets();
    }

    private static StyleTargets targetsOf(DataContext context) {
        return StyleTargets.of(node(context), sheets(context));
    }

    /** The first sheet of the document a rule could be written into, or null when every one is read-only. */
    @Nullable
    private static TextBuffer firstEditableSheet(DataContext context) {
        SheetDocuments sheets = sheets(context);
        if (sheets == null) return null;
        for (SheetDocuments.Sheet sheet : sheets.sheets()) {
            if (sheet.isEditable()) return sheet.buffer();
        }
        return null;
    }

    /** What a new rule's target key names the sheet by. @see StyleTarget#key */
    private static String sheetLabel(DataContext context) {
        SheetDocuments sheets = sheets(context);
        if (sheets == null) return "";
        for (SheetDocuments.Sheet sheet : sheets.sheets()) {
            if (sheet.isEditable()) return sheet.label();
        }
        return "";
    }

    private static StyleTarget chosen(DataContext context) {
        BuilderSelection selection = selection(context);
        return targetsOf(context).chosen(selection == null ? null : selection.styleTarget());
    }

    /** Shared by every section here: one node, and the chosen target is part of the subject. */
    private abstract static class StyleAware implements InspectorSection {

        @Override
        public String tab() {
            return STYLE_TAB;
        }

        @Override
        public boolean accepts(DataContext context) {
            return node(context) != null;
        }

        @Override
        public String subjectKey(DataContext context) {
            UIElement node = node(context);
            BuilderSelection selection = selection(context);
            // THE TARGET IS PART OF THE SUBJECT: picking another rule is a different thing to edit, so the
            // form is meant to be rebuilt -- unlike a value change, which must leave the controls alone.
            return getClass().getSimpleName() + ":" + (node == null ? "" : System.identityHashCode(node))
                    + ":" + (selection == null ? "" : selection.styleTarget());
        }
    }

    // ── Where an edit lands ─────────────────────────────────────────────────

    /** The chip row: inline, then every rule that matched, one of them the write target. */
    private static final class TargetsSection extends StyleAware {

        @Override
        public int order() {
            return 0;
        }

        @Override
        public void build(ConfigForm form, DataContext context) {
            BuilderSelection selection = selection(context);
            if (selection == null) return;
            StyleTargets targets = targetsOf(context);
            StyleTarget writing = targets.chosen(selection.styleTarget());

            UIElement chips = form.custom(new UIElement());
            chips.addClass(TARGETS_CLASS);
            for (StyleTarget target : targets.targets()) chips.append(chip(target, writing, selection));

            UIElement node = node(context);
            TextBuffer sheet = firstEditableSheet(context);
            if (node != null && sheet != null) {
                String selector = RuleActions.selectorFor(node);
                chips.append(action("+ " + selector, () -> {
                    // NAMED BEFORE IT IS PRESSED: a rule written for a selector you did not see is a rule
                    // that restyles elements you were not looking at. An empty rule has no number yet, so
                    // it is picked by the selector it was written with.
                    if (RuleActions.newRule(sheet, selector)) {
                        selection.selectStyleTarget("rule:" + sheetLabel(context) + ":" + selector);
                    }
                }));
                if (writing.isInline() && !writing.declarations().isEmpty()) {
                    chips.append(action("Extract class", () -> RuleActions.extractClass(sheet,
                            context.get(BuilderEditor.UI_DOCUMENT), node, classNameFor(node))));
                }
            }

            String reason = writing.readOnlyReason();
            if (reason != null) form.note(reason);
        }

        /** A button on the chip row: the rule actions, which are about targets rather than declarations. */
        private static Button action(String label, Runnable done) {
            Button button = new Button(label);
            button.addClass(TARGET_ACTION_CLASS);
            button.attachListener(done);
            return button;
        }

        /** The class an extract would make: the node's id, else its kind, since it has no class yet. */
        private static String classNameFor(UIElement node) {
            String id = node.id();
            return id != null && !id.isEmpty() ? id : node.name().local();
        }

        private static UIElement chip(StyleTarget target, StyleTarget writing, BuilderSelection selection) {
            UIElement chip = new UIElement();
            chip.addClass(TARGET_CLASS);
            if (target.key().equals(writing.key())) chip.addClass(ACTIVE_CLASS);
            if (!target.isEditable()) chip.addClass(READ_ONLY_CLASS);
            chip.setHitTest(true);
            chip.append(new UIText(target.label()));
            if (!target.isInline()) {
                UIText where = new UIText(target.sheetLabel());
                where.addClass("__style-target-sheet__");
                chip.append(where);
            }
            chip.onMouseDown.attachListener((element, event) -> {
                selection.selectStyleTarget(target.key());
                event.preventDefault();
            }, false, true);
            return chip;
        }
    }

    // ── What it declares ────────────────────────────────────────────────────

    /** The target's own declarations, by family, each family offering the rest behind a {@code +}. */
    private static final class DeclarationsSection extends StyleAware {

        @Override
        public int order() {
            return 10;
        }

        @Override
        public void build(ConfigForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            StyleTarget target = chosen(context);
            StyleFields fields = StyleFields.on(context.get(BuilderEditor.UI_DOCUMENT), target, node);

            for (StyleFamilies.Family family : StyleFamilies.inOrder()) {
                List<StyleTarget.Declared> declared = declaredIn(target, family);
                if (declared.isEmpty() && family == StyleFamilies.Family.OTHER) continue;
                form.custom(familyHead(family, target, fields));
                for (StyleTarget.Declared declaration : declared) row(form, fields, node, declaration);
            }
            if (target.isEditable() && !target.isInline()) {
                UIElement actions = new UIElement();
                actions.addClass("__lab-row__");
                Button asCss = new Button("Edit as CSS");
                asCss.addClass(ROW_ACTION_CLASS);
                asCss.attachListener(() -> RuleTextEditor.open(asCss, target));
                actions.append(asCss);

                SheetDocuments.Sheet sheet = target.sheet();
                if (sheet != null && sheet.resource() != null) {
                    Button source = new Button("Go to source");
                    source.addClass(ROW_ACTION_CLASS);
                    source.attachListener(() -> SheetDocuments.goToSource(sheet, target.ruleOrder()));
                    actions.append(source);
                }
                form.custom(actions);
            }
            if (target.isInline() && !target.declarations().isEmpty()) {
                TextBuffer sheet = firstEditableSheet(context);
                if (sheet != null) {
                    Button promote = new Button("Promote to rule");
                    promote.addClass(ROW_ACTION_CLASS);
                    promote.attachListener(() -> {
                        String selector = RuleActions.selectorFor(node);
                        for (StyleTarget.Declared declared : target.declarations()) {
                            if (declared.property() == null) continue;
                            RuleActions.promote(sheet, context.get(BuilderEditor.UI_DOCUMENT), node,
                                    declared.property(), selector);
                        }
                    });
                    form.custom(promote);
                }
            }
        }

        private static List<StyleTarget.Declared> declaredIn(StyleTarget target, StyleFamilies.Family family) {
            List<StyleTarget.Declared> out = new ArrayList<>();
            for (StyleTarget.Declared declared : target.declarations()) {
                if (StyleFamilies.of(declared.property(), declared.name()) == family) out.add(declared);
            }
            return out;
        }

        /** {@code FILL   +} — the family's name, and what it can add. */
        private static UIElement familyHead(StyleFamilies.Family family, StyleTarget target, StyleFields fields) {
            UIElement head = new UIElement();
            head.addClass(FAMILY_HEAD_CLASS);
            UIText label = new UIText(family.label().toUpperCase(Locale.ROOT));
            label.addClass(FAMILY_LABEL_CLASS);
            head.append(label);
            if (!fields.canWrite()) return head;

            Button add = new Button("+");
            add.addClass(ADD_CLASS);
            add.attachListener(() -> PropertyPalette.open(add, family,
                    name -> target.declaring(name) != null,
                    // A PICK ADDS THE DECLARATION AT ITS INITIAL VALUE, so the row appears with something in
                    // it rather than as an empty line the sheet would not parse.
                    property -> fields.add(property.name, initialOf(property))));
            head.append(add);
            return head;
        }

        /** What a newly added declaration starts at: the property's own initial, as CSS. */
        private static String initialOf(StyleProperty<?> property) {
            String written = write(property, property.initialValue);
            return written == null || written.isBlank() ? "initial" : written;
        }

        private static void row(ConfigForm form, StyleFields fields, UIElement node,
                                StyleTarget.Declared declared) {
            String id = "style." + declared.name();
            DeclarationEditors.Field field = declared.disabled()
                    // A commented-out declaration is TEXT until it is switched back on: its value is not in
                    // the cascade, so a typed control would be editing something that is not there.
                    ? new DeclarationEditors.Field(ConfigDescriptor.info(id, declared.name()),
                            Property.derived(declared::value))
                    : DeclarationEditors.of(declared.property(), id, declared.name(),
                            fields.value(declared.name()), fields, node);

            Configurator row = field.control() == null
                    ? form.prop(field.descriptor(), cast(field.value()))
                    : form.control(id, declared.name(), field.control());
            // CTRL+Z IN THE ROW REACHES THE FILE THE ROW WROTE TO: a rule's edit is in the sheet's buffer,
            // which is a different history from the document's and the same one its editor tab uses.
            TextBuffer buffer = fields.target().buffer();
            if (buffer != null) row.editedIn(buffer.history());
            if (declared.disabled()) row.addClass(DISABLED_CLASS);
            if (!declared.won() && !declared.disabled()) row.addClass(OVERRIDDEN_CLASS);
            if (!fields.canWrite()) return;

            if (!fields.target().isInline()) {
                Button toggle = new Button(declared.disabled() ? "☐" : "☑");
                toggle.addClass(ROW_ACTION_CLASS);
                toggle.attachListener(() -> fields.setEnabled(declared.name(), declared.disabled()));
                row.append(toggle);
            }
            Button remove = new Button("×");
            remove.addClass(ROW_ACTION_CLASS);
            remove.attachListener(() -> fields.remove(declared.name()));
            row.append(remove);
        }
    }

    // ── What it beat ────────────────────────────────────────────────────────

    /** Every rule that reached the element, read-only, with what lost struck through. Collapsed. */
    private static final class CascadeSection extends StyleAware {

        @Override
        public int order() {
            return 80;
        }

        @Override
        public void build(ConfigForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            ConfigForm cascade = form.group("Cascade", true);
            for (MatchedRules.Rule rule : MatchedRules.of(node)) {
                cascade.header(rule.origin().name().toLowerCase(Locale.ROOT)
                        + (rule.sheetIndex() < 0 ? "" : "  rule " + rule.ruleOrder()));
                for (MatchedRules.Declaration declaration : rule.declarations()) {
                    // OVERRIDDEN, not hidden: that a declaration matched and lost is what this shows.
                    String label = declaration.won()
                            ? declaration.property().name
                            : declaration.property().name + "  (overridden)";
                    cascade.row(ConfigDescriptor.info("matched." + rule.origin() + "." + rule.ruleOrder()
                            + "." + declaration.property().name, label), String.valueOf(declaration.value()));
                }
            }
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static String write(StyleProperty<?> property, @Nullable Object value) {
        return value == null ? null : ((StyleProperty<Object>) property).write(value);
    }

    @SuppressWarnings("unchecked")
    private static Property<Object> cast(Property<?> property) {
        return (Property<Object>) property;
    }
}
