/**
 * SandboxTest.java — a file written to be REFUSED. Every section reaches for something a locked-down
 * deployment would not allow.
 *
 * HOW TO SEE IT. The filter is ON by default -- `unsafe,java.io` -- so running this file should REFUSE
 * it outright: nothing below prints, and the Run panel shows which classes it reached for. To watch the
 * same file with the filter off, restart the harness with `-Dcgui.harness.scriptPolicy=none`.
 *
 * `unsafe` is ScriptPolicy.UNSAFE -- reflection, method handles, ClassLoader, Runtime, ProcessBuilder,
 * java.security and the internals. `java.io` is added so section 1 has something to hit.
 *
 * WHAT SHOULD HAPPEN, and the difference between the two halves is the whole design:
 *
 *   Sections 1-3 name their classes in source, so they are in the compiled constant pool. The
 *   AHEAD-OF-TIME scan (RefusedTypes) sees them and refuses the WHOLE FILE before a single line runs --
 *   which is why the first `println` below never appears. That matters: a script refused halfway has
 *   already done whatever it did before the refusal, and a partly-applied script is its own hazard.
 *
 *   Section 4 builds a class name at run time, so the constant pool holds only `java.lang.Class`. The
 *   scan cannot see it and passes; the LOADER GATE (ScriptClassLoader.loadClass) catches it when the
 *   name is actually resolved. To watch that half on its own, comment out sections 1-3.
 *
 * WHAT THIS IS NOT. Read plan/lang-stack.md 19.1. For Java this is a guardrail, not a security boundary:
 * compiled bytecode links what it links, SecurityManager is gone from modern JVMs, and a script that can
 * reach reflection can resolve names neither half of this ever sees. It stops accidents and casual
 * reach, and it keeps the editor from teaching an API the runtime will refuse. It does not contain a
 * determined author, and nothing here should be described as though it did.
 */

System.out.println("If you can read this, the class filter is OFF.");

// ── 1. Ordinary I/O — refused by `java.io` in the example above ──────────────────────────────────
// Nothing exotic. This is the shape most policies actually care about: a script reading or writing
// files on the machine of whoever is running it.
java.io.File here = new java.io.File(".");
System.out.println("1. listed " + here.getAbsolutePath());

// ── 2. Reflection — refused by `unsafe`, and the reason the rest is worth anything ───────────────
// The route out of every class filter. With this reachable, an allowlist is a suggestion: the name
// below is a string, and a string can be assembled.
java.lang.reflect.Method[] methods = String.class.getMethods();
System.out.println("2. String has " + methods.length + " methods");

// ── 3. Leaving the JVM entirely — refused by `unsafe` ────────────────────────────────────────────
// The one nobody argues about. `Runtime` and `ProcessBuilder` run whatever the host user can run.
Runtime runtime = Runtime.getRuntime();
System.out.println("3. " + runtime.availableProcessors() + " processors");

// ── 4. A name built at run time — the LOADER's case, not the scan's ──────────────────────────────
// The constant pool for this section names `java.lang.Class` and `java.lang.String` and nothing else,
// so the ahead-of-time scan has nothing to object to. Comment out 1-3 and run again: the file starts,
// prints, and is stopped at the moment the class is resolved rather than before it began.
String assembled = "java.io" + "." + "File";
try {
    Class<?> late = Class.forName(assembled);
    System.out.println("4. resolved " + late.getName() + " at run time");
} catch (ClassNotFoundException refused) {
    System.out.println("4. refused: " + refused.getMessage());
}
