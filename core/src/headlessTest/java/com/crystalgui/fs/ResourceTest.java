package com.crystalgui.fs;

import com.crystalgui.core.async.Reply;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.CgPath;
import com.crystalgui.serialization.PlainOps;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link Resource} — a tab's input, whether or not it is a file.
 *
 * <p>The load-bearing assertion is the first one. {@code CgPath} states that its text form is written
 * into saved documents and <b>must round trip exactly and forever</b>; wrapping it in a scheme system is
 * only safe if the project scheme's spelling does not move by a single byte.</p>
 */
public class ResourceTest {

    // ── The compatibility guarantee ─────────────────────────────────────────────────────────────

    /**
     * <b>A project resource is spelled exactly as the path was.</b>
     *
     * <p>Every saved {@code .shadergraph}, every session record, every dock panel's {@code path} state is
     * this string. If it changes, they all stop resolving — silently, because a path that fails to parse
     * looks the same as a file that was deleted.</p>
     */
    @Test
    public void aProjectResourceSpellsItselfExactlyAsItsPath() {
        CgPath path = CgPath.parse("mymod.proj:src/Main.java");
        assertEquals(path.toString(), Resource.of(path).toString());
        assertEquals("mymod.proj:src/Main.java", Resource.of(path).toString());
    }

    @Test
    public void parsingAPlainPathGivesTheProjectScheme() {
        Resource resource = Resource.parse("mymod.proj:src/Main.java");
        assertTrue(resource.isProject());
        assertEquals(Resource.SCHEME_PROJECT, resource.scheme());
        assertNotNull(resource.asPath());
        assertEquals(CgPath.parse("mymod.proj:src/Main.java"), resource.asPath());
    }

    /** The scheme marker is {@code ://}, so a colon inside a path segment is not one. */
    @Test
    public void aColonInsideAPathIsNotASchemeMarker() {
        Resource resource = Resource.parse("mymod.proj:src/odd:name.txt");
        assertTrue("a path with a colon in it stopped being a project path", resource.isProject());
        assertEquals("odd:name.txt", resource.name());
    }

    // ── Other schemes ───────────────────────────────────────────────────────────────────────────

    @Test
    public void anotherSchemeRoundTrips() {
        Resource resource = Resource.of("untitled", "buffer-1");
        assertEquals("untitled://buffer-1", resource.toString());
        assertEquals(resource, Resource.parse(resource.toString()));
        assertFalse(resource.isProject());
        assertNull(resource.asPath());
    }

    /**
     * <b>A derived resource carries its origin, through text.</b>
     *
     * <p>This is what replaces an application-side map from generated document back to the thing it was
     * generated from — and because it survives {@code parse}, it survives a saved session too, with
     * nothing to keep in step.</p>
     */
    @Test
    public void aDerivedResourceRemembersWhatItCameFrom() {
        Resource graph = Resource.of(CgPath.parse("mymod.proj:fire.shadergraph"));
        Resource generated = Resource.derived("shader-generated", graph);

        assertEquals("shader-generated://mymod.proj:fire.shadergraph", generated.toString());
        assertEquals(graph, generated.origin());

        Resource reparsed = Resource.parse(generated.toString());
        assertEquals(generated, reparsed);
        assertEquals("the origin did not survive a round trip", graph, reparsed.origin());
    }

    /** Five graphs give five distinct generated resources — the whole reason this is keyed by origin. */
    @Test
    public void twoOriginsGiveTwoDistinctDerivedResources() {
        Resource first = Resource.derived("shader-generated",
                Resource.of(CgPath.parse("mymod.proj:fire.shadergraph")));
        Resource second = Resource.derived("shader-generated",
                Resource.of(CgPath.parse("mymod.proj:ice.shadergraph")));
        assertFalse(first.equals(second));
    }

    /** A resource in a scheme of its own is not "derived from" anything. */
    @Test
    public void anOrdinarySchemedResourceHasNoOrigin() {
        assertNull(Resource.parse("output://build.log").origin());
    }

    /** Named after the origin, because that is what the document is about. */
    @Test
    public void aDerivedResourceIsNamedAfterItsOrigin() {
        Resource generated = Resource.derived("shader-generated",
                Resource.of(CgPath.parse("mymod.proj:fire.shadergraph")));
        assertEquals("fire.shadergraph", generated.name());
        assertEquals("shadergraph", generated.extension());
    }

    @Test
    public void valueEqualityMakesItUsableAsAKey() {
        Resource one = Resource.parse("mymod.proj:a.txt");
        Resource two = Resource.of(CgPath.parse("mymod.proj:a.txt"));
        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
    }

    // ── Which side answers for a scheme ─────────────────────────────────────────────────────────

    /** A workspace with no wire behind it. Every test here asks about routing, never about content. */
    private static Workspace offline() {
        return Workspace.over((method, args, onResult, onError) -> { }, (method, handler) -> { },
                PlainOps.INSTANCE);
    }

    @Test
    public void anUnregisteredSchemeHasNoProviderAndIsReadOnly() {
        Workspace workspace = offline();
        Resource resource = Resource.of("nobody", "x");
        assertNull(workspace.providerFor(resource));
        assertTrue("refusing to write something nobody claims is the safe direction",
                workspace.isReadOnly(resource));
    }

    @Test
    public void aProjectResourceIsWritable() {
        assertFalse(offline().isReadOnly(Resource.parse("mymod.proj:a.txt")));
    }

    @Test
    public void aRegisteredProviderAnswersForItsScheme() {
        Workspace workspace = offline();
        workspace.registerScheme("greeting", resource -> Reply.of("hello".getBytes()));

        Resource resource = Resource.of("greeting", "x");
        byte[][] got = {null};
        workspace.read(resource).then(bytes -> got[0] = bytes);
        assertEquals("hello", new String(got[0]));
        assertTrue("providers are read-only unless they say otherwise", workspace.isReadOnly(resource));
    }

    @Test
    public void withdrawingASchemeTakesItsProviderWithIt() {
        Workspace workspace = offline();
        Disposable registration =
                workspace.registerScheme("greeting", resource -> Reply.of("hello".getBytes()));
        registration.dispose();
        assertNull(workspace.providerFor(Resource.of("greeting", "x")));
    }

    /**
     * A provider may claim the project scheme, and a project file is still never READ through one.
     *
     * <p>A provider answers two questions and only one is about bytes: {@code symbolOf} says what a
     * resource IS, which is how a tab draws its glyph, and the author's own files need that answered as
     * much as a library's. So the guard is on the read rather than on the registration.</p>
     */
    @Test
    public void theProjectSchemeIsNeverReadThroughAProvider() {
        Workspace workspace = offline();
        workspace.registerScheme(Resource.SCHEME_PROJECT,
                resource -> Reply.of("leaked".getBytes()));

        Resource mine = Resource.parse("mymod.proj:src/Main.java");
        byte[][] got = {null};
        // NO WIRE BEHIND THIS WORKSPACE, so the read never settles -- which is the assertion: it went to
        // the wire rather than to the provider sitting right there with an answer.
        workspace.read(mine).then(bytes -> got[0] = bytes);
        assertNull("a project file's bytes come from the server, never a provider", got[0]);
        // AND THE OTHER HALF still resolves, which is the whole reason the guard is where it is.
        assertNotNull("what a project resource IS is still a question somebody can answer",
                workspace.providerFor(mine));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aSchemeMayNotContainASeparator() {
        Resource.of("bad/scheme", "x");
    }
}
