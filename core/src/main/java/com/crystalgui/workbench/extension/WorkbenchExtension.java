package com.crystalgui.workbench.extension;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.workbench.WorkbenchContext;

/**
 * <b>A feature that attaches itself to a workbench</b> - one interface, one moment, one handle back.
 *
 * <p>This is how anything optional reaches a workbench: a tool window, a file type, a status entry, a
 * set of commands. Implement it, ship a services entry, and an application enables it by naming your id
 * in its manifest. Everything the engine itself offers beyond an editor and a dock - the project tree,
 * Problems, Notifications, the Inspector - arrives through exactly this door, so a third-party feature
 * is never second-class.</p>
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
 * <p>plus one line in {@code META-INF/services/com.crystalgui.workbench.extension.WorkbenchExtension}.
 * <b>One class per feature</b>: put {@code activate} on the thing itself rather than writing a separate
 * {@code *Extension} beside it - the two would share one lifetime and one id, and the second file's
 * only real content would be the first one's name.</p>
 *
 * <h3>The handle is the contract</h3>
 *
 * <p>{@link #activate} returns everything it registered, as one {@link Disposable}. That is what makes a
 * feature removable and a workbench closable: the engine disposes what you hand back, so nothing has to
 * enumerate what any extension did. Anything registered <em>on the workbench</em> needs no handle,
 * because it goes when the workbench does; what needs one is anything process-wide - a global command, a
 * static registry entry - which is exactly the class of thing that otherwise gets left behind.</p>
 *
 * <h3>Written against {@link WorkbenchContext}, never the engine</h3>
 *
 * <p>So an extension can live outside {@code core/} entirely - the language stack's Run shell is one,
 * and it cannot see this module's classes. {@code LayeringTest} keeps it that way.</p>
 *
 * <h3>Available is not enabled</h3>
 *
 * <p>Shipping the jar makes your feature <em>available</em>; an {@code ApplicationKind} naming your id
 * is what turns it <em>on</em>. An id nothing ships is a logged absence rather than an error, which is
 * what lets one manifest name a feature that is simply not present on some hosts.</p>
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
