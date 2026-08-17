package com.crystalgui.language.java;

import com.crystalgui.language.java.fix.catalog.ImportCorrections;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * "Organize imports" — the first intention: offered by where the caret is, not by what is wrong.
 *
 * <p>The refusals are as much the contract as the tidy: not offered outside the import region, not offered
 * when there is nothing to change, and not offered over a comment that the rebuild would delete.</p>
 */
public class OrganizeImportsTest extends FixFixture {

    private static final String MESSY = ""
            + "package demo;\n"
            + "\n"
            + "import java.util.Map;\n"
            + "import static java.lang.Math.max;\n"
            + "import java.util.List;\n"
            + "import org.w3c.dom.Node;\n"
            + "import java.util.Set;\n"
            + "import javax.swing.JFrame;\n"
            + "\n"
            + "public class Script {\n"
            + "    Map<String, Set<Node>> things; JFrame frame; int m = max(1, 2);\n"
            + "}\n";

    @Test
    public void unusedDroppedRestSortedAndGroupedTheIntelliJWay() {
        CodeAction tidy = offered(MESSY, "import java.util.Map", ImportCorrections.ORGANIZE);
        assertNotNull("offered with the caret on an import", tidy);
        assertEquals(CodeActionKind.SOURCE, tidy.kind());
        assertFalse("a whole-region tidy is chosen, never defaulted to", tidy.preferred());
        assertEquals(""
                + "package demo;\n"
                + "\n"
                + "import org.w3c.dom.Node;\n"
                + "\n"
                + "import javax.swing.JFrame;\n"
                + "import java.util.Map;\n"
                + "import java.util.Set;\n"
                + "\n"
                + "import static java.lang.Math.max;\n"
                + "\n"
                + "public class Script {\n"
                + "    Map<String, Set<Node>> things; JFrame frame; int m = max(1, 2);\n"
                + "}\n", applied(MESSY, tidy));
    }

    /** An intention decides from the caret; the same file offers nothing with the caret in the body. */
    @Test
    public void notOfferedOutsideTheImportRegion() {
        assertNull(offered(MESSY, "things;", ImportCorrections.ORGANIZE));
    }

    @Test
    public void notOfferedWhenAlreadyOrganized() {
        String tidy = ""
                + "import java.util.List;\n"
                + "import java.util.Map;\n"
                + "public class Script { List<Map<String, String>> x; }\n";
        assertNull("nothing to change, nothing to offer",
                offered(tidy, "import java.util.List", ImportCorrections.ORGANIZE));
    }

    /**
     * <b>Refused over a comment.</b> The region is rebuilt as text, so a note between two imports would
     * be deleted — and a tidy that removes somebody's note is not a tidy.
     */
    @Test
    public void refusedWhenACommentSitsBetweenImports() {
        String commented = ""
                + "import java.util.Map;\n"
                + "// keep this one, it is load-bearing\n"
                + "import java.util.List;\n"
                + "public class Script { List<Map<String, String>> x; }\n";
        assertNull(offered(commented, "import java.util.Map", ImportCorrections.ORGANIZE));
    }

    /** Emptying the region entirely also takes the blank line that separated it from the class. */
    @Test
    public void emptyingTheRegionLeavesNoDoubleBlankLine() {
        String allUnused = ""
                + "package demo;\n"
                + "\n"
                + "import java.util.Map;\n"
                + "\n"
                + "public class Script { }\n";
        assertFix(allUnused, "import java.util.Map", ImportCorrections.ORGANIZE, ""
                + "package demo;\n"
                + "\n"
                + "public class Script { }\n");
    }
}
