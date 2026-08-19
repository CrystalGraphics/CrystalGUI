/**
 * The Java engine — ECJ behind every {@code .java} document, and behind Run.
 *
 * <h2>What is at this root, and why so little</h2>
 *
 * <p>Two classes: {@link com.crystalgui.language.java.JavaLanguage}, the one call an application makes,
 * and {@code JavaLanguageServices}, the per-document attachment it installs. Everything else is one of
 * the engine's four answers, and each has a package:</p>
 *
 * <table>
 *   <caption>Where the rest lives</caption>
 *   <tr><th>Package</th><th>Answers</th></tr>
 *   <tr><td>{@code .ecj}</td><td>diagnostics, semantic colour and resolution — driving the compiler</td></tr>
 *   <tr><td>{@code .classpath}</td><td>what a script is compiled and completed <em>against</em></td></tr>
 *   <tr><td>{@code .assist}</td><td>completion and Quick Documentation</td></tr>
 *   <tr><td>{@code .fix}</td><td>Alt+Enter — the catalog and its substrate</td></tr>
 *   <tr><td>{@code .exec}</td><td>compile always, run explicitly — the {@code ScriptRuntime}</td></tr>
 * </table>
 *
 * <h2>The host/child line is NOT the directory structure here, and that is deliberate</h2>
 *
 * <p>{@code language.js} splits its directories on which loader defines a class, because six of its
 * classes import neither Rhino nor anything of ours and the answer genuinely cannot be read off the
 * file. Here it can: <b>a class that imports {@code org.eclipse.jdt} is child-side</b>, and that is
 * thirty-six of the fifty. Splitting on it would put the whole fix catalog one level deeper to restate
 * something every one of those files already says in its imports, and would leave the axis that
 * actually makes this package hard to navigate — what the class is <em>for</em> — unexpressed.</p>
 *
 * <p>The rule still holds and is still the one that bites: a child-side class may name only JDK types,
 * {@code com.crystalgui.text.*} and {@code language.engine.bridge}. Naming {@code language.run} or
 * {@code language.js} from one compiles and appears to work, and quietly defines a second copy on the
 * far side of the bridge.</p>
 */
package com.crystalgui.language.java;
