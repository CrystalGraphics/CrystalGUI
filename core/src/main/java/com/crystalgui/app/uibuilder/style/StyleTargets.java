package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.MatchedRules;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.selector.Selector;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.source.CssSourceModel;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * Everywhere one element's style is written from, as a list to pick from — the Styles tab's chip row.
 *
 * <pre>{@code
 * StyleTargets targets = StyleTargets.of(node, editor.sheets());
 * StyleTarget writing = targets.chosen(selection.styleTarget());   // falls back to inline
 * }</pre>
 *
 * <p><b>Inline first, then the rules strongest last-won first.</b> That is the order a person reads a
 * cascade in when they are about to change something: what is winning now, then what it beat. (
 * {@link MatchedRules} answers weakest-first, which is right for a pane that shows the history; this
 * reverses it.)</p>
 *
 * <p>A rule reaches this list by having matched the element — so the chips are a true statement about this
 * element rather than a sheet listing. A rule's declarations are read from the <b>text</b>, which is what
 * makes an editor honest: a value the cascade never applied is still in the file and still shown.</p>
 */
public final class StyleTargets {

    private final List<StyleTarget> targets;

    private StyleTargets(List<StyleTarget> targets) {
        this.targets = List.copyOf(targets);
    }

    /** Inline plus every rule that reached {@code node}. Never empty: inline is always a target. */
    public static StyleTargets of(@Nullable UIElement node, @Nullable SheetDocuments sheets) {
        if (node == null) return new StyleTargets(List.of(StyleTarget.inline(List.of())));

        List<MatchedRules.Rule> matched = MatchedRules.of(node);
        List<StyleSheet> engineSheets = sheetsOf(node);

        List<StyleTarget> out = new ArrayList<>();
        out.add(StyleTarget.inline(inlineDeclarations(node, matched)));
        // Strongest first -- MatchedRules answers weakest first.
        for (int i = matched.size() - 1; i >= 0; i--) {
            MatchedRules.Rule rule = matched.get(i);
            if (rule.sheetIndex() < 0 || rule.sheetIndex() >= engineSheets.size()) continue;
            StyleTarget target = ruleTarget(engineSheets.get(rule.sheetIndex()), rule, sheets);
            if (target != null) out.add(target);
        }
        addWritableRules(node, sheets, out);
        return new StyleTargets(out);
    }

    /** Inline, then the rules. */
    public List<StyleTarget> targets() {
        return targets;
    }

    /** The target {@code key} names, or inline when nothing here has that key any more. */
    public StyleTarget chosen(@Nullable String key) {
        if (key != null && !key.isEmpty()) {
            for (StyleTarget target : targets) {
                if (target.key().equals(key)) return target;
            }
        }
        return targets.get(0);
    }

    // ── Building ────────────────────────────────────────────────────────────

    private static List<StyleSheet> sheetsOf(UIElement node) {
        UIDocument window = node.document();
        return window == null ? List.of() : window.styles().getSheets();
    }

    @Nullable
    private static StyleTarget ruleTarget(StyleSheet sheet, MatchedRules.Rule rule,
                                          @Nullable SheetDocuments sheets) {
        SheetDocuments.Sheet entry = sheets == null ? null : sheets.of(sheet);
        String text = entry != null && entry.buffer() != null ? entry.buffer().toString() : sheet.source();
        if (text == null) return null;

        CssSourceModel model = CssSourceModel.parse(text);
        CssSourceModel.Rule written = model.ruleAt(rule.ruleOrder());
        if (written == null) return null;

        Map<String, Boolean> won = new HashMap<>();
        for (MatchedRules.Declaration declaration : rule.declarations()) {
            // A rule that declares one property twice wins on the last one; either winning is enough for
            // the row not to be struck through.
            won.merge(declaration.property().name, declaration.won(), (a, b) -> a || b);
        }

        String label = entry != null ? entry.label() : engineLabel(sheet);
        return new StyleTarget(entry, label, rule.ruleOrder(), model.textOf(written.selectorsRange()).trim(),
                declarationsOf(model, written, won));
    }

