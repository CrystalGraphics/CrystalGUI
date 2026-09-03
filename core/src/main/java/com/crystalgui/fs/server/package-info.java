/**
 * <b>The server's workspace</b> — authorisation, etags, the trash, presence and watching.
 *
 * <p>Empty until F3 of {@code plan_fs_rewrite.md}, which moves {@code WorkspaceService} and its
 * neighbours here and replaces {@code WorkspaceWatcher} with a {@code WatchHub}. The package exists
 * now so the tier ordering {@code LayeringTest.theFilesystemTiersDoNotReachUpward} asserts is in place
 * before anything is written into it, rather than being added after the first violation.</p>
 *
 * <p>Knows {@code fs} and {@code fs.project}. Must never name {@code fs.client}: the two halves talk
 * through {@code fs.protocol} and nothing else, which is what makes a dedicated server able to run
 * this package with no client on the classpath at all.</p>
 */
package com.crystalgui.fs.server;
