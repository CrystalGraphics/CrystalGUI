package com.crystalgui.language.map;

import com.crystalgui.text.Change;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A whole source file taken out of the runtime namespace.
 *
 * <h3>What is worth pinning here</h3>
 *
 * <p>The rename itself is a map lookup. What can go wrong is <b>where</b> it is applied: renaming inside
 * a string literal turns a working reflective call into a broken one, and failing to recognise a comment
 * lets one quote character silently suppress every rename below it. Both are invisible — the file still
 * compiles, the command still reports a number, and the damage is somewhere nobody is looking.</p>
 *
 * <p>Every assertion is on the <b>text the edits produce</b> rather than on their offsets, which is the
 * standing rule for anything that rewrites a document: a test that checks {@code from}/{@code to} passes
 * against a rewrite that lands a character to the left.</p>
 */
public class ReadableSourceTest {

    /** The rows {@code mcp_stable/12} actually carries for the names in the obfuscated workspace. */
    private static MappingSet mcp() {
        return MappingSet.builder()
                .method("func_71276_C", "getServer")
                .method("func_71203_ab", "getConfigurationManager")
                .method("func_71033_a", "setGameType")
                .field("field_72404_b", "playerEntityList")
                .field("field_71075_bZ", "capabilities")
                .field("field_75098_d", "isCreativeMode")
                .build();
    }

    /**
     * <b>The reported file.</b> Every SRG name goes; nothing else moves.
     *
     * <p>{@code .get(0)} is the counter-assertion and it is not incidental. {@code get} <em>is</em> a
     * mapped name — {@code func_76163_a} in the real data — but on the <em>value</em> side, which is the
     * whole reason a blind pass is safe in this direction and would be catastrophic in the other. A
     * rewrite that consulted the reverse table would rename it to an SRG name and break the call.</p>
     */
    @Test
    public void theObfuscatedWorkspaceScriptBecomesReadable() {
        String source = String.join("\n",
                "import net.minecraft.server.MinecraftServer;",
                "final EntityPlayer plr = (EntityPlayer) MinecraftServer.func_71276_C()"
                        + ".func_71203_ab().field_72404_b.get(0);",
                "boolean currMode = plr.field_71075_bZ.field_75098_d;",
                "plr.func_71033_a(currMode ? GameType.SURVIVAL : GameType.CREATIVE);");

        assertEquals(String.join("\n",
                "import net.minecraft.server.MinecraftServer;",
                "final EntityPlayer plr = (EntityPlayer) MinecraftServer.getServer()"
                        + ".getConfigurationManager().playerEntityList.get(0);",
                "boolean currMode = plr.capabilities.isCreativeMode;",
                "plr.setGameType(currMode ? GameType.SURVIVAL : GameType.CREATIVE);"),
                ReadableSource.readable(mcp(), source));
    }

    /**
     * <b>A string literal is data and is left alone.</b>
     *
     * <p>{@code getDeclaredMethod("func_71203_ab")} names a member the runtime really does call that —
     * the bytecode remapper rewrites references, never constants — so renaming inside the quotes takes a
     * call that works and breaks it. The second line is the control: the identical text outside quotes
     * must still be renamed, or a fix written as "never rename anything" would pass this.</p>
     */
    @Test
    public void aNameInsideAStringLiteralIsNotTouched() {
        String source = "String m = \"func_71203_ab\";\nserver.func_71203_ab();";

        assertEquals("String m = \"func_71203_ab\";\nserver.getConfigurationManager();",
                ReadableSource.readable(mcp(), source));
    }

    /** Char literals too — {@code '\u005c''} included, so the escape does not end the literal early. */
    @Test
    public void aCharLiteralIsSkippedIncludingAnEscapedQuote() {
        String source = "char q = '\\'';\nx.func_71276_C();";

        assertEquals("char q = '\\'';\nx.getServer();", ReadableSource.readable(mcp(), source));
    }

    /**
     * <b>A comment is renamed, and its quotes are not literals.</b>
     *
     * <p>This is the pair that only a comment-aware scan gets right. The quotes here <em>do</em> close on
     * their line, so the line rule cannot save it: without recognising the comment, the name between them
     * is read as a string constant and silently kept. A comment quoting a call is exactly the text this
     * command exists to make readable.</p>
     */
    @Test
    public void aQuotedNameInsideACommentIsStillRenamed() {
        String source = "// see \"func_71276_C\" for the instance\nx.func_71203_ab();";

        assertEquals("// see \"getServer\" for the instance\nx.getConfigurationManager();",
                ReadableSource.readable(mcp(), source));
    }