    /**
     * Rules in the project's own sheets that match the element and the cascade never mentioned: one just
     * written and still empty, or one whose every declaration is commented out.
     *
     * <p>They are written into exactly like any other — which is what makes <i>new rule</i> usable at all,
     * since a rule with nothing in it has no number and so cannot be reached through the cascade.</p>
     */
    private static void addWritableRules(UIElement node, @Nullable SheetDocuments sheets, List<StyleTarget> out) {
        if (sheets == null) return;
        for (SheetDocuments.Sheet sheet : sheets.sheets()) {
            if (!sheet.isEditable() || sheet.buffer() == null) continue;
            CssSourceModel model = CssSourceModel.parse(sheet.buffer().toString());
            for (CssSourceModel.Rule rule : model.rules()) {
                String selector = model.textOf(rule.selectorsRange()).trim();
                if (rule.sourceOrder() >= 0 && !rule.declarations().isEmpty()) continue;
                if (!matches(selector, node) || holds(out, sheet.label(), selector)) continue;
                out.add(new StyleTarget(sheet, sheet.label(), rule.sourceOrder(), selector,
                        declarationsOf(model, rule, Map.of())));
            }
        }
    }

    /** Whether a selector as written reaches {@code node}; unparseable text reaches nothing. */
    private static boolean matches(String selector, UIElement node) {
        try {
            Selector parsed = Selector.parse(selector);
            return parsed != null && parsed.matches(node);
        } catch (RuntimeException unparseable) {
            return false;
        }
    }

    private static boolean holds(List<StyleTarget> targets, String sheetLabel, String selector) {
        for (StyleTarget target : targets) {
            if (!target.isInline() && target.sheetLabel().equals(sheetLabel)
                    && target.selector().equals(selector)) {
                return true;
            }
        }
        return false;
    }

    /** A sheet the document does not name — the engine's own, or a theme's. */
    private static String engineLabel(StyleSheet sheet) {
        return sheet == StyleSheet.DEFAULT ? "engine" : "theme";
    }

    private static List<StyleTarget.Declared> declarationsOf(CssSourceModel model, CssSourceModel.Rule rule,
                                                            Map<String, Boolean> won) {
        List<StyleTarget.Declared> out = new ArrayList<>();
        for (CssSourceModel.Declaration declaration : rule.declarations()) {
            out.add(new StyleTarget.Declared(StylePropertyRegistry.byName(declaration.property()),
                    declaration.property(), declaration.value(), declaration.important(),
                    won.getOrDefault(declaration.property(), false), false));
        }
        // A DECLARATION TOGGLED OFF IS A COMMENT, and listing it is what makes the toggle reversible --
        // otherwise switching a row off deletes it as far as the pane is concerned.
        for (CssSourceModel.Comment comment : model.comments()) {
            int at = comment.range().start();
            if (at < rule.bodyRange().start() || at >= rule.bodyRange().end()) continue;
            StyleTarget.Declared disabled = asDeclaration(comment.text());
            if (disabled != null) out.add(disabled);
        }
        return out;
    }

    /** A commented-out declaration, or null for an ordinary comment. */
    @Nullable
    private static StyleTarget.Declared asDeclaration(String comment) {
        String text = comment.trim();
        if (text.startsWith("/*")) text = text.substring(2);
        if (text.endsWith("*/")) text = text.substring(0, text.length() - 2);
        text = text.trim();
        if (text.endsWith(";")) text = text.substring(0, text.length() - 1).trim();
        int colon = text.indexOf(':');
        if (colon <= 0 || text.indexOf('{') >= 0) return null;
        String name = text.substring(0, colon).trim();
        String value = text.substring(colon + 1).trim();
        if (name.isEmpty() || value.isEmpty() || name.indexOf(' ') >= 0) return null;
        return new StyleTarget.Declared(StylePropertyRegistry.byName(name), name, value, false, false, true);
    }

    /** What is set on the element itself, in the order the cascade holds it. */
    private static List<StyleTarget.Declared> inlineDeclarations(UIElement node, List<MatchedRules.Rule> matched) {
        Set<StyleProperty<?>> won = new LinkedHashSet<>();
        for (MatchedRules.Rule rule : matched) {
            if (rule.origin() != StyleOrigin.INLINE) continue;
            for (MatchedRules.Declaration declaration : rule.declarations()) {
                if (declaration.won()) won.add(declaration.property());
            }
        }
        List<StyleTarget.Declared> out = new ArrayList<>();
        for (Map.Entry<StyleProperty<?>, List<StyleSlot<?>>> entry : node.getStyle().candidates.entrySet()) {
            for (StyleSlot<?> slot : entry.getValue()) {
                if (slot.origin() != StyleOrigin.INLINE) continue;
                StyleProperty<?> property = entry.getKey();
                out.add(new StyleTarget.Declared(property, property.name, write(property, slot.value()),
                        false, won.contains(property), false));
                break;
            }
        }
        return out;
    }

    /** A value as CSS — the property's own writer, which is what a sheet would have to hold to mean this. */
    @SuppressWarnings("unchecked")
    private static String write(StyleProperty<?> property, @Nullable Object value) {
        if (value == null) return "";
        return ((StyleProperty<Object>) property).write(value);
    }
}
