/**
 * <b>A filesystem, and nothing about a workspace</b> — the bottom of the stack.
 *
 * <p>{@link com.crystalgui.fs.provider.CgFileSystem} is the SPI: stat, list, read, write, mkdir,
 * delete, rename, and a capability set saying which of the optional halves an implementation has.
 * {@link com.crystalgui.fs.provider.LocalFileSystem} is the real one and
 * {@link com.crystalgui.fs.provider.InMemoryFileSystem} is the one every test runs on — a complete,
 * exercisable filesystem with a monotonic clock, which is what makes an etag reproducible.</p>
 *
 * <p>Nothing here knows what a permission, an actor or an editor is. Authorisation, trash, presence
 * and conflict detection are {@code fs.server}'s, over this.</p>
 *
 * <h3>It names {@code fs.project}, and not the other way round</h3>
 *
 * <p>A project is a NAMED ROOT and a filesystem is what resolves one to a real directory, so
 * {@code LocalFileSystem} and {@code NioFileEventSource} ask the registry where a project lives.
 * The reverse edge would be a cycle, and briefly was: {@code CgPath}, {@code CgFileError} and
 * {@code CgFileSystemException} are named by both, so they sit at {@code fs}'s own root with
 * {@code Resource} rather than here.</p>
 */
package com.crystalgui.fs.provider;
