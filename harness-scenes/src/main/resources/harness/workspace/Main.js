/**
 * Main.js — the JavaScript twin of Main.java, and the file M10 is traced in.
 *
 * It is not a program anybody should run. Every section exists because some part of the JavaScript
 * stack is supposed to do something visible to it, and each one says what that is — so a section that
 * looks like the section above it, and should not, is the bug.
 *
 * Sections are added as milestones land. What is here now:
 *
 *   M10.1  colours, from the tree-sitter grammar, and the EDITING affordances that arrived with
 *          Language.JAVASCRIPT: toggle-comment, auto-closing brackets, the backtick pair, and the
 *          `.` that opens a completion list.
 *   M10.2  Rhino behind the file. The status bar names it; a syntax error is a real squiggle from a
 *          real parser, on the offset the parser reported; Run recognises the file and refuses one
 *          that does not compile.
 *   M10.3  ONE diagnostic per problem rather than Rhino's five, with this engine's refusals named as
 *          themselves — see the bottom of this file — and a warning on a local nothing uses.
 *   M10.4  every name drawn as what the scopes say it is: parameter, local, const, reassigned,
 *          captured, unresolved. None of these is visible to a grammar.
 *   M10.5  Shift+F10 RUNS IT. Every console.log lands in the Run panel stamped with the line that
 *          printed it; Stop ends a spinning loop; a thrown error squiggles its own line and its
 *          stack frames are links. See the Output section, and the runtime-error line under it.
 *
 *   M10.6  RESOLUTION. Hover a name and it says what it is, and which of four tiers said so:
 *          what the last run left it as, what JSDoc declared, what the initializer implies, or
 *          just how it was declared. A Java receiver's members come from the JAVA engine, so they
 *          are the same list a .java file would show.
 *
 *   M10.7  COMPLETION. `.` after a Java object lists the Java engine's members with their real
 *          signatures; inside `Java.type('` the classpath's class names; in open code what is in
 *          scope, then the globals, then only the keywords THIS engine accepts. After a run, `.`
 *          on a live object lists what it actually has.
 *
 *   M10.8  QUICK DOCUMENTATION. Mod+Q on any name shows its declaration the way the engine would
 *          write it — `function summarise(items, rate)`, `const MAX_RETRIES: number` — coloured by
 *          the same scheme as the editor. On a Java member it shows the JDK's OWN line, quoted from
 *          src.zip: `public boolean add(E e)`, parameter name included.
 *
 *   M10.9  QUICK FIXES AND INTENTIONS. Alt+Enter on a warning or a caret: remove an unused local,
 *          "did you mean" over what is in scope, declare a free name, `var`->`let`/`const`, `==`->`===`,
 *          either `Java.type` spelling into the other, a concatenation into a template literal, and
 *          surround with try/catch. Each offered only where it would still RUN on this band.
 *
 *   M10.10 THE SANDBOX. One allowlist, obeyed by four surfaces at once: a refused class is absent from
 *          the member list, absent from the popup, absent from the type index, and throws if a script
 *          names it anyway. Nothing is restricted in the harness — the point is that it is one switch.
 *
 *   M10.11 READABLE NAMES. On an obfuscated deployment a method is `func_147439_a` and a script writes
 *          `getBlock`; the call is translated at lookup and the member list is renamed on the way in.
 *          Both directions or neither, or the editor teaches you to write names the runtime refuses.
 *
 * See RunTest.js beside this file for the other half — a script written to be RUN rather than read,
 * whose console output is a transcript of what this engine actually executes.
 */

'use strict';

// ── Constants and the literal forms ─────────────────────────────────────────────────────────────
// These are drawn as CONSTANTS, distinct from the locals below — a distinction no grammar can make,
// because nothing in the shape of a name says whether it was declared with `const`.

const MAX_RETRIES = 5;
const TIMEOUT_MS = 2500;
const GOLDEN = 1.618033988749;
const HEX = 0xdeadbeef;
const NOTHING = null;
const MISSING = undefined;
const PATTERN = /^cg-([a-z]+)-(\d+)$/;

