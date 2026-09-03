/**
 * <b>The client's workspace</b> — {@code Workspace} and its facades, documents, file operations,
 * backup, local history and connection health.
 *
 * <p>Empty until F4 of {@code plan_fs_rewrite.md}, which replaces {@code WorkspaceClient}'s 832 lines
 * of seven jobs — an RPC facade, three parallel per-path maps, a chunked-pull state machine and four
 * single-slot listener fields — with one entry point whose sub-facades are named by noun.</p>
 *
 * <p>May name {@code fs}, {@code fs.project}, {@code fs.protocol} and the wire's own
 * {@code net.protocol}. Never {@code fs.server}, and never the UI's networking.</p>
 */
package com.crystalgui.fs.client;
