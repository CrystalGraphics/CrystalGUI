package com.crystalgui.headless;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * <b>The sheets, against the ledger and against the selector engine</b> — the single highest-value
 * guard in M6, because a dropped rule does not throw.
 *
 * <p>{@code plan_m6.md} §2.4. Of 1,048 part selectors, 401 select a part UNDER a part and 99 reach
 * THROUGH a part into a tag; {@code ::part()} can express neither, so the sheet work is a
 * classification (D1) rather than a rewrite. A misclassification produces a rule that silently stops
 * matching: no error, no warning, a widget that looks slightly wrong in one state, discovered by
 * eye if at all.</p>
 *
 * <h3>The three things it can check mechanically</h3>
 *
 * <ol>
 *   <li>No rule spells {@code ::part(a)::part(b)}, which is invalid CSS and the shape a mechanical
 *   rewrite of the 401 would produce.</li>
 *   <li>Every {@code __x__} a sheet names is in the ledger with a kind — so a new one cannot appear
 *   without somebody deciding what it is.</li>
 *   <li>Every TYPE a sheet names is either a registered kind or a widget that has not been ported
 *   yet. This is §1.5's finding: 32 of the 55 tags the sheets name are unregistered today and match
 *   by {@code tagName()}'s lowercased-class-name fallback, which the new engine does not have.</li>
 * </ol>
 */
public class SheetPortTest {

    private static final Pattern PART_IN_SELECTOR = Pattern.compile("__([a-z0-9-]+)__");
    private static final Pattern DOUBLE_PART = Pattern.compile("::part\\([^)]*\\)\\s*::part\\(");