// The backtick pair is the one piece of punctuation JavaScript has and Java does not. Type one and
// the editor writes its partner; the lexer knows it opens a string, which is what stops the quote
// inside it from painting the rest of the line.
const GREETING = `hello, ${'world'} — he said "hi" and it stayed inside the template`;

// ── Functions, parameters and captures ──────────────────────────────────────────────────────────
// Four different colours here, and every one of them needs resolved scopes: `items` and `rate` are
// PARAMETERS, `total` is a local that is REASSIGNED (`+=`), `seen` is a local CAPTURED by the closure
// inside it, and `applyRate` is a function. Look at `seen` on its declaration line and again inside
// `report` — the second one is drawn differently, because that is where it escapes.

function summarise(items, rate) {
    let total = 0;
    const seen = [];
    for (var i = 0; i < items.length; i++) {
        total += items[i] * rate;
        seen.push(items[i]);
    }
    const report = function () {
        // `seen` is captured here, and `total` is not — that difference is the whole point of the
        // colour, and it is invisible to anything that has not resolved the scopes.
        return seen.length;
    };
    return { total: total, count: report() };
}

// A local nothing uses is a warning — the only check a language with no compiler offers before the
// script is run. Delete the `return` below and the warning moves to `answer` instead, because then
// nothing reads it either.
function unusedExample() {
    var neverRead = 'this one is warned about';
    var answer = 42;
    return answer;
}

// ...but a PARAMETER is not, however unused: a callback's signature belongs to whoever calls it, and
// `(err, data)` that ignores `err` is idiomatic rather than wrong. Nor is a TOP-LEVEL name, because
// a script's top level is its surface — nothing in this file uses `GREETING` either.
function callbackShape(err, data) {
    return data;
}

const applyRate = (value, rate) => value * rate;

// ── Objects, and the dot that opens a list ──────────────────────────────────────────────────────
// Type a `.` after `settings` and a completion list opens. At M10.2 it opens EMPTY, which is the
// honest state: the trigger is wired and the provider is not. At M10.7 it lists these three.

const settings = {
    retries: MAX_RETRIES,
    timeout: TIMEOUT_MS,
    label: 'scratch',
};

function describe(config) {
    return config.label + ' (' + config.retries + ' retries)';
}

// ── Java interop — the half of this language that matters here ──────────────────────────────────
// Resolved THROUGH THE JAVA ENGINE. Hover `list` and it is a java.util.ArrayList; hover `add` and you
// get the member the Java popup would show, with the signature the Java analyser quotes. `Java.type`
// and the bare `java.util.…` spelling both resolve, and each says whether it is the class (statics) or
// an instance. `Packages.java.util.ArrayList` is the third spelling and resolves to the same class.
//
// One thing is inherently less precise here than in Java, and it is the language's fault rather than
// the engine's: JavaScript has no diamond, so this is a RAW ArrayList and `list.get(0)` is an Object.

function useJava() {
    var list = new java.util.ArrayList();
    list.add('one');
    list.add('two');
    var joined = java.lang.String.join(', ', list);
    var Files = Java.type('java.util.Collections');
    return joined + Files.emptyList().size();
}

// Type a `.` after `list` above and the list is the JAVA engine's: `add`, `get`, `size` with their
// real parameter types. Type inside the quotes of `Java.type('` and the classpath's class names are
// offered — accepting one writes the whole qualified name and edits nothing else, because JavaScript
// has no import to add. And `Java.type('…')` is the CLASS, so its statics are offered and its
// instance members are not; `new java.util.ArrayList()` is the other way round.

// ── What JSDoc buys, which is the only place JavaScript writes a type down ──────────────────────
// Hover `applyDiscount` and its owner band reads "— from JSDoc": the tier that answered is stated,
// because a JavaScript answer's provenance is information a Java answer never had to carry. Hover
// `rate` inside it and it is a number because the tag said so, not because anything could infer it.

