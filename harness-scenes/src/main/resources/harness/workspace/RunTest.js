/**
 * RunTest.js — a file for RUNNING, not for reading. Every section prints what it did.
 *
 * The JavaScript twin of RunTest.java, and the same contract: open it and press Shift+F10. Each section
 * logs a line, so the console is a transcript of what this engine actually executed. A section missing
 * from the output is where it stopped.
 *
 * Where Main.js is about what the EDITOR knows before anything runs — colours, hovers, completion,
 * diagnostics — this is about what the RUNTIME does. The two are deliberately separate: an engine can
 * look entirely correct in an editor and fail at the first `Java.type`, and the reverse is just as
 * possible.
 *
 * WHAT IT TARGETS. This band's Rhino, whatever it is — `let`, `const`, arrow functions and template
 * literals are used because every shipped band takes them, and `class`, modules and `async` are not,
 * because none does. That set is measured rather than assumed (see JsKeywords), so if a future band
 * widens it this file is what should widen with it.
 */

'use strict';

// ── The logger ──────────────────────────────────────────────────────────────────────────────────
// Everything below reports through these, so the console reads as a transcript rather than as a pile
// of values. `console.log` goes to the out stream and `console.warn`/`console.error` to the error one,
// which is Node's own routing and what the panel colours by.

var section = function (name) {
    console.log('');
    console.log('== ' + name + ' ==');
};

var report = function (label, value) {
    console.log('  ' + label + ': ' + value);
};

// ── Values, and how the console shows them ──────────────────────────────────────────────────────
// Not a formatting test for its own sake: every one of these is a shape `RhinoConsoleFormat` decides
// about, and each was chosen because the obvious implementation gets it wrong. An array prints as
// `[ 1, 2, 3 ]` rather than `1,2,3`; an object as `{ a: 1 }` rather than `[object Object]`; a function
// by name rather than by its whole source; a nested container as `[Object]` rather than recursing
// forever.

section('values');
console.log([1, 2, 3]);
console.log({ alpha: 1, beta: 'two', 'needs-quotes': 3 });
console.log({ nested: { deeper: true }, list: [1, [2]] });
console.log(function namedFunction() { return 1; });
console.log('a string is printed bare at the top level');
console.log(['but quoted', 'inside a container']);
console.log(null, undefined, NaN, Infinity, /a-regex/g);

// ── Scopes and closures ─────────────────────────────────────────────────────────────────────────

section('scopes');

function makeCounter(start) {
    var count = start;
    return {
        next: function () { return ++count; },
        value: function () { return count; }
    };
}

var counter = makeCounter(10);
counter.next();
counter.next();
report('a closure kept its own count', counter.value());

var shadowing = 'outer';
(function () {
    var shadowing = 'inner';
    report('an inner declaration shadows', shadowing);
})();
report('and the outer one is untouched', shadowing);

// ── let and const, which are per band ───────────────────────────────────────────────────────────
// Every shipped Rhino takes these. They are here because BLOCK SCOPE is the thing the analyser had to
// learn to model — two sibling blocks declaring the same name are two different names.

section('block scope');
{
    let blockLocal = 'first block';
    report('in the first block', blockLocal);
}
{
    let blockLocal = 'second block';
    report('in the second block', blockLocal);
}

const NEVER_REASSIGNED = 42;
report('a const holds', NEVER_REASSIGNED);

// ── Arrow functions and template literals ───────────────────────────────────────────────────────

section('modern syntax this band accepts');
var double = (n) => n * 2;
report('an arrow function', double(21));
report('a template literal', `two plus two is ${2 + 2}`);

// ── Errors, and that a catch really catches ─────────────────────────────────────────────────────
// The catch PARAMETER is the point as much as the catch is: `e` is a declaration, and an analyser that
// did not know that drew it as an unresolved name in every catch block in every file.

section('errors');
try {
    throw new Error('deliberate');
} catch (e) {
    report('caught', e.message);
} finally {
    report('and finally ran', true);
}

try {
    null.property;
} catch (e) {
    report('a runtime TypeError is catchable', String(e).indexOf('TypeError') >= 0);
}

// ── Java interop — the half that matters on this host ───────────────────────────────────────────
// Three spellings of "reach a Java class", all of which resolve in the editor and all of which must
// run: the bare package chain, `Packages.`, and `Java.type`. If one of them prints and another does
// not, the editor and the runtime have come apart, which is the failure this file exists to catch.

section('java interop');

var list = new java.util.ArrayList();
list.add('one');
list.add('two');
report('a Java list has size', list.size());
report('and its element is a JavaScript string', list.get(0) === 'one');

var joined = java.lang.String.join(', ', list);
report('a static call through the bare chain', joined);

var ArrayListViaType = Java.type('java.util.ArrayList');
var second = new ArrayListViaType();
second.add('made through Java.type');
report('Java.type constructs', second.get(0));

var viaPackages = new Packages.java.util.ArrayList();
viaPackages.add('made through Packages');
report('Packages constructs', viaPackages.get(0));

report('a Java array is indexable', java.util.Arrays.asList('a', 'b').size());

// A NESTED CLASS, in the spelling a script writes. The JVM knows it as `Map$Entry`; the editor offers
// `Map.Entry`, and a runtime that could not take that offered a name it then refused.
var entryType = Java.type('java.util.Map.Entry');
report('a nested class resolves in the source spelling', entryType !== null);

// ── Iterating Java from JavaScript ──────────────────────────────────────────────────────────────
// `for...of` over a Java Iterable goes through Symbol.iterator, which is the one thing a naive member
// membrane silently takes away — it worked unmapped and stopped working the moment a mapping existed.

section('iterating java');
var seen = [];
var iterator = list.iterator();
while (iterator.hasNext()) {
    seen.push(iterator.next());
}
report('walked with an explicit iterator', seen.length);

// ── Output routing ──────────────────────────────────────────────────────────────────────────────
// System.out from INSIDE a Java call the script made lands in the same console, by the output marker
// rather than by anything the script does.

section('output routing');
java.lang.System.out.println('  printed by java.lang.System.out, not by console.log');
console.warn('  console.warn goes to the error stream, as it does in Node');

// ── Where a line came from ──────────────────────────────────────────────────────────────────────
// Every row above carries the line that printed it, read from Rhino's own frame. Double-click one and
// the caret should land on that line of this file.

section('done');
report('lines above should each carry their own origin', true);
console.log('');
console.log('RunTest.js finished.');

// ── Uncomment to exercise the failure paths ─────────────────────────────────────────────────────
//
// A thrown error should print `Error: on purpose (RunTest.js#N)`, link its `at RunTest.js:N` frame,
// AND squiggle the line itself — the runtime's verdict filed beside the analyser's, withdrawn by the
// next run that gets past it.
//
// throw new Error('on purpose');
//
// A spinning loop should be endable with Stop, and no `try` in the script can swallow that:
//
// while (true) { try { } catch (e) { } }
//
// And a blocked read should be endable the same way, through the thread's interrupt rather than
// through the instruction observer:
//
// report('you typed', readLine('type something: '));
