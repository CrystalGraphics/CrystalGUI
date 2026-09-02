package com.crystalgui.net.window;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.Styleable;
import com.crystalgui.style.sheet.StyleSheet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * <b>A server's CSS, confined to its own window and taken away when the window goes.</b>
 *
 * <p>What a server sends used to be handed straight to the client's one style engine:</p>
 *
 * <pre>{@code
 * host.styles().addStylesheet(StyleSheet.parse(sheet));   // and never removed
 * }</pre>
 *
 * <p>Three things wrong with that, and each is silent:</p>
 *
 * <ul>
 *   <li><b>It never came off.</b> Close the window and its rules stayed, restyling whatever the user
 *       did next. A session of opening and closing windows accumulates every sheet any of them ever
 *       used, and nothing on screen says why the editor's buttons have gone green.</li>
 *   <li><b>It went in twice.</b> Two windows of one type meant two copies of one sheet, at increasing
 *       priority, and re-adding a sheet appends it — so the duplicate quietly wins.</li>
 *   <li><b>It applied to EVERYTHING.</b> A rule a server wrote for its own panel matched the client's
 *       own UI: a mod could restyle the workbench, the file tree and every other mod's window by
 *       shipping {@code button { … }}.</li>
 * </ul>
 *
 * <h3>Scoped to the window's own root, natively</h3>
 *
 * <p>{@code StyleEngine.addStylesheet(sheet, root)} is CSS {@code @scope}: only nodes at or under
 * {@code root} can match the sheet's rules. That is the whole of the confinement, and it is the
 * engine's rather than this class's.</p>
 *
 * <p><b>This used to prefix every selector with a class instead, and the difference is not
 * cosmetic.</b> A prefix is a DESCENDANT COMBINATOR, and an element is not its own descendant — so
 * every rule aimed at the panel root itself silently stopped applying. The machine window opened as
 * a sliver in the corner with no styling and nothing failing anywhere, because the one rule carrying
 * its width and padding was written for the element wearing the scope. Native scoping means "this
 * element or below" and the whole problem disappears rather than being worked around. The textual
 * pass also had to read selectors out of the text, which is how a COMMENT between two rules came to
 * be parsed as the next rule's selector and refused a whole sheet over a sentence ending in a full
 * stop.</p>
 *
 * <h3>One parse per text, one installation per window</h3>
 *
 * <p>Two windows of one type share a parsed {@link StyleSheet} — that is what makes the refcount
 * worth having — and each installs it against its own root. So the sheet must be removed against
 * that root and not wholesale, or closing either window unstyles the other; see
 * {@link com.crystalgui.style.StyleEngine#removeStylesheet(StyleSheet, Styleable)}.</p>
 *
 * <p>What it must not do is <b>fail loudly</b>: a sheet that will not parse is a plain window, never
 * a missing one. Note the parser no longer throws for malformed input — it drops what it cannot read
 * and keeps the rest — so that is now about the OUTCOME rather than about an exception.</p>
 */
public final class ScopedSheets {

    /** What a host does with a sheet once it has been scoped to a window's root. */
    public interface Host {
        void add(StyleSheet sheet, Styleable root);

        void remove(StyleSheet sheet, Styleable root);
    }

    /** One parse, and the roots currently showing it. */
    private static final class Entry {
        final StyleSheet sheet;
        final Set<Styleable> roots = new LinkedHashSet<>();

        Entry(StyleSheet sheet) {
            this.sheet = sheet;
        }
    }

    private final Host host;

    /** Keyed by the CSS text, so identical CSS is parsed once however many windows show it. */
    private final Map<String, Entry> live = new LinkedHashMap<>();

    public ScopedSheets(Host host) {
        this.host = host;
    }

    /** Installs {@code css} scoped to {@code root}, re-using the parse if another window has it. */
    public void acquire(String css, Styleable root) {
        if (root == null) return;
        Entry entry = live.get(css);
        if (entry == null) {
            StyleSheet parsed;
            try {
                parsed = StyleSheet.parse(css);
            } catch (RuntimeException malformed) {
                // A GUARD, not a path. Since 5.2 the parser drops a rule it cannot read, warns with
                // the selector text and carries on -- CSS's own rule -- so garbage installs and
                // simply carries nothing. The catch stays because a server can ship anything and the
                // one thing this must never do is lose the window along with its styling.
                CrystalGuiCore.LOGGER.warn("A server sheet would not parse: {}", malformed.getMessage());
                return;
            }
            entry = new Entry(parsed);
            live.put(css, entry);
        }
        // The SET is what makes this idempotent: a window re-described mid-session re-applies its
        // sheets, and a second install against one root would be a second copy at higher priority.
        if (entry.roots.add(root)) host.add(entry.sheet, root);
    }

    /** Gives one back. The parse is dropped when the last window using it goes. */
    public void release(String css, Styleable root) {
        Entry entry = live.get(css);
        if (entry == null || !entry.roots.remove(root)) return;
        host.remove(entry.sheet, root);
        if (entry.roots.isEmpty()) live.remove(css);
    }

    /** How many distinct sheets are currently parsed. For tests and diagnostics. */
    public int installed() {
        return live.size();
    }
}
