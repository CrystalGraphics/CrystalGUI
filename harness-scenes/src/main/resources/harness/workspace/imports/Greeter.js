// Middle of the chain — a module that itself imports a module.
//
// This is the file that decided how modules are loaded. Rhino ships CommonJS and binding our `import`
// statement to it would have been the natural thing, except that `Require` builds each module's scope
// internally with no hook to inject a binding into — so a module with an import of its own could not be
// served at all. Which is exactly this file, and it is the ordinary shape rather than an edge case.
//
// No `exports.` here either: every top-level declaration is exported. Note what is NOT exported —
// `Formatter` is a name brought IN by the import above, and exporting it again is a different statement
// that nobody wrote.
//
// Things worth trying:
//   * Type `Formatter.` below — the popup should offer `shout` and `quiet` with their parameters, read
//     out of the other file.
//   * Ctrl+B on `Formatter` opens it; Ctrl+B on `shout` lands on the function itself.
//   * Delete the import line and `Formatter` should go unresolved — then undo.
//   * Add a `function helper()` and watch it appear behind `Greeter.` in App.js.

import util.Formatter;

function greet(who) {
    return Formatter.shout('hello, ' + who);
}

function murmur(who) {
    return Formatter.quiet('hello, ' + who);
}

// A plain value, so the popup has something that is not a function in it.
var defaultName = 'world';
