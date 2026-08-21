package com.crystalgui.fs;

import com.crystalgui.text.lang.SymbolInfo;

/**
 * Supplies the bytes behind a {@link Resource} whose scheme is not the workspace.
 *
 * <p>VS Code's {@code ITextModelContentProvider}, IntelliJ's {@code VirtualFileSystem} for a
 * {@code NonPhysicalFileSystem}. A feature registers one for its own scheme and the workbench can then
 * open that scheme without knowing anything about the feature.</p>
 *
 * <p>Read-only by default, because almost every non-file scheme is: a generated shader, a diff, a
 * decompiled class. A scheme that <em>is</em> writable says so, and nothing has to be told twice.</p>
 */
@FunctionalInterface
public interface ResourceContentProvider {

    /**
     * The current content behind {@code resource}.
     *
     * <p><b>Must have an answer even when the origin is gone.</b> A derived resource's origin can be
     * closed or deleted while its tab is still open, and this is reached from a paint path — so an empty
     * array is the contract for "nothing to show", never an exception. A pane can render a banner over
     * empty; it cannot render a throw.</p>
     */
    byte[] read(Resource resource);

    default boolean isReadOnly(Resource resource) {
        return true;
    }

    /**
     * What to call this resource where a person reads it — a tab, a breadcrumb — or null.
     *
     * <h3>Why the provider names it and not the caller</h3>
     *
     * <p>A resource is a NAME and nothing else: {@code library://java.util.ArrayList} says which type,
     * not what serves it. Whether that arrives as attached source or as reconstructed bytecode is known
     * only here, and it is the difference between a tab reading {@code ArrayList.java} and one reading
     * {@code FlexDirection.class} — which is how IntelliJ says the same thing, and how a reader tells at
     * a glance whether they are looking at what somebody wrote.</p>
     *
     * <p>It also decides the icon, since a file-icon theme keys on the name.</p>
     *
     * <p><b>Null lets the caller decide</b>, which is the right default for a scheme whose content has no
     * file-ish identity at all — a diff, a log, a generated shader.</p>
     */
    default String displayName(Resource resource) {
        return null;
    }

    /**
     * The DECLARATION this resource shows, when it shows one — or null.
     *
     * <h3>Why the provider, and why a symbol rather than an icon</h3>
     *
     * <p>A file name cannot answer what a resource holds. {@code FlexDirection.class} is an ENUM and
     * {@code Runnable.class} is an INTERFACE, and the extension is the same both times — so deriving a
     * glyph from the name draws a class icon on every one of them. That is not a cosmetic slip: this
     * codebase has already paid for it once, when a hand-built symbol reported {@code java.util.List} as
     * a class and the documentation popup drew a class glyph beside an interface, in the same session
     * where a {@code .java} file drew the right one.</p>
     *
     * <p><b>A symbol rather than an icon name</b>, because the picture is not the only thing that
     * follows from it: a {@code static final} class carries two more marks over the glyph, and a tooltip
     * says "Final class" in words. Handing over the icon would answer one of those and leave the others
     * to be derived a second way, which is how the two tables this replaced came to exist.</p>
     */
    default SymbolInfo symbolOf(Resource resource) {
        return null;
    }
}
