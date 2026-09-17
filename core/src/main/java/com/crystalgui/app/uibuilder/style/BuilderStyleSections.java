package com.crystalgui.app.uibuilder.style;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.BuilderSelection;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.inspect.MatchedRules;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ConfigForm;
import com.crystalgui.widget.config.PanelForm;
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
 * one of them the write target. <b>Declarations</b> is what that target declares, grouped by family, with one
 * place to add from. <b>Cascade</b> is the read-only history: every rule that reached this element and which
 * of its values lost.</p>
 *
 * <p>A section is rebuilt when the element or the chosen target changes, and at no other time: what the
 * target declares is followed by {@link DeclarationList}, row by row.</p>
 */
public final class BuilderStyleSections {

    public static final String STYLE_TAB = "Style";

    public static final String TARGETS_CLASS = "__style-targets__";
    public static final String TARGET_CLASS = "__style-target__";
    public static final String ACTIVE_CLASS = "__active__";
    public static final String READ_ONLY_CLASS = "__read-only__";
    public static final String ADD_CLASS = "__style-add__";
    public static final String ADD_ROW_CLASS = "__style-add-row__";
    public static final String LIST_CLASS = "__style-declarations__";
    public static final String OVERRIDDEN_CLASS = "__overridden__";
    public static final String ROW_ACTION_CLASS = "__style-row-action__";
    /** On a row's eye, beside {@link #ROW_ACTION_CLASS}: switches the declaration off and on. */
    public static final String ROW_EYE_CLASS = "__style-row-eye__";
    /** On an eye whose declaration is switched off. */
    public static final String OFF_CLASS = "__off__";
    /** On a row that starts with its eye, which takes the place of the name's left inset. */
    public static final String EYED_ROW_CLASS = "__eyed__";
    /** On a row whose declaration is switched off: greyed whole, its editor inert. */
    public static final String HIDDEN_CLASS = "__hidden-declaration__";
    public static final String STYLE_ROW_CLASS = "__style-row__";
    /** A text button in the tab — a rule action — as opposed to a row's one-glyph {@link #ROW_ACTION_CLASS}. */
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

    /** The first sheet of the document a rule could be written into, or null when every one is read-only. */
    @Nullable
    private static SheetDocuments.Sheet firstEditableSheet(DataContext context) {
        SheetDocuments sheets = sheets(context);
        if (sheets == null) return null;
        for (SheetDocuments.Sheet sheet : sheets.sheets()) {
            if (sheet.isEditable()) return sheet;
        }
        return null;
    }

    private static StyleTarget chosen(DataContext context) {
        BuilderSelection selection = selection(context);
        return StyleTargets.of(node(context), sheets(context)).chosen(selection == null ? null : selection.styleTarget());
    }

    private static Button action(String label, String styleClass, Runnable done) {
        Button button = new Button(label);
        button.addClass(styleClass);
        button.attachListener(done);
        return button;
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
            // THE TARGET IS PART OF THE SUBJECT: picking another rule is a different thing to edit. What it
            // declares is not -- DeclarationList follows that row by row, so a scrub that creates a
            // declaration does not replace the control under the pointer.
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
            UIElement node = node(context);
            if (selection == null || node == null) return;
            StyleTargets targets = StyleTargets.of(node, sheets(context));
            StyleTarget writing = targets.chosen(selection.styleTarget());

            UIElement chips = form.custom(new UIElement());
            chips.addClass(TARGETS_CLASS);
            for (StyleTarget target : targets.targets()) chips.append(chip(target, writing, selection));

            SheetDocuments.Sheet sheet = firstEditableSheet(context);
            if (sheet != null) {
                // NAMED BEFORE IT IS PRESSED: a rule written for a selector you did not see restyles elements
                // you were not looking at.
                String selector = RuleActions.selectorFor(node);
                chips.append(action("+ " + selector, TARGET_ACTION_CLASS, () -> {
                    if (RuleActions.newRule(sheet, selector) == null) return;
                    // AS THE LIST NAMES IT: by number when the rule already declares something, else by selector.
                    StyleTarget rule = StyleTargets.of(node, sheets(context)).find(sheet.label(), selector);
                    if (rule != null) selection.selectStyleTarget(rule.key());
                }));
                if (writing.isInline() && !StyleFields.on(null, writing, node).declared().isEmpty()) {
                    chips.append(action("Extract class", TARGET_ACTION_CLASS, () -> RuleActions.extractClass(
                            sheet, context.get(BuilderEditor.UI_DOCUMENT), node, classNameFor(node))));
                }
            }

            String reason = writing.readOnlyReason();
            if (reason != null) form.note(reason);
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

    /** The target's own declarations, by family, followed as they change. */
    private static final class DeclarationsSection extends StyleAware {

        @Override
        public int order() {
            return 10;
        }

        @Override
        public void build(ConfigForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null || !(form instanceof PanelForm panelForm)) return;
            StyleTarget target = chosen(context);
            UiBuilderDocument document = context.get(BuilderEditor.UI_DOCUMENT);
            StyleFields fields = StyleFields.on(document, target, node);
            form.custom(new DeclarationList(panelForm.panel(), fields, node));

            if (target.isEditable() && !target.isInline()) {
                UIElement actions = new UIElement();
                actions.addClass(StyleLab.ROW_CLASS);
                UIElement anchor = actions;
                actions.append(action("Edit as CSS", TARGET_ACTION_CLASS, () -> RuleTextEditor.open(anchor, target)));
                SheetDocuments sheets = sheets(context);
                SheetDocuments.Sheet sheet = target.sheet();
                if (sheets != null && sheets.canReveal() && sheet != null && sheet.resource() != null) {
                    actions.append(action("Go to source", TARGET_ACTION_CLASS, () -> sheets.goToSource(sheet)));
                }
                form.custom(actions);
            }
            SheetDocuments.Sheet sheet = firstEditableSheet(context);
            if (target.isInline() && sheet != null && !fields.declared().isEmpty()) {
                form.custom(action("Promote to rule", TARGET_ACTION_CLASS,
                        () -> RuleActions.promote(sheet, document, node, RuleActions.selectorFor(node))));
            }
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
}
