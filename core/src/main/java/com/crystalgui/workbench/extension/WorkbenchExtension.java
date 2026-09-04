package com.crystalgui.workbench.extension;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.workbench.WorkbenchContext;

/**
 * <b>A feature that attaches itself to a workbench</b> — one interface, one moment, one handle back.
 *
 * <p>It replaces three incompatible ways of doing this that had drifted apart: a thing baked into the
 * workbench's own constructor, a {@code static register(Workbench)} somebody has to remember to call,
 * and a {@code static install(...)} that returns something the caller then has to keep. Each was a
 * different answer to "when does this run and who takes it down", and the third answer was usually
 * nobody.</p>
 *
 * <pre>{@code
 * public final class NotesKind implements WorkbenchExtension {
 *     public static final String ID = "crystalgui:notes";
 *     public static final DocumentKind KIND = DocumentKind.of(ID, "Notes")...;
 *
 *     public NotesKind() { }                       // ServiceLoader's rule
 *     public String id() { return ID; }
 *     public Disposable activate(WorkbenchContext workbench) {
 *         return workbench.kinds().register(KIND);
 *     }
 * }
 * }</pre>
 *
 * <p>plus one line in {@code META-INF/services/com.crystalgui.workbench.extension.WorkbenchExtension}. <b>One
 * class per feature</b>: a declaration and a separate {@code *Extension} beside it read as a boundary
 * and are a wrapper — one lifetime, one id, and the second file's only real content is the name of the
 * first.</p>
 *
 * <h3>The handle is the contract</h3>
 *
 * <p>{@code activate} returns everything it registered, as one {@link Disposable}. That is what makes
 * an extension removable — and, more to the point, what makes a workbench <em>closable</em>: the
 * engine disposes what an extension handed back, so nothing has to enumerate what any of them did.
 * Registering on the workbench itself needs no handle, because those go when it does; what needs one is
 * anything process-wide, which is exactly the class of thing that used to be left behind.</p>
 *
 * <h3>Written against {@link WorkbenchContext}, never the engine</h3>
 *
 * <p>So an extension may live outside {@code core/} — the language stack's Run panel is one, and it
 * cannot see this module's classes. {@code LayeringTest} keeps it that way.</p>
 */
public interface WorkbenchExtension {

    /**
     * Namespaced, stable, and the string an application's manifest names.
     *
     * <p>Persisted in nothing yet and in a manifest shortly, which is why it is picked once and not
     * derived from a class name.</p>
     */
    String id();

    /**
     * Attaches to {@code workbench}, and hands back everything that has to be taken away again.
     *
     * <p>Called once per workbench, while it is being built — so the tree exists and a window does not.
     * Anything that needs geometry, a document or a frame waits for one rather than asking here.</p>
     */
    Disposable activate(WorkbenchContext workbench);
}
