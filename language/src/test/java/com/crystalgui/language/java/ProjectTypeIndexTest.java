package com.crystalgui.language.java;

import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.language.java.classpath.TypeIndex;
import com.crystalgui.text.lang.ProjectSources;
import com.crystalgui.text.lang.ProjectSourcesRegistry;

import org.junit.After;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The workspace's own types in the "which types exist" query — M15 S4.
 *
 * <h3>One index, two features, and both were missing the same half</h3>
 *
 * <p>{@code TypeIndex.matching} is what a completion popup shows for an unimported name <em>and</em> what
 * the Alt+Enter "import this" fix searches. It is built from the CLASSPATH, so a type declared in the file
 * next door was in neither: typing {@code Formatter} in a package that declares one offered
 * {@code java.util.Formatter}, {@code java.util.logging.Formatter} and a JavaFX accessor, and deleting the
 * import of a sibling offered no way to put it back.</p>
 *
 * <p>Both are one query, so both are one fix — and the ranking matters as much as the presence. IntelliJ
 * weighs match quality first and proximity second, so a project class beats a library class of equal match
 * quality and never beats a better one.</p>
 */
public class ProjectTypeIndexTest {

    private static final String FORMATTER = "com.example.util.Formatter";

    /** A workspace that declares names and nothing else — the half of the index that costs no I/O. */
    private static final class Declaring implements ProjectSources {
        private final List<String> names;

        Declaring(String... names) {
            this.names = List.of(names);
        }

        @Override
        public String sourceOf(String qualifiedName) {
            return null;
        }

        @Override
        public boolean declaresPackage(String packageName) {
            return false;
        }

        @Override
        public List<String> declaredTypes() {
            return names;
        }
    }

    private static TypeIndex index() {
        return JavaLanguageServices.typeIndexFor(HostClasspath.detect());
    }

    private static List<String> namesIn(TypeIndex.Match match) {
        return match.entries().stream().map(TypeIndex.Entry::qualifiedName).toList();
    }

    @After
    public void clearRegistry() {
        ProjectSourcesRegistry.resetForTesting();
    }

    /**
     * <b>A project type is offered at all.</b>
     *
     * <p>The presence half. Asserted on a name the JDK also has, because that is the reported case and the
     * harder one: a project {@code Formatter} has to appear <em>beside</em> {@code java.util.Formatter}
     * rather than instead of it.</p>
     */
    @Test
    public void aProjectTypeIsOfferedAlongsideTheClasspath() {
        ProjectSourcesRegistry.contribute(new Declaring(FORMATTER));

        List<String> names = namesIn(index().matching("Formatter"));
        assertTrue("the workspace's own type was not offered: " + names, names.contains(FORMATTER));
        assertTrue("the classpath's went missing, so this replaced rather than added: " + names,
                names.contains("java.util.Formatter"));
    }

    /**
     * <b>...and it is offered FIRST.</b>
     *
     * <p>The ranking half, and the one a user actually notices. Both are exact prefix matches, so nothing
     * about match quality separates them and only proximity can — which is IntelliJ's order. A row that
     * exists but sits below three JDK classes is, at a glance, a row that is missing.</p>
     */
    @Test
    public void aProjectTypeOutranksAClasspathTypeOfEqualQuality() {
        ProjectSourcesRegistry.contribute(new Declaring(FORMATTER));

        List<String> names = namesIn(index().matching("Formatter"));
        // PRESENCE FIRST. `indexOf` answers -1 for a name that is not there, and -1 sorts before every
        // real position -- so an ordering assertion alone passes most convincingly when the row is
        // MISSING, which is the exact bug it was written for.
        int project = names.indexOf(FORMATTER);
        int library = names.indexOf("java.util.Formatter");
        assertTrue("the workspace's own type was not offered at all: " + names, project >= 0);
        assertTrue("the classpath's type was not offered at all: " + names, library >= 0);
        assertTrue("the workspace's own type ranked below the classpath's: " + names,
                project < library);
    }

