package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.MatchedRules;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.selector.Selector;
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
 * <p><b>Inline first, then the rules, strongest first.</b> That is the order a person reads a cascade in when
 * they are about to change something: what is winning now, then what it beat.</p>
 *
 * <p>A rule is listed by having matched the element, or — for a rule in a project sheet the cascade has not
 * numbered yet, such as one just written — by its selector matching. Asked when the tab is built, never per
 * frame: it parses each sheet a rule came from once.</p>
 */
public final class StyleTargets {

    private final List<StyleTarget> targets;

    private StyleTargets(List<StyleTarget> targets) {
        this.targets = List.copyOf(targets);
    }

    /** Inline plus every rule that reached {@code node}. Never empty: inline is always a target. */
    public static StyleTargets of(@Nullable UIElement node, @Nullable SheetDocuments sheets) {
        List<StyleTarget> out = new ArrayList<>();
        out.add(StyleTarget.inline());
        if (node == null) return new StyleTargets(out);

        List<StyleSheet> engineSheets = sheetsOf(node);
        Map<StyleSheet, CssSourceModel> parsed = new IdentityHashMap<>();
        List<MatchedRules.Rule> matched = MatchedRules.of(node);
        for (int i = matched.size() - 1; i >= 0; i--) {
            MatchedRules.Rule rule = matched.get(i);
            if (rule.origin() == StyleOrigin.INLINE) continue;
            if (rule.sheetIndex() < 0 || rule.sheetIndex() >= engineSheets.size()) continue;
            StyleSheet sheet = engineSheets.get(rule.sheetIndex());
            SheetDocuments.Sheet entry = sheets == null ? null : sheets.of(sheet);
            CssSourceModel model = parsed.computeIfAbsent(sheet, key -> modelOf(key, entry));
            CssSourceModel.Rule written = model == null ? null : model.ruleAt(rule.ruleOrder());
            if (written == null) continue;
            String selector = model.textOf(written.selectorsRange()).trim();
            out.add(entry != null
                    ? StyleTarget.rule(entry, rule.ruleOrder(), selector)
                    : StyleTarget.engineRule(sheet, sheet == StyleSheet.DEFAULT ? "engine" : "theme",
                            rule.ruleOrder(), selector));
        }
        addUnnumberedRules(node, sheets, out);
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

    /** The rule written as {@code selector} in the sheet labelled {@code sheetLabel}, however it is keyed. */
    @Nullable
    public StyleTarget find(String sheetLabel, String selector) {
        return find(targets, sheetLabel, selector);
    }

    @Nullable
    private static StyleTarget find(List<StyleTarget> targets, String sheetLabel, String selector) {
        for (StyleTarget target : targets) {
            if (!target.isInline() && target.sheetLabel().equals(sheetLabel) && target.selector().equals(selector)) {
                return target;
            }
        }
        return null;
    }

    private static List<StyleSheet> sheetsOf(UIElement node) {
        UIDocument window = node.document();
        return window == null ? List.of() : window.styles().getSheets();
    }

    @Nullable
    private static CssSourceModel modelOf(StyleSheet sheet, @Nullable SheetDocuments.Sheet entry) {
        String text = entry != null && entry.buffer() != null ? entry.buffer().toString() : sheet.source();
        return text == null ? null : parse(text);
    }

    /**
     * The last few sheets parsed, keyed by their TEXT, so an edit is a new key and nothing is served stale.
     *
     * <p>Every Inspector section asks for its target, and parsing the user-agent sheet -- six thousand lines --
     * each time was forty milliseconds a section: half a second to select a node.</p>
     */
    private static final Map<String, CssSourceModel> PARSED = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CssSourceModel> eldest) {
            return size() > 8;
        }
    };

    private static CssSourceModel parse(String text) {
        synchronized (PARSED) {
            return PARSED.computeIfAbsent(text, CssSourceModel::parse);
        }
    }

    /**
     * Rules in the project's own sheets that match the element and the cascade has not numbered: one just
     * written and still empty, or one whose every declaration is commented out. Without these, <i>new
     * rule</i> would write a rule nothing could then be written into.
     */
    private static void addUnnumberedRules(UIElement node, @Nullable SheetDocuments sheets, List<StyleTarget> out) {
        if (sheets == null) return;
        for (SheetDocuments.Sheet sheet : sheets.sheets()) {
            if (!sheet.isEditable() || sheet.buffer() == null) continue;
            CssSourceModel model = parse(sheet.buffer().toString());
            for (CssSourceModel.Rule rule : model.rules()) {
                if (rule.sourceOrder() >= 0 && !rule.declarations().isEmpty()) continue;
                String selector = model.textOf(rule.selectorsRange()).trim();
                if (!matches(selector, node) || find(out, sheet.label(), selector) != null) continue;
                out.add(StyleTarget.rule(sheet, -1, selector));
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
}