    /**
     * <b>A quote that does not close on its line was never opening a literal.</b>
     *
     * <p>The fixture is a JavaScript character class, and the third line is what makes it bite: without
     * the rule the apostrophe in {@code /['"]/} pairs with the one four lines down, and every name in
     * between is read as sitting inside a string. Nothing throws, the file below is simply left alone,
     * and the command truthfully reports however few names it managed.</p>
     *
     * <p>An <em>unterminated</em> quote could not show this — it finds no partner either way — which is
     * why the first version of this test passed against a scan with the rule taken out.</p>
     */
    @Test
    public void aQuoteThatDoesNotCloseOnItsLineDoesNotSwallowWhatFollows() {
        String source = String.join("\n",
                "const quotes = /['\"]/;",
                "x.func_71276_C();",
                "const label = 'player';");

        assertEquals(String.join("\n",
                "const quotes = /['\"]/;",
                "x.getServer();",
                "const label = 'player';"),
                ReadableSource.readable(mcp(), source));
    }

    /** And the everyday shape of the same rule: an apostrophe in prose, inside a comment. */
    @Test
    public void anApostropheInACommentCostsNothingBelowIt() {
        String source = "// don't touch this\nx.func_71276_C();";

        assertEquals("// don't touch this\nx.getServer();", ReadableSource.readable(mcp(), source));
    }

    /** A block comment, across lines, with the code after it still reached. */
    @Test
    public void aBlockCommentIsRenamedAndDoesNotRunAway() {
        String source = "/* calls func_71276_C\n   then func_71203_ab */\nx.func_71033_a(0);";

        assertEquals("/* calls getServer\n   then getConfigurationManager */\nx.setGameType(0);",
                ReadableSource.readable(mcp(), source));
    }

    /**
     * <b>A template literal is text; its {@code ${…}} hole is code.</b>
     *
     * <p>Both halves in one fixture, because getting either alone is the bug: renaming the text half
     * changes what a script prints, and skipping the hole leaves a real call in the runtime namespace in
     * the middle of a file the command claims to have remapped.</p>
     */
    @Test
    public void aTemplateLiteralKeepsItsTextAndRemapsItsHoles() {
        String source = "console.log(`field_75098_d is ${plr.field_75098_d}`);";

        assertEquals("console.log(`field_75098_d is ${plr.isCreativeMode}`);",
                ReadableSource.readable(mcp(), source));
    }

    /**
     * <b>Only the unqualified tier is consulted.</b>
     *
     * <p>An owner-keyed entry exists for formats that carry owners, and in one of those {@code a} is a
     * method on hundreds of classes with a different readable name on each. A source file offers no
     * receiver type, so reading that table here would be a coin toss dressed as a lookup — and the
     * answer would be plausible, which is what makes it worth a test rather than a comment.</p>
     */
    @Test
    public void anOwnerKeyedEntryIsNotAppliedToText() {
        MappingSet owned = MappingSet.builder()
                .method("net/minecraft/world/World", "a", "getBlock")
                .build();

        assertEquals("world.a(x, y, z);", ReadableSource.readable(owned, "world.a(x, y, z);"));
        assertFalse(ReadableSource.containsRuntimeNames(owned, "world.a(x, y, z);"));
    }

    /**
     * An identity mapping is the common case — a dev environment, the harness, every test — and it
     * answers without scanning. {@code assertSame} is the assertion that says "no work happened".
     */
    @Test
    public void anIdentityMappingRewritesNothingAtAll() {
        String source = "server.func_71276_C();";

        assertSame(source, ReadableSource.readable(MappingSet.IDENTITY, source));
        assertTrue(ReadableSource.rewrites(MappingSet.IDENTITY, source).isEmpty());
        assertFalse(ReadableSource.containsRuntimeNames(MappingSet.IDENTITY, source));
    }

    /**
     * <b>The cheap question and the expensive one agree.</b>
     *
     * <p>{@link ReadableSource#containsRuntimeNames} stops at the first hit and drives the menu row's
     * enablement, while {@link ReadableSource#rewrites} does the work. A short-circuit that answered
     * differently would offer a command that then reports nothing to do, or — worse — hide one that
     * would have worked.</p>
     */
    @Test
    public void theEnablementQuestionAgreesWithTheRewrite() {
        MappingSet mcp = mcp();
        String has = "x.func_71276_C();";
        String hasNot = "x.getServer();";

        assertTrue(ReadableSource.containsRuntimeNames(mcp, has));
        assertFalse(ReadableSource.rewrites(mcp, has).isEmpty());
        assertFalse(ReadableSource.containsRuntimeNames(mcp, hasNot));
        assertTrue(ReadableSource.rewrites(mcp, hasNot).isEmpty());
    }

    /** The edits are offsets into the text that was scanned, ascending and non-overlapping. */
    @Test
    public void theEditsAreOverTheTextThatWasScanned() {
        String source = "a.func_71276_C().func_71203_ab();";
        List<Change> edits = ReadableSource.rewrites(mcp(), source);

        assertEquals(2, edits.size());
        int previous = -1;
        for (Change edit : edits) {
            assertTrue("edits must ascend and not overlap", edit.from() >= previous);
            assertEquals("the edit does not cover the name it replaces",
                    edit.insert().equals("getServer") ? "func_71276_C" : "func_71203_ab",
                    source.substring(edit.from(), edit.to()));
            previous = edit.to();
        }
    }
}
