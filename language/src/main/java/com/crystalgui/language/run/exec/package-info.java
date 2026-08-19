/**
 * Everything that has to be true <em>while</em> a script is running — capture, stop, and reuse.
 *
 * <h2>The kill switch, in three parts that only work together</h2>
 *
 * <p>{@code Safepoints} injects a check at method entry and at backward branches, <b>as a call and never
 * as a read-and-branch</b>: a new branch target in a Java 7+ class file needs a new {@code StackMapTable}
 * entry, which means {@code COMPUTE_FRAMES}, which means ASM loading classes at instrumentation time —
 * on a Minecraft host, that is loading MC classes while compiling. A single {@code invokestatic} of a
 * void no-arg method adds no branch, no local and no stack depth, so every frame stays valid, and
 * HotSpot inlines it back to the volatile read the obvious version would have emitted.</p>
 *
 * <p>{@code ScriptControl} is what the injected call reaches, and the flag is <b>the thread's own
 * interrupt status</b> — one {@code interrupt()} then reaches a spinning script through the check and a
 * blocked one through {@code InterruptedException}; a private static would cover only the busy half.
 * {@code ScriptStoppedException} is an {@code Error} rather than an {@code Exception} because scripts are
 * full of {@code catch (Exception e)} around exactly the loop a stop must break out of.
 * {@code catch (Throwable)} still defeats it, and nothing cooperative can beat that — the trust model is
 * the answer.</p>
 *
 * <h2>Output, input and reuse</h2>
 *
 * <p>{@code ScriptOutput} and {@code ScriptInput} exist because there is <b>no process boundary</b>:
 * IntelliJ captures a run's output for free because a run is a separate process. Here the script's
 * {@code System.out} and everyone else's are the same stream, so both route by asking who is calling.</p>
 *
 * <p>{@code ScriptCache} keys on {@code (source hash, mappings hash, band)} — three components, each a
 * real invalidation, and hashed rather than kept.</p>
 */
package com.crystalgui.language.run.exec;