/**
 * Applies a discount, for the sake of having something documented.
 *
 * Mod+Q here reads `function applyDiscount(price: number, rate: number): number` — the types come
 * from the tags below and nowhere else, since JavaScript declares none. An undocumented parameter is
 * shown as a bare name rather than `?`, because a column of question marks is what most JavaScript
 * would render and it says nothing the absence does not.
 *
 * @param {number} price the amount before the discount
 * @param {number} rate a fraction between 0 and 1
 * @returns {number}
 */
function applyDiscount(price, rate) {
    return price - price * rate;
}

/** @deprecated use applyDiscount */
function oldDiscount(price) {
    return price * 0.9;
}

// ── Output ──────────────────────────────────────────────────────────────────────────────────────
// Shift+F10. `console.log`, `print` and `console.error` go to the Run panel with the level known and
// the LINE that printed them in the stamp — double-click a row and the caret lands on it. Values are
// formatted the way every JavaScript console formats them: `[ 1, 2, 3 ]`, `{ a: 1, b: 'x' }`,
// `[Function: name]`. `readLine('prompt')` blocks on the panel's input row; Stop ends it there too.
// `System.out` inside a Java call from the script lands in the same console, by the same rule.

function main() {
    var result = summarise([1, 2, 3], 1.5);
    console.log('total: ' + result.total + ' over ' + result.count + ' items');
    console.log(describe(settings));
    console.log(GREETING);
    console.log(settings, [MAX_RETRIES, TIMEOUT_MS], applyRate);
    console.log(useJava());
    console.warn('a warning goes to the error stream, as it does in Node');
    return result;
}

main();

// ── After a run, the editor knows more than the source says ─────────────────────────────────────
// This is the tier no static analysis can reach. `made` is assigned from a call nothing can follow,
// so before a run its type is unknown — press Shift+F10 and hover it again: it is whatever it turned
// out to be, and the owner band says "from last run". The same is true of any global the run left
// behind, including one this file never declares.
//
// COMPLETION USES IT TOO, and this is the clearest demonstration of what an engine buys: run the
// file, then type `settings.` — the list is `retries`, `timeout` and `label`, the object's ACTUAL
// properties, with what every object inherits (`toString`, `hasOwnProperty`) sorted underneath and
// marked as inherited. Before the run there is nothing to know.

var made = useJava();

// ── A runtime error, and where it lands ─────────────────────────────────────────────────────────
// Uncomment the next line and run again: the console prints `Error: not today (Main.js#N)` with the
// script frame `at Main.js:N` as a link, AND the line itself gets a red squiggle -- the runtime's
// verdict filed beside the analyser's, tracked through edits, withdrawn by the next run that gets
// past it. Then uncomment the spinner and press Stop.
//
// throw new Error('not today');
//
// while (true) { }

// ── Uncomment to see the engine disagree with the grammar ───────────────────────────────────────
//
// The grammar parses modern JavaScript perfectly and colours all of this. The ENGINE is what refuses
// it, and refuses different things per band — measured, not guessed: `class`, `import`/`export` and
// `async`/`await` are refused by every band we ship, while `?.` and `??` work on Java 11+ and not on
// Java 8. That gap is the whole reason parse diagnostics come from Rhino rather than from tree-sitter.
//
// Uncomment `class Point` and you get exactly ONE error, saying "'class': classes are not supported by
// Rhino <version>". Rhino itself reports FIVE — one real and four from its parser failing to
// re-synchronise afterwards — and its wording for the real one is "identifier is a reserved word",
// which is accurate about its lexer and useless to somebody who did not think they were declaring an
// identifier. `async` is the interesting one: it lexes as an ordinary name, so the engine's complaint
// lands on the `function` after it and mentions `async` nowhere at all.
//
// class Point {
//     constructor(x, y) { this.x = x; this.y = y; }
// }
//
// import { thing } from './lib.js';
//
// async function fetchThing() { return await thing(); }
//
// const maybe = settings?.label ?? 'none';