    private static Path repoRoot() {
        Path here = ClassReferences.mainClassesRoot(SheetPortTest.class);
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("settings.gradle.kts"))) return p;
        }
        throw new IllegalStateException("cannot find the repository root from " + here);
    }

    private static Path sheets() {
        return repoRoot().resolve("core/src/main/resources/assets/crystalgui/ui/styles");
    }

    /** Every RULE's selector list, so a selector can be asked about beside its siblings. */
    private static List<List<String>> selectorLists() throws IOException {
        List<List<String>> out = new ArrayList<>();
        try (var walk = Files.walk(sheets())) {
            for (Path p : walk.toList()) {
                if (!p.getFileName().toString().endsWith(".css")) continue;
                String text = Files.readString(p, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "");
                Matcher m = Pattern.compile("([^{}]+)\\{").matcher(text);
                while (m.find()) {
                    List<String> rule = new ArrayList<>();
                    for (String alternative : m.group(1).split(",")) {
                        String sel = alternative.trim();
                        if (!sel.isEmpty() && !sel.startsWith("@")) rule.add(sel);
                    }
                    if (!rule.isEmpty()) out.add(rule);
                }
            }
        }
        return out;
    }

    /** Every selector in every shipped sheet, comments stripped, one per comma-separated alternative. */
    private static List<String> selectors() throws IOException {
        List<String> out = new ArrayList<>();
        try (var walk = Files.walk(sheets())) {
            for (Path p : walk.toList()) {
                if (!p.getFileName().toString().endsWith(".css")) continue;
                String text = Files.readString(p, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "");
                Matcher m = Pattern.compile("([^{}]+)\\{").matcher(text);
                while (m.find()) {
                    for (String alternative : m.group(1).split(",")) {
                        String sel = alternative.trim();
                        if (!sel.isEmpty() && !sel.startsWith("@")) out.add(sel);
                    }
                }
            }
        }
        return out;
    }

    /**
     * {@code ::part(a)::part(b)} is invalid CSS, and is what a mechanical rewrite of the 401
     * part-under-part selectors would produce.
     *
     * <p>The whole reason D1 exists: a part is a leaf of ONE widget, and a name with something
     * selected through it is light-tree structure that stays a class. A sheet that spells the chain
     * anyway has classified something wrong, and the failure is a rule matching nothing.</p>
     */
    @Test
    public void noSheetSpellsAPartInsideAPart() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String sel : selectors()) {
            if (DOUBLE_PART.matcher(sel).find()) offenders.add(sel);
        }
        assertTrue("::part(a)::part(b) is invalid CSS -- these names are light-tree structure (D1), "
                + "not shadow parts:\n" + String.join("\n", offenders), offenders.isEmpty());
    }

    /**
     * <b>No rule ends in a {@code .__part__} whose host exposes it as a shadow part.</b>
     *
     * <p>Such a rule is unreachable and silent: the class lives inside a shadow tree, so an outer
     * selector matches nothing, no error is raised, and the widget draws its structure with none of
     * its styling. Every widget in the M6.1 batch shipped at least one — a checkbox with an unstyled
     * mark, a slider with no fill, a scrollbar laid out at zero width, a colour picker's channel rows
     * with the default knob.</p>
     *
     * <p>The fix is always the same and is mechanical: {@code python tools/port/twins.py}, which
     * rewrites {@code <anything> HOST .__part__} to {@code <anything> HOST::part(part)} and is
     * idempotent. This asserts the tool has been run, which is the only thing that keeps 400 more
     * files from each discovering it by eye.</p>
     *
     * <p><b>It cannot see the rules that have no spelling at all</b> — a part under a part, or a tag
     * under a part — which is why {@code ColorSelector} keeps its structure in the LIGHT tree
     * (D1 kind B) rather than being twinned. That decision is per widget and belongs in the ledger,
     * not here.</p>
     */
    @Test
    public void noRuleTargetsAShadowPartByItsClass() throws IOException {
        // Host -> the part names it exposes. Mirrors tools/port/twins.py; a widget ported without
        // adding itself to both is a widget whose rules quietly stop matching.
        var hosts = new java.util.LinkedHashMap<String, Set<String>>();
        hosts.put("switch", Set.of("spacer", "knob"));
        hosts.put("slider", Set.of("fill", "thumb", "spacer"));
        hosts.put("progressbar", Set.of("fill"));
        hosts.put("scroller", Set.of("track", "thumb", "head", "tail"));
        hosts.put("scrollerview", Set.of("v-scroller", "h-scroller", "corner"));
        hosts.put("tooltip", Set.of("label"));
        hosts.put("menu", Set.of("items", "separator"));
        hosts.put("menuitem", Set.of("mark", "checkable", "accelerator", "submenu-arrow"));
        hosts.put("dropdown", Set.of("chevron", "menu"));
        hosts.put("searchfield", Set.of("icon", "field", "clear", "options"));
        hosts.put("checkbox", Set.of("mark", "label"));
        hosts.put("button", Set.of("label"));

        Pattern tail = Pattern.compile("^\\.__([a-z0-9-]+)__(?::[a-z-]+(?:\\([^)]*\\))?)*$");
        List<String> unreachable = new ArrayList<>();
        for (List<String> rule : selectorLists()) {
            for (String sel : rule) {
                String[] compounds = sel.trim().split("\\s+");
                if (compounds.length < 2) continue;
                Matcher m = tail.matcher(compounds[compounds.length - 1]);
                if (!m.matches()) continue;
                String part = m.group(1);
                String host = compounds[compounds.length - 2].split("[.:\\[]")[0];
                Set<String> exposed = hosts.get(host);
                if (exposed == null || !exposed.contains(part)) continue;
                // THE ORIGINAL STAYS -- it is what the OLD engine reads, and both ship until 6.9.
                // What must not be missing is its TWIN, beside it in the same rule.
                String twin = String.join(" ",
                        java.util.Arrays.copyOf(compounds, compounds.length - 1))
                        + "::part(" + part + ")";
                if (rule.stream().noneMatch(other -> other.trim().startsWith(twin))) {
                    unreachable.add(sel);
                }
            }
        }
        assertTrue("these target a SHADOW PART by its class, so they match nothing and say nothing"
                + " -- run `python tools/port/twins.py`:\n" + String.join("\n", unreachable),
                unreachable.isEmpty());
    }

    /**
     * Every {@code __x__} in a sheet is in the ledger with a kind decided.
     *
     * <p>So a name cannot be introduced without somebody saying whether it is a part, structure or a
     * state flag — which is the decision the whole sheet port turns on, and the one that is silent
     * when it is skipped.</p>
     */
    @Test
    public void everyPartNameInASheetIsInTheLedger() throws IOException {
        Set<String> ledgered = new LinkedHashSet<>();
        for (String line : Files.readAllLines(repoRoot().resolve("tools/port/port-ledger.tsv"),
                StandardCharsets.UTF_8)) {
            if (line.startsWith("PART\t")) ledgered.add(line.split("\t")[2]);
        }
        Set<String> unledgered = new LinkedHashSet<>();
        for (String sel : selectors()) {
            Matcher m = PART_IN_SELECTOR.matcher(sel);
            while (m.find()) {
                if (!ledgered.contains(m.group(1))) unledgered.add(m.group(1));
            }
        }
        assertTrue("a sheet names these and the ledger has not classified them -- run "
                + "`python tools/port/ledger.py`:\n" + String.join("\n", unledgered), unledgered.isEmpty());
    }

    /**
     * Every TYPE a sheet names is a registered kind, once the widget that owns it has been ported.
     *
     * <p>§1.5: the sheets name 55 tags and {@code ElementRegistry} registers 23. The other 32 —
     * {@code texteditor}, {@code graphnode}, {@code workbench}, {@code runpanel} and the rest — match
     * only through the old engine's lowercased-class-name fallback, which {@code UINodeRegistry} does
     * not have. A port that registers the 23 and not the 32 turns thirty-two widgets unstyled in one
     * commit, with no error anywhere.</p>
     *
     * <p>Vacuous until the first widget moves, which is deliberate: the tags the OLD engine matches
     * by fallback are not a defect today.</p>
     */
    @Test
    public void everyTypeASheetNamesIsRegisteredOncePorted() throws IOException {
        Set<String> ported = new LinkedHashSet<>();
        for (String line : Files.readAllLines(repoRoot().resolve("tools/port/port-ledger.tsv"),
                StandardCharsets.UTF_8)) {
            String[] f = line.split("\t");
            if (line.startsWith("CLASS\t") && f.length > 6 && "ported".equals(f[6])) {
                String path = f[1];
                String simple = path.substring(path.lastIndexOf('/') + 1);
                ported.add(simple.toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (ported.isEmpty()) return;

        // `names()` BOOTSTRAPS -- the registry runs every NodeKinds service on the first question
        // anybody asks it, so nothing here has to have touched a widget class. That was not true for
        // one commit, when a widget registered from its own static initialiser and this test had to
        // force-load the class it was asking about; @see com.crystalgui.ui.dom.NodeKinds.
        Set<String> registered = new LinkedHashSet<>();
        for (com.crystalgui.ui.dom.Name name : com.crystalgui.ui.dom.UINodeRegistry.names()) {
            registered.add(name.local());
        }
        List<String> missing = new ArrayList<>();
        for (String sel : selectors()) {
            for (String compound : sel.split("\\s*>\\s*|\\s+")) {
                Matcher m = Pattern.compile("^([a-z][a-z0-9-]*)").matcher(compound);
                if (!m.find()) continue;
                String tag = m.group(1);
                if (ported.contains(tag) && !registered.contains(tag)) missing.add(tag);
            }
        }
        assertTrue("a ported widget's sheet names a type nothing registered -- it will match nothing, "
                + "silently:\n" + String.join("\n", new LinkedHashSet<>(missing)), missing.isEmpty());
    }

}
