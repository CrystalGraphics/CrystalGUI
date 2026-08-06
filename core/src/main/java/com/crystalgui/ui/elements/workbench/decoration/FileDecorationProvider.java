package com.crystalgui.ui.elements.workbench.decoration;

import com.crystalgui.fs.CgPath;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

/**
 * One source of file decorations — VS Code's {@code IDecorationsProvider}, ported.
 *
 * <p>A provider answers about the files it knows and says nothing about the rest. Dirty state, read-only,
 * diagnostics and version control are four of these, written independently, none aware of the others.</p>
 *
 * <p>{@link #decorated()} is the addition VS Code does not need and we do. Its decoration service keeps a
 * URI-keyed tree it can walk to bubble a child's decoration up to a folder; asking a provider to enumerate
 * instead keeps the whole thing stateless here, and costs nothing because the sets are genuinely small —
 * the modified files, the files with errors. <b>The alternative is asking every provider about every file
 * in a directory, which is a directory listing per folder row per frame.</b></p>
 */
public interface FileDecorationProvider {

    /** For diagnostics and for a settings page that lists what is contributing. */
    String label();

    /** This provider's statement about one file, or null when it has none. */
    @Nullable
    FileDecoration decorationFor(CgPath path);

    /**
     * Every path this provider currently decorates, for folder bubbling.
     *
     * <p>Defaults to empty, which means "my decorations never climb to a folder" — correct for anything
     * that is a property of the file alone, and the safe default because the cost of this method is paid
     * per folder row.</p>
     */
    default Collection<CgPath> decorated() {
        return List.of();
    }
}