    /**
     * <b>Match quality still wins over proximity.</b>
     *
     * <p>The control, and the reason "project first" is not simply "project on top". A project type that
     * matches only as a scattered subsequence must not displace a classpath type the query actually
     * prefixes — that would make the list reorder itself around whichever file happened to be in the
     * workspace, which is the failure mode of ranking by owner instead of by tier.</p>
     */
    @Test
    public void aScatteredProjectMatchDoesNotBeatAPrefixedClasspathMatch() {
        // Contains "Formatter" and does not START with it, so it lands in the scattered tier while
        // java.util.Formatter lands in the prefixed one.
        ProjectSourcesRegistry.contribute(new Declaring("com.example.util.MyFormatterHelper"));

        List<String> names = namesIn(index().matching("Formatter"));
        int project = names.indexOf("com.example.util.MyFormatterHelper");
        int exact = names.indexOf("java.util.Formatter");
        assertTrue("the scattered workspace match was not offered, so this asserts nothing: " + names,
                project >= 0);
        assertTrue("the prefix hit was not offered: " + names, exact >= 0);
        assertTrue("a subsequence match from the workspace displaced a real prefix hit: " + names,
                exact < project);
    }

    /**
     * <b>No workspace, no change.</b>
     *
     * <p>A host with nothing registered — a plain script runtime, a dedicated server — must see exactly
     * the index it saw before any of this existed.</p>
     */
    @Test
    public void anEmptyRegistryLeavesTheClasspathIndexAlone() {
        List<String> names = namesIn(index().matching("Formatter"));
        assertTrue("the classpath index stopped answering: " + names,
                names.contains("java.util.Formatter"));
        for (String name : names) {
            assertFalse("a project name appeared with no provider registered: " + name,
                    name.startsWith("com.example."));
        }
    }

    // \u2500\u2500 The other three queries \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    /**
     * <b>An {@code import} line offers a package the WORKSPACE declares.</b>
     *
     * <p>{@code matching} was taught about the workspace when the index was; the three queries beside it
     * were not, and each is what some editor affordance reads. This one backs Java's import line: typing
     * {@code import com.} listed {@code sun} \u2014 from the JDK \u2014 and not {@code example}, in a project whose
     * own package is {@code com.example}.</p>
     *
     * <p>Asserted on {@code com}, which the classpath also populates, because a package the workspace
     * SHARES with a jar is the case that a naive union gets wrong twice over: dropped, or listed twice.</p>
     */
    @Test
    public void anImportLineOffersAProjectPackage() {
        ProjectSourcesRegistry.contribute(new Declaring(FORMATTER));

        TypeIndex.Children children = index().childrenOf("com", "");

        assertTrue("the workspace's own package was not offered: " + children.packages(),
                children.packages().contains("example"));
        assertEquals("one package, however many roots contribute to it",
                1, children.packages().stream().filter("example"::equals).count());
    }

    /** <b>...and the type itself, once the package is complete.</b> */
    @Test
    public void anImportLineOffersAProjectTypeInItsOwnPackage() {
        ProjectSourcesRegistry.contribute(new Declaring(FORMATTER));

        TypeIndex.Children children = index().childrenOf("com.example.util", "");

        List<String> names = children.types().stream().map(TypeIndex.Entry::simpleName).toList();
        assertTrue("the workspace's own type was not offered: " + names, names.contains("Formatter"));
    }

    /**
     * <b>{@code allUnder} sees it too \u2014 which is what JavaScript's import line reads.</b>
     *
     * <p>The two languages ask different questions of the same index: Java asks {@code childrenOf} and
     * takes the packages it returns, JavaScript asks {@code allUnder} and derives the next segment itself.
     * Fixing one would have left the other exactly as broken, in the language the report came from.</p>
     */
    @Test
    public void everythingUnderAPrefixIncludesTheWorkspace() {
        ProjectSourcesRegistry.contribute(new Declaring(FORMATTER));

        List<String> names = namesIn(index().allUnder("com."));

        assertTrue("the workspace's own type was not under its own prefix: " + names.size(),
                names.contains(FORMATTER));
    }

    /**
     * <b>And "did you mean" suggests the author's own type before a jar's.</b>
     *
     * <p>The quietest of the three: an unresolvable name in the file next door suggested
     * {@code java.util.Formatter} and never the {@code Formatter} the workspace declares.</p>
     */
    @Test
    public void didYouMeanSuggestsAProjectTypeFirst() {
        ProjectSourcesRegistry.contribute(new Declaring(FORMATTER));

        List<String> similar = index().similar("Formater");

        assertTrue("the workspace's own type was not suggested: " + similar, similar.contains(FORMATTER));
        assertEquals("a jar's type outranked the author's own", FORMATTER, similar.get(0));
    }
}
