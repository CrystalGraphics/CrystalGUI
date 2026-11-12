package com.crystalgui.desktop.app;

/**
 * What builds an application's document: the host that frames documents implements it and hands it to the registry.
 * A desktop without one runs every application in its own document, as a test does.
 *
 * <pre>{@code
 * desktop.applications().useDocuments(kind -> screen.openDocument(kind));
 * }</pre>
 */
public interface AppDocuments {

    /** A document for an application of {@code kind}, styled as the shell's and run as the kind says. */
    AppDocument open(ApplicationKind kind);
}
