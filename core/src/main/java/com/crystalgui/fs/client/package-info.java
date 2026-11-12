/**
 * <b>The client's workspace</b> — the desktop's {@code Workspace} service, an application's {@code WorkspaceClient} and its facades, documents, file operations,
 * backup, local history and connection health.
 *
 * <p>{@link com.crystalgui.fs.client.WorkspaceClient} is the entry point; everything else here is one of
 * its sub-facades or something one of them holds.</p>
 *
 * <p>May name {@code fs}, {@code fs.project}, {@code fs.protocol} and the wire's own
 * {@code net.protocol}. Never {@code fs.server}, and never the UI's networking.</p>
 */
package com.crystalgui.fs.client;
