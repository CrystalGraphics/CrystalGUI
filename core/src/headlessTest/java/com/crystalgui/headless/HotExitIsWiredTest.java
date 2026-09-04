package com.crystalgui.headless;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.assertTrue;

/**
 * <b>Hot exit reaches the two places only a HOST can call</b> — asserted from the constant pool.
 *
 * <p>Unsaved work is backed up on every edit and given back on the next launch, and the giving-back
 * runs through three links that each read as complete from the others. The middle one —
 * {@code EditorService.restoreUnsavedWork} actually restoring the content — is driven directly by
 * {@code EditorServiceTest}. The two at the ends are single calls inside {@code WorkbenchApplication}
 * — they were {@code CrystalEditor}'s until W7 made that a manifest and the runtime a shared one —
 * and both were missing:</p>
 *
 * <ul>
 *   <li>the launch wired the preferences and the session record and <b>not</b>
 *       {@code Workspace.setStorage}, so {@code backup()} answered null on every host and nothing was
 *       ever written — taking {@code LocalHistory}, and with it the conflict dialog's merge base.</li>
 *   <li>the session restore never called {@code restoreUnsavedWork}, so nothing offered it back.</li>
 * </ul>
 *
 * <h3>Why a scan and not a fixture</h3>
 *
 * <p>A test that <em>builds</em> an application was written first and deleted, and both halves of why
 * are still true. It exhausted the heap — which W0's {@code ApplicationRetentionTest} now measures on
 * purpose — and it shifted the computed colours {@code ConfigKitTest} asserts, because installing a
 * theme substitutes variables into the shipped {@code StyleSheet.DEFAULT} in place. That second one bit
 * again the day an application started loading preferences unconditionally, and the fixture that does
 * build one has to reset the theme manager afterwards. Constructing an application to check that one
 * line survives is the wrong instrument; a reference in the constant pool is the real question, and it
 * is the same one {@code ExecutionNeedsNoGrammarTest} and {@code ModeStackTest} ask.</p>
 *
 * <p>It cannot see whether the calls are <em>reachable</em> — only that they are there. That is worth
 * saying out loud, and it is still the whole of what broke: neither line existed at all.</p>
 */
public class HotExitIsWiredTest {

    private static final String EDITOR = "com/crystalgui/workbench/WorkbenchApplication.class";

    private Set<String> calls() throws IOException {
        Path editor = ClassReferences.mainClassesRoot(getClass()).resolve(EDITOR);
        assertTrue("the application runtime was not compiled: " + editor, editor.toFile().isFile());
        return ClassReferences.memberReferencesOf(editor);
    }

    /** The host's store has to reach the workspace, or nothing is ever backed up. */
    @Test
    public void theEditorHandsItsStoreToTheWorkspace() throws IOException {
        assertTrue("WorkbenchApplication must call Workspace.setStorage — without it "
                        + "Workspace.backup() and history() are null on every host",
                calls().contains("com/crystalgui/fs/client/Workspace.setStorage"));
    }

    /** ...and somebody has to ask for the work back. */
    @Test
    public void theEditorOffersUnsavedWorkBack() throws IOException {
        assertTrue("the session restore must call EditorService.restoreUnsavedWork — "
                        + "otherwise it is backed up on every edit and offered by nobody",
                calls().contains("com/crystalgui/workbench/editor/EditorService.restoreUnsavedWork"));
    }

    /**
     * The counter-control on the instrument itself.
     *
     * <p>Both assertions above are "this string is present", which passes for the wrong reason if the
     * scan silently answers an empty set — a wrong path, a class that did not compile, an ASM version
     * that read nothing. A member the editor plainly does call proves the reader is looking at
     * something.
     */
    @Test
    public void theScanIsReadingTheEditorAtAll() throws IOException {
        Set<String> calls = calls();
        assertTrue("the scan found nothing at all", calls.size() > 20);
        assertTrue("...and not the class it was aimed at",
                calls.contains("com/crystalgui/workbench/Workbench.resolve"));
    }
}
