package com.crystalgui.core.cursor;

import com.crystalgraphics.platform.CgService;

/**
 * Shows a mouse cursor — the seam between whoever <em>decides</em> on one and whoever <em>presents</em> it.
 *
 * <pre>{@code
 * CgPlatform.get(CursorService.SERVICE).setCursor(Cursor.POINTER);   // anyone; this is the whole API
 * }</pre>
 *
 * <p><b>A loader registers nothing.</b> The default is {@code PlatformCursorService}, which resolves
 * the keyword to a picture through {@link CursorBitmaps#artFor} and hands it to CrystalGraphics'
 * {@code CgCursorService} — whose LWJGL2 and LWJGL3 adapters ship with CrystalGraphics and know
 * nothing about this package. So a host gets working cursors without naming a cursor service, an
 * adapter, or the module either lives in. Filling the slot is still possible, for a test or a host
 * that wants to intercept.</p>
 *
 * <h3>Why this is CrystalGUI's and no longer CrystalGraphics'</h3>
 *
 * <p>It began in {@code com.crystalgraphics.platform.service} for a reason that has expired: CrystalGUI
 * had no way to own a service, so anything a loader had to supply went into the one registry that
 * existed. {@link CgService} is that reason's answer — the open half of the platform stack, for
 * contracts the rendering framework must not name. Nothing about a cursor is a rendering concern (no GL
 * context, no backend capability, no frame) and the whole of what decides one is here: the {@code cursor}
 * CSS property, its inheritance, the {@code auto} context rule, and {@code Input}'s override for a live
 * gesture.</p>
 *
 * <h3>Presenting one is toolkit-specific to an awkward degree — and that is CrystalGraphics' half</h3>
 *
 * <ul>
 *   <li><b>LWJGL3 / GLFW</b> (MC 1.20.x) has standard system cursors, the resize set included. A
 *       mapping table is most of the implementation.</li>
 *   <li><b>LWJGL2</b> (MC 1.7.10, and the harness) has <b>no standard cursors at all</b>:
 *       {@code Mouse.setNativeCursor} takes a cursor built from raw pixel data — bottom-up, unlike
 *       every other toolkit — which is what {@link CursorBitmaps} exists to supply.</li>
 * </ul>
 *
 * <p>Both adapters live in CrystalGraphics' {@code mc-lwjgl2} / {@code mc-lwjgl3} tier-1 modules and
 * take a {@code CgCursorService.Image}: a name, some pixels and a hotspot. <b>Neither enumerates keywords</b>
 * — {@link CursorBitmaps#artFor} is the single table and it is here, so adding a {@link Cursor} needs
 * no edit anywhere else. The name is what crosses: an adapter whose toolkit ships that shape natively
 * prefers its own and falls back to our artwork.</p>
 *
 * <h3>{@link #NONE} is the absent-value, unlike the closed bundle's services</h3>
 *
 * <p>{@code CgPlatformService} deliberately gives none of its methods a default, on the reasoning that
 * inheriting "no sound" is indistinguishable from deciding on it. A cursor is the case that argument
 * does not cover: an unpresented one is a <b>cosmetic</b> gap and never a functional one, and the engine
 * runs where there is nothing to present to — a dedicated server, a headless test, a fixture with no
 * window. Those must not have to register a stub to stay silent. A slot rather than a bundle method is
 * exactly that distinction, and {@link CgService} still says so once in the log the first time an
 * unprovided slot is read.</p>
 *
 * <p><b>Called only on change</b>, never per frame: {@code Input} tracks the last cursor it asked for and
 * stays quiet while the pointer sits still. An implementation may therefore do real work here — creating
 * a native cursor object — though caching by {@link CursorArt} is still wise, since a pointer crossing a
 * UI cycles through a handful repeatedly.</p>
 */
@FunctionalInterface
public interface CursorService {
    /** Shows nothing, for a host with no pointer to dress. @see #SERVICE */
    CursorService NONE = cursor -> {};

    /**
     * The slot. Declared here because CrystalGUI owns this contract — presenting a cursor is not
     * something the rendering framework requires, so it does not belong in {@code CgPlatformService}'s
     * closed bundle.
     *
     * <p>One slot for the process, and that is the honest shape rather than a shortcut: two windows on
     * one desktop share one pointer, so a per-document service would be several answers to a question
     * that has one. {@code Input.setCursorSink} is the per-document escape hatch for a test or a loader
     * that genuinely wants to intercept.</p>
     */
    CgService<CursorService> SERVICE = CgService.of("crystalgui:cursor", new PlatformCursorService());

    /**
     * Shows {@code cursor}.
     *
     * <p>Never receives {@link Cursor#AUTO}: the caller resolves that to a concrete value first, so an
     * implementation only ever sees something it can map or ignore.</p>
     */
    void setCursor(Cursor cursor);
}
