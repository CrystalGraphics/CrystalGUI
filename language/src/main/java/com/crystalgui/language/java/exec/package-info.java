/**
 * Compile always, run explicitly, re-run replaces — the Java {@code ScriptRuntime}.
 *
 * <p>{@code ScriptHost} is what the Run panel holds, and it is one runtime among what is now two. The
 * shell in {@code language.run} is written against the interface and never against this class; a
 * bytecode scan enforces that, because the shell was written against this class first and it did not
 * read as wrong while Java was the only runtime.</p>
 *
 * <p>{@code ScriptPrelude} is the other half of what makes a script a script: an author writes a
 * <em>body</em>, and a compiler needs a compilation unit. The wrapping is reversible, which is what lets
 * a diagnostic reported against the synthesized unit be reported back at the line the author typed.</p>
 */
package com.crystalgui.language.java.exec;
