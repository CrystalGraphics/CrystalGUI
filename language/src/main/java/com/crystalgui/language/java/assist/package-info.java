/**
 * What the editor asks at a caret, and what Java answers — completion and Quick Documentation.
 *
 * <p>Eclipse calls this layer {@code codeassist} and the grouping is the same one: these are the
 * answers that are computed <em>on demand at an offset</em>, as against the analysis in {@code .ecj}
 * that is computed for the whole document on a debounce. The distinction is not cosmetic — an answer
 * here is allowed to be slow once and must never be recomputed per keystroke, which is why
 * {@code AttachedSources} caches and {@code SourceArchives} indexes.</p>
 *
 * <p>Documentation is <b>quoted first and assembled only as a fallback</b>: a symbol with a
 * {@code -sources.jar} or a JDK {@code src.zip} behind it has its declaration read out of the file
 * somebody wrote, and only an obfuscated jar, a mod shipped without sources or a bare directory of
 * class files falls through to {@code JavaSignatures} reconstructing one. Both paths are live and the
 * fallback is the Minecraft case, so neither is legacy.</p>
 *
 * <p><b>And there is a third source now, below both</b> (M13 §25.1): the class file itself. Where no
 * source is attached, {@code ClassFileParameterNames} still reads the real parameter <em>names</em> off
 * the bytes, so the assembled declaration is no longer types-only. The order is quote, assemble with
 * read names, assemble with types — each step losing something the one above it had, and none of them
 * a shortfall to be papered over with {@code arg0}.</p>
 *
 * <p>{@code JavaDocs} is the other half of Quick Documentation and the one that was missing for two
 * milestones: the popup's body band. It renders a {@code Javadoc} node, and
 * {@code JavaSignatures.documentationOf} finds that node by the same two-step walk the signature quote
 * uses — this unit, then the attached source — <b>inheriting an override's comment from its
 * supertype</b>, without which most methods would render nothing.</p>
 */
package com.crystalgui.language.java.assist;
