/**
 * <b>What a project is</b> — a named root, its source roots and what it excludes.
 *
 * <p>{@link com.crystalgui.fs.project.ProjectProvider} is what a host implements to say which
 * projects exist; {@link com.crystalgui.fs.project.ProjectRegistry} caches the answer against a
 * provider revision, and {@link com.crystalgui.fs.project.ProjectInfo} is what travels.
 * {@link com.crystalgui.fs.project.Excludes} is the one glob matcher, honoured by the listing, the
 * watcher and anything that walks.</p>
 *
 * <p>The bottom tier: it names {@code fs}'s root types and nothing else in {@code fs}.</p>
 */
package com.crystalgui.fs.project;
