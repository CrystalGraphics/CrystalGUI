/**
 * SandboxTest.js — the JavaScript twin of SandboxTest.java, and the contrast is the point.
 *
 * HOW TO SEE IT. The filter is ON by default -- `unsafe,java.io` -- so just run this. To watch the same
 * file with it off, restart the harness with `-Dcgui.harness.scriptPolicy=none` and compare.
 *
 * WHY THIS FILE READS DIFFERENTLY FROM THE JAVA ONE. Rhino enforces through a ClassShutter, which is
 * asked at the moment a script touches a class -- so JavaScript is refused ONE REACH AT A TIME, where
 * Java is refused as a whole file before it starts. That is not an inconsistency to be fixed; it is what
 * each engine can honestly do. Compiled Java links its classes when the class is defined, so the only
 * two places to stand are before it loads and inside the loader. Rhino resolves a name every time the
 * script asks for one, so it can answer every time.
 *
 * The practical difference is visible below: each section prints, then fails, then the next one runs.
 * Nothing above a refusal is un-done. In the Java file the refusal happens instead of the script.
 *
 * WHAT THIS IS NOT. Read plan_syntax.md 19.1. JavaScript's is the stronger of the two -- call-time
 * interception is real -- but the trust model is still "a script is code the player installed", and
 * server-authored scripts executing on a client are out of scope permanently. This is a guardrail.
 */

'use strict';

print('If you can read this, the script started -- JavaScript is refused per reach, not per file.');

function attempt(label, body) {
    try {
        print(label + ': ' + body());
    } catch (refused) {
        print(label + ' REFUSED: ' + refused);
    }
}

// ── 1. Ordinary I/O — refused by `java.io` in the example above ──────────────────────────────────
// `Java.type` is the front door, and it is the one a shutter sees first.
attempt('1. java.io.File', function () {
    var File = Java.type('java.io.File');
    return new File('.').getAbsolutePath();
});

// ── 2. The package-chain spelling of the same thing ──────────────────────────────────────────────
// `java.io.File` written out reaches the identical class by a different route. A filter that caught
// only `Java.type` would be a filter on a spelling rather than on a class.
attempt('2. package chain', function () {
    return 'reached java.io.File, isDirectory=' + new java.io.File('.').isDirectory();
});

// ── 3. Reflection — refused by `unsafe`, and the reason the rest is worth anything ───────────────
attempt('3. reflection', function () {
    // A STATIC METHOD, because a class object reached through Java.type answers its STATICS -- neither
    // `.name` nor `.class` is a member of it, and asking for either is an InternalError about this
    // fixture rather than a refusal by the filter. Both spellings were tried here and both misreported.
    var Modifier = Java.type('java.lang.reflect.Modifier');
    return 'reached java.lang.reflect.Modifier, isPublic(1)=' + Modifier.isPublic(1);
});

// ── 4. Leaving the JVM entirely — refused by `unsafe` ────────────────────────────────────────────
attempt('4. Runtime', function () {
    return Java.type('java.lang.Runtime').getRuntime().availableProcessors();
});

// ── 5. Something the policy ALLOWS, last, on purpose ─────────────────────────────────────────────
// A filter that refused everything would be indistinguishable from a broken engine. This line runs
// under the example policy above, which is how you tell the difference.
attempt('5. allowed', function () {
    var list = new java.util.ArrayList();
    list.add('still reachable');
    return list.get(0);
});

print('Done. Sections 1-4 refused, 5 ran -- that is the shutter working, not the engine failing.');
