package com.crystalgui.style.sheet;

import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.ui.dom.UIElementRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import static org.junit.Assert.assertTrue;

/**
 * <b>Every declaration a shipped sheet writes is one the parser takes.</b>
 *
 * <p>A declaration the parser does not take is dropped with a warning, which reads as a style that
 * silently does nothing: an unknown or misspelt property, a longhand only its shorthand may write, a
 * shorthand whose parts do not parse, or a value that does not compute, such as a length written
 * without its unit. Variables resolve against every shipped sheet's, as the themes supply them.</p>
 */
public class ShippedSheetsParseTest {

    @Test
    public void everyShippedDeclarationParses() {
        UIElementRegistry.bootstrap();
        Map<String, String> sources = new HashMap<>();
        Map<String, String> variables = new HashMap<>();
        for (String path : sheets()) {
            String css = CgIO.loadSource(path);
            if (css == null) continue;
            String stripped = DeclarationParser.stripComments(css);
            sources.put(path, stripped);
            variables.putAll(DeclarationParser.collectVariables(stripped));
        }
        assertTrue("the shipped sheets are not being read: " + sources.keySet(), sources.size() >= 15);
        variables = DeclarationParser.resolveTable(variables);

        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, String> sheet : sources.entrySet()) {
            Matcher rule = DeclarationParser.RULE_PATTERN.matcher(sheet.getValue());
            while (rule.find()) {
                for (DeclarationParser.Raw raw : DeclarationParser.scanBlock(rule.group(2))) {
                    checked++;
                    List<StyleRule.Declaration> out = new ArrayList<>();
                    DeclarationParser.parse(out, raw, variables);
                    String where = raw.name() + ": " + raw.value() + "  (" + sheet.getKey() + ")";
                    if (out.isEmpty()) failures.add("dropped  " + where);
                    for (StyleRule.Declaration d : out) {
                        if (d.value().compute() == null) failures.add(d.property().name + " does not parse  " + where);
                    }
                }
            }
        }
        assertTrue("only " + checked + " declarations read", checked > 1000);
        assertTrue(failures.size() + " shipped declarations the parser does not take:" + System.lineSeparator() + "  "
                        + String.join(System.lineSeparator() + "  ", failures.subList(0, Math.min(40, failures.size()))),
                failures.isEmpty());
    }

    /** The user-agent parts, the themes, and the app sheets, as ShippedStyleRoundTripTest reads them. */
    private static List<String> sheets() {
        List<String> paths = new ArrayList<>(StyleSheetRegistry.DEFAULT_SHEET_PARTS.stream()
                .map(StyleSheetRegistry::toResourcePath).toList());
        paths.add("crystalgui:ui/themes/base.css");
        paths.add("crystalgui:ui/themes/crystal-dark.css");
        paths.add("crystalgui:ui/styles/ore.css");
        paths.add("crystalgui:ui/styles/graph.css");
        paths.add("crystalgui:ui/styles/filetypes.css");
        paths.add("crystalgui:ui/styles/decorations.css");
        return paths;
    }
}
