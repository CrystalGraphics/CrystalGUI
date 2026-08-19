package com.crystalgui.ui.elements.workbench.decoration;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.Markers;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Problems, as a file decoration — the red filename in the project tree and on the editor tab.
 *
 * <h3>A provider, not a feature</h3>
 *
 * <p>Everything this needs already existed and nobody had joined it up: {@link FileDecoration} ships
 * {@code WEIGHT_ERROR} and {@code WEIGHT_WARNING}, {@code decorations.css} ships {@code .decoration-error},
 * {@code .decoration-warning} and {@code .decoration-info}, and the tree has resolved and applied
 * decorations since it was written. The framework was built for this and was waiting for the one class
 * that reads {@link Markers}.</p>
 *
 * <p>Being a provider rather than a special case in either view is what makes the tab and the tree agree
 * without either knowing about diagnostics: they both ask {@link FileDecorations} the same question, and
 * the answer is merged with whatever else contributes — dirty state, VCS — by the rules already there.</p>
 *
 * <h3>Errors only, deliberately</h3>
 *
 * <p>Not warnings, and not notes. A decoration on a filename is read at a glance across a whole tree, and
 * its only useful question is "is this file broken" — an amber name says "there is something here you may
 * eventually care about", which on a real project is most files most of the time and turns the tree into
 * a colour chart nobody reads. The graded answer already exists in two places with room to be precise:
 * the inspection widget counts every severity, and the Problems panel lists them.</p>
 *
 * <h3>Colour, no badge</h3>
 *
 * <p>IntelliJ underlines the name and VS Code recolours it; neither puts a count on the file itself, and a
 * letter here would collide with the {@code M} the modified provider already contributes.</p>
 *
 * <p><b>It bubbles.</b> A folder reddening because something inside it will not compile is worth the walk
 * up — that is the case the bubble was built for, and the reason a bubbled decoration keeps the colour and
 * drops the badge.</p>
 */
public final class DiagnosticDecorations implements FileDecorationProvider {

    private final Markers markers;

    public DiagnosticDecorations(Markers markers) {
        this.markers = markers;
    }

    @Override
    public String label() {
        return "Problems";
    }

    @Override
    @Nullable
    public FileDecoration decorationFor(CgPath path) {
        if (path == null || !hasErrors(path)) return null;
        return FileDecoration.of(FileDecoration.WEIGHT_ERROR, "decoration-error", null,
                "Contains errors");
    }

    /**
     * Whether {@code path} has anything at {@code ERROR}.
     *
     * <p>Matched on the resource's <b>path</b> rather than by constructing a {@link Resource}, because a
     * resource carries a project id this provider has no way to know — the tree walks {@code CgPath}s and
     * the index is keyed by resource, so the join has to happen on the part they share.</p>
     */
    private boolean hasErrors(CgPath path) {
        for (Resource resource : markers.resources()) {
            if (!path.equals(resource.asPath())) continue;
            for (Diagnostic diagnostic : markers.read(resource)) {
                if (diagnostic.severity() == DiagnosticSeverity.ERROR) return true;
            }
        }
        return false;
    }

    /**
     * Every file that will not compile.
     *
     * <p>Paid per folder row, so this is the index's own key set rather than a tree walk: a project with
     * no errors answers with an empty list and no folder does any work at all.</p>
     */
    @Override
    public Collection<CgPath> decorated() {
        List<CgPath> paths = new ArrayList<>();
        for (Resource resource : markers.resources()) {
            for (Diagnostic diagnostic : markers.read(resource)) {
                if (diagnostic.severity() != DiagnosticSeverity.ERROR) continue;
                paths.add(resource.asPath());
                break;
            }
        }
        return paths;
    }
}
