package com.crystalgui.headless;

import com.crystalgui.fs.CgPath;
import com.crystalgui.workbench.decoration.FileDecoration;
import com.crystalgui.workbench.decoration.FileDecorationProvider;
import com.crystalgui.workbench.decoration.FileDecorations;

import org.junit.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Merging and bubbling — VS Code's {@code DecorationsService}, ported.
 *
 * <p>Headless on purpose: this is the half of the feature that is pure model, and it needs no window, no
 * layout and no fonts. What it does need is {@code CgPath}, which is already headless.</p>
 */
public class FileDecorationsTest {

    private static final CgPath ROOT = CgPath.ofProject("p");
    private static final CgPath SRC = CgPath.of("p", "src");
    private static final CgPath MAIN = CgPath.of("p", "src/Main.java");
    private static final CgPath OTHER = CgPath.of("p", "README.md");

    /** A provider that decorates exactly the paths it is handed. */
    private static FileDecorationProvider provider(String name, FileDecoration decoration, CgPath... on) {
        List<CgPath> paths = List.of(on);
        return new FileDecorationProvider() {
            @Override public String label() {
                return name;
            }
            @Override public FileDecoration decorationFor(CgPath path) {
                return paths.contains(path) ? decoration : null;
            }
            @Override public Collection<CgPath> decorated() {
                return paths;
            }
        };
    }

    @Test
    public void noProvidersMeansNoDecoration() {
        assertNull(new FileDecorations().resolve(MAIN, false));
    }

    /**
     * <b>Two providers on one file merge per field, rather than the winner taking everything.</b>
     *
     * <p>A modified file that also has an error should be red <em>and</em> keep its {@code M}. VS Code's
     * own service takes the heaviest wholesale and drops the badge in exactly that case; per-field is
     * strictly more information for the same work, and the row was asked to show both facts.</p>
     */
    @Test
    public void twoProvidersMergePerField() {
        FileDecorations decorations = new FileDecorations()
                .addProvider(provider("git",
                        FileDecoration.of(FileDecoration.WEIGHT_MODIFIED, "decoration-modified", "M", "Modified"),
                        MAIN))
                .addProvider(provider("errors",
                        new FileDecoration(FileDecoration.WEIGHT_ERROR, "decoration-error", null, "1 error",
                                false, true),
                        MAIN));

        FileDecoration merged = decorations.resolve(MAIN, false);
        assertEquals("the heavier provider supplies the colour", "decoration-error", merged.styleClass());
        assertEquals("and the lighter one still supplies the badge it alone stated", "M", merged.letter());
    }

    /**
     * <b>A folder inherits the heaviest bubbling decoration beneath it — that is what a collapsed tree
     * shows.</b>
     *
     * <p>Without it, every signal the feature has is hidden until you have already found what you were
     * looking for.</p>
     */
    @Test
    public void aFolderInheritsFromItsDescendants() {
        FileDecorations decorations = new FileDecorations().addProvider(provider("git",
                FileDecoration.of(FileDecoration.WEIGHT_MODIFIED, "decoration-modified", "M", "Modified"),
                MAIN));

        assertEquals("decoration-modified", decorations.resolve(SRC, true).styleClass());
        assertEquals("and the project root, two levels up",
                "decoration-modified", decorations.resolve(ROOT, true).styleClass());
        assertNull("a sibling is not an ancestor", decorations.resolve(OTHER, false));
    }

    /**
     * <b>A bubbled decoration keeps the colour and drops the badge.</b>
     *
     * <p>A folder showing {@code M} claims the folder itself is modified, which is not what happened.
     * VS Code colours the folder name and badges only the file, and that distinction is the entire
     * information content of the bubble.</p>
     */
    @Test
    public void aBubbledDecorationLosesItsBadge() {
        FileDecorations decorations = new FileDecorations().addProvider(provider("git",
                FileDecoration.of(FileDecoration.WEIGHT_MODIFIED, "decoration-modified", "M", "Modified"),
                MAIN));

        assertEquals("M", decorations.resolve(MAIN, false).letter());
        assertNull("the folder took the file's badge", decorations.resolve(SRC, true).letter());
    }

    /**
     * <b>{@code bubble = false} stays on its own file.</b>
     *
     * <p>Per-decoration rather than global because not every fact should climb: "modified" should reach
     * the folder, "this is read-only" should not.</p>
     */
    @Test
    public void aNonBubblingDecorationDoesNotClimb() {
        FileDecorations decorations = new FileDecorations().addProvider(provider("readonly",
                FileDecoration.of(FileDecoration.WEIGHT_INFO, "decoration-readonly", null, "Read-only")
                        .withBubble(false),
                MAIN));

        assertEquals("decoration-readonly", decorations.resolve(MAIN, false).styleClass());
        assertNull("a non-bubbling decoration reached the folder", decorations.resolve(SRC, true));
    }

    /**
     * <b>A folder does not bubble its own decoration into itself.</b>
     *
     * <p>{@code CgPath.contains} is a strict ancestor test, and if it were not, a decorated folder would
     * be counted twice — once directly and once as its own descendant — which merges fine and would go
     * unnoticed right up until a weight comparison depended on it.</p>
     */
    @Test
    public void aFolderDoesNotBubbleIntoItself() {
        FileDecorations decorations = new FileDecorations().addProvider(provider("git",
                FileDecoration.of(FileDecoration.WEIGHT_MODIFIED, "decoration-modified", "M", "Modified"),
                SRC));

        // Decorated directly, so the badge survives -- which is exactly what a self-bubble would remove.
        assertEquals("M", decorations.resolve(SRC, true).letter());
    }

    /** Registering or dropping a provider is a change the view has to hear about. */
    @Test
    public void providerChangesFire() {
        boolean[] fired = {false};
        FileDecorations decorations = new FileDecorations();
        decorations.onChanged.connect(() -> fired[0] = true);

        FileDecorationProvider provider = provider("git", FileDecoration.of(0, "decoration-modified", null, null));
        decorations.addProvider(provider);
        assertTrue("adding a provider did not notify", fired[0]);

        fired[0] = false;
        decorations.removeProvider(provider);
        assertTrue("removing a provider did not notify", fired[0]);

        fired[0] = false;
        decorations.removeProvider(provider);
        assertTrue("removing an absent provider must not notify", !fired[0]);
    }
}
