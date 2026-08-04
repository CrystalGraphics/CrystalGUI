package com.crystalgui.fs;

import java.util.List;

/**
 * The open documents a file operation has to account for.
 *
 * <p><b>The whole point of this interface is that it names no widget.</b> {@link WorkspaceFileService}
 * enforces rules about editors without being able to see one, which is what makes those rules testable
 * with no window, no fonts and no style engine — the same move that earned {@code text/cursor} its
 * correctness and exposed a real bug within minutes of the extraction.</p>
 *
 * <p>Modelled on VS Code's {@code IWorkingCopyService}, reduced to the three questions a file operation
 * actually asks. Its {@code IWorkingCopyFileService} exists for one sentence — <i>"any operation that
 * would leave a stale dirty working copy behind will make sure to revert the working copy first"</i> —
 * and these are the operations that sentence needs.</p>
 */
public interface WorkingCopies {

    /** Nothing is open. For a host with no editors, and the default in tests that do not care. */
    WorkingCopies NONE = new WorkingCopies() {
        @Override
        public List<CgPath> openUnder(CgPath path) {
            return List.of();
        }

        @Override
        public void close(CgPath path) {
        }

        @Override
        public void retarget(CgPath from, CgPath to) {
        }
    };

    /**
     * Every open document at {@code path} or beneath it.
     *
     * <p>Beneath, not just at: deleting a directory has to account for the six files open inside it, and
     * that is exactly the case a per-path lookup silently misses.</p>
     */
    List<CgPath> openUnder(CgPath path);

    /**
     * Drops an open document, discarding whatever was unsaved in it.
     *
     * <p>Called when the bytes behind it are gone — a delete, or an overwritten move destination. There is
     * nothing to save it back to, so keeping it open is keeping a document that cannot be written.</p>
     */
    void close(CgPath path);

    /**
     * Points an open document at its new path, <b>keeping its content and its undo history</b>.
     *
     * <p>A deliberate divergence from VS Code, which soft-reverts the source of a move. The filesystem
     * plan already settled this for externally-observed renames — <i>"the client retargets the open
     * document, keeping edits and undo history. The bytes did not change"</i> — and a rename the user
     * performed themselves has even less claim to throw their work away.</p>
     */
    void retarget(CgPath from, CgPath to);
}
