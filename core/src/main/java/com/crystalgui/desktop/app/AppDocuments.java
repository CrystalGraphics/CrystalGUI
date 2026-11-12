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

    /**
     * A document for a connection's server windows, which every frame of an application's draws after: a window of the
     * workbench may show one. Named for the connection it follows.
     *
     * @throws UnsupportedOperationException on a host that runs no documents of its own
     */
    default AppDocument openServer(String name) {
        throw new UnsupportedOperationException("no documents for " + name);
    }
}
