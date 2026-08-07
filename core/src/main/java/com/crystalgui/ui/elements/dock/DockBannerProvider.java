package com.crystalgui.ui.elements.dock;

import com.crystalgui.core.notify.Notification;

import javax.annotation.Nullable;

/**
 * Something that can put a banner above a panel's content — IntelliJ's
 * {@code EditorNotificationProvider}.
 *
 * <h3>What a banner is for</h3>
 *
 * <p>Facts about a tab that the tab itself cannot show: <em>this file is generated</em>, <em>this is
 * read-only</em>, <em>this is out of date</em>. The generated shader is the case that motivated it —
 * {@code compiled_graph.shader} opens as an ordinary editor with {@code setReadOnly(true)}, so typing in
 * it silently does nothing, which reads as a <b>broken editor</b> rather than as a generated file. There
 * was nowhere for it to say otherwise.</p>
 *
 * <h3>A provider, for the same reason the other three surfaces are</h3>
 *
 * <p>Asked per panel and answering or declining is {@link com.crystalgui.ui.elements.workbench.DocumentType},
 * {@code InspectorSection} and {@code Command.menu} again: the package that knows why a tab is special
 * says so, and neither the dock nor the application enumerates the cases. A file type that wants a
 * "restored from backup" banner registers one and nothing here changes.</p>
 *
 * <h3>Asked with the {@link DockPanelRef}, not with a document</h3>
 *
 * <p>Deliberately, and the motivating case is why: the generated source tab is <b>not</b> a
 * {@code FileDocument} — it is a panel type whose ref carries the derived {@code Resource} in its state.
 * A document-shaped question could not have been asked about the one tab that needed it.</p>
 */
public interface DockBannerProvider {

    /**
     * The banner for {@code panel}, or {@code null} for "nothing to say" — which is the common answer.
     *
     * <p>Severity picks the treatment and the actions become buttons, so a banner can offer the way out
     * rather than only naming the problem: the generated shader's is <em>Open Graph</em>.</p>
     */
    @Nullable
    Notification bannerFor(DockPanelRef panel);
}
