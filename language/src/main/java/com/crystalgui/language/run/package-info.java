/**
 * The Run shell — written against {@code ScriptRuntime}, and naming no language.
 *
 * <h2>The rule, and the test that keeps it</h2>
 *
 * <p>The commands, the console, the rail, the sessions and the workbench wiring are about <em>a script
 * running</em>. Which language it is written in reaches them through two registries —
 * {@code ScriptRuntimes} for how to run it, the {@code LanguageRegistry} for which files are which —
 * and through nothing else. <b>No class in this package or any below it may name {@code language.java},
 * {@code language.js}, ECJ or Rhino</b>, and {@code RunShellIsEngineNeutralTest} is the bytecode scan
 * that fails the commit which reintroduces it.</p>
 *
 * <p>That test exists because this package <em>was</em> written against the concrete Java host, and it
 * did not read as wrong: {@code ScriptHost} was the only runtime, so "the host" and "the Java host"
 * were the same words. The second language is where it would have been paid for — a second Run command
 * and a second panel wiring, or a rewrite of both.</p>
 *
 * <h2>What is at this root</h2>
 *
 * <p>The seam and the shell's own state. {@code ScriptRuntime} is what a language implements;
 * {@code ScriptRuntimes} is where it contributes itself; {@code ScriptRef} is which script is running;
 * {@code ScriptBindings} is what a host puts in scope for every script; {@code ScriptPolicy} is which
 * Java classes a script may reach. {@code RunSessions} and {@code RunState} are which scripts are live,
 * keyed by file rather than by run — this is event-driven, so two of an exit code's three preconditions
 * do not hold.</p>
 *
 * <p>{@code ScriptPolicy} is here rather than in {@code language.js} because three of its four consumers
 * are not JavaScript, and because it must have <b>one</b> entry point: a field per consumer means the
 * executor obeys one policy while resolution, completion and the type index obey another, so a class is
 * offered by the popup and refused at run time.</p>
 *
 * <table>
 *   <caption>The three packages below</caption>
 *   <tr><td>{@code .exec}</td><td>everything that has to be true <em>while</em> a script runs</td></tr>
 *   <tr><td>{@code .console}</td><td>the transcript, and everything that shapes a line of it</td></tr>
 *   <tr><td>{@code .view}</td><td>the workbench surface — the only package here that may import
 *       {@code com.crystalgui.ui}</td></tr>
 * </table>
 */
package com.crystalgui.language.run;
