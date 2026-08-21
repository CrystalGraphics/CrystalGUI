package com.crystalgui.style;

import com.crystalgui.render.texture.asset.JavaNodeIcons;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>One kind, one glyph — asserted across the two places that have to agree.</b>
 *
 * <h3>Why the vocabulary is written twice at all</h3>
 *
 * <p>A completion row and the documentation popup's owner band are ELEMENTS, so they carry a
 * {@code completion-kind-*} class and let the cascade choose the picture — which is right, and is where
 * the table belongs. A <b>dock tab</b> cannot: {@code DockGroup.applyIcon} resolves an icon NAME through
 * {@code CgUiSvg.ofIcon} and sets it as an overlay, because a tab's icon is chosen per panel rather than
 * per state. So the same answer is needed in two forms.</p>
 *
 * <p>Two tables that agree today and drift the first time a kind is added to one of them is the ordinary
 * failure, and it is invisible: a viewer tab showing a class glyph on an interface looks like a tab with
 * an icon, not like a tab with the wrong one. This parses the stylesheet and asserts they still say the
 * same thing.</p>
 *
 * <h3>What it deliberately does not check</h3>
 *
 * <p>That every kind HAS a glyph — some have none on purpose ({@code KEYWORD} is not a node and
 * JetBrains draws no icon for one), and the stylesheet says so by having no rule. The assertion is
 * agreement where both speak, plus the types, which are the ones a viewer tab actually shows.</p>
 */
public class JavaNodeIconsMatchTheStylesheetTest {

    private static final Pattern RULE = Pattern.compile(
            "\\.completion-kind-([a-z_]+)\\s*\\{\\s*background:\\s*icon\\(\"([^\"]+)\"\\)");

    /** {@code completion-kind-class} → {@code crystalgui:nodes/java/class}, read off the shipped sheet. */
    private static Map<String, String> stylesheetTable() throws IOException {
        String css = read("/assets/crystalgui/ui/styles/ua/editor.css");
        Map<String, String> table = new LinkedHashMap<>();
        Matcher matcher = RULE.matcher(css);
        while (matcher.find()) table.putIfAbsent(matcher.group(1), matcher.group(2));
        return table;
    }

    private static String read(String path) throws IOException {
        try (InputStream in = JavaNodeIconsMatchTheStylesheetTest.class.getResourceAsStream(path)) {
            assertNotNull("the stylesheet is missing from the classpath: " + path, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The precondition: with no rules parsed, every assertion below passes for the wrong reason. */
    @Test
    public void theStylesheetTableIsFound() throws IOException {
        Map<String, String> table = stylesheetTable();
        assertTrue("no completion-kind rules parsed -- the selector shape changed and this scan is "
                + "now looking at nothing: " + table.size(), table.size() > 10);
        assertEquals("crystalgui:nodes/java/interface", table.get("interface"));
    }

    /**
     * <b>Every kind the stylesheet draws, the Java map draws the same way.</b>
     *
     * <p>Both directions matter and this is the one that catches a rename: a kind whose CSS rule moved to
     * a different glyph while the tab kept the old one.</p>
     */
    @Test
    public void everyStyledKindAgrees() throws IOException {
        Map<String, String> table = stylesheetTable();
        StringBuilder offences = new StringBuilder();
        for (SymbolKind kind : SymbolKind.values()) {
            String styled = table.get(kind.name().toLowerCase(Locale.ROOT));
            if (styled == null) continue;
            String named = JavaNodeIcons.forKind(kind);
            if (!styled.equals(named)) {
                offences.append("\n  ").append(kind).append(": stylesheet ").append(styled)
                        .append(", JavaNodeIcons ").append(named);
            }
        }
        assertEquals("the two kind tables disagree, so a tab and a completion row draw different "
                + "glyphs for one kind:" + offences, 0, offences.length());
    }

    /**
     * <b>Every TYPE has a glyph</b>, which is the half a viewer tab depends on.
     *
     * <p>A member with no icon degrades to a blank slot in a list. A type with no icon is a library tab
     * with nothing on it, which is the report that started this.</p>
     */
    @Test
    public void everyTypeKindHasAnIcon() {
        for (SymbolKind kind : SymbolKind.values()) {
            if (!JavaNodeIcons.isType(kind)) continue;
            assertNotNull(kind + " is a type with no icon, so its viewer tab would have none",
                    JavaNodeIcons.forKind(kind));
        }
    }

    /**
     * <b>Abstract is a modifier, not a kind</b> — and the stylesheet spells that as a compound selector.
     *
     * <p>Pinned because the two axes are easy to collapse into one, and collapsing them is what produces
     * an {@code abstract} entry in a kind enum that then has to be kept out of every switch.</p>
     */
    @Test
    public void abstractRefinesAKindRatherThanBeingOne() {
        assertEquals("crystalgui:nodes/java/class", JavaNodeIcons.forKind(SymbolKind.CLASS));
        assertEquals("crystalgui:nodes/java/classAbstract",
                JavaNodeIcons.forKind(SymbolKind.CLASS, Set.of(SymbolModifier.ABSTRACT)));
        assertEquals("crystalgui:nodes/java/methodAbstract",
                JavaNodeIcons.forKind(SymbolKind.METHOD, Set.of(SymbolModifier.ABSTRACT)));
        // AND IT DOES NOT LEAK. An abstract interface is still an interface -- `abstract` is implied
        // there, so a compound rule for it would be a glyph nobody asked for.
        assertEquals("crystalgui:nodes/java/interface",
                JavaNodeIcons.forKind(SymbolKind.INTERFACE, Set.of(SymbolModifier.ABSTRACT)));
    }

    /** Every icon the map names must actually ship, or a tab resolves to nothing. */
    @Test
    public void everyNamedIconExists() {
        for (SymbolKind kind : SymbolKind.values()) {
            for (Set<SymbolModifier> modifiers :
                    java.util.List.of(Set.<SymbolModifier>of(), Set.of(SymbolModifier.ABSTRACT))) {
                String name = JavaNodeIcons.forKind(kind, modifiers);
                if (name == null) continue;
                String path = "/assets/crystalgui/ui/icons/"
                        + name.substring(name.indexOf(':') + 1) + ".svg";
                assertNotNull(kind + " names " + name + ", which ships nowhere",
                        JavaNodeIconsMatchTheStylesheetTest.class.getResource(path));
            }
        }
    }
}
