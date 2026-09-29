// Top of the chain — the JavaScript file to open first, and the one to Run.
//
// The twin of `com/example/Main.java` next door. Same three-file shape, same source-root rule, same
// no-save promise — deliberately, so the two engines can be compared on one workspace.
//
// ── What should be true, in rough order of how much it would hurt to lose ───────────────────────────
//
//  1. NO ERRORS. `Greeter` resolves through the import, and so does the `util.Formatter` that Greeter
//     itself imports two levels down.
//
//  2. `Greeter.` COMPLETES with `greet`, `murmur` and `defaultName` — read statically out of another
//     file, without running anything, and a function reads as `function greet(who)` with its parameters.
//     Note that NEITHER file writes `exports.` anywhere: a module that says nothing exports every
//     top-level declaration, and an explicit `exports.x = ...` is the way to keep the rest private.
//
//  3. HOVER on `Greeter` says MODULE, and names `util.Greeter` as where the value came from. Not
//     "class", which is what it said while the name was going through the Java tier.
//
//  4. Ctrl+B on `Greeter` opens `util/Greeter.js` in the workspace — not an empty viewer.
//
//  5. RUN IT. The console should print the shouted and the quiet form.
//
//  6. NOW EDIT WITHOUT SAVING. Change `shout` in `util/Formatter.js` to return something else, do NOT
//     save, and Run again — the output changes. That is the whole point of reading modules through the
//     project index rather than off disk, and it is two files away from the one being run.
//
//  7. A CYCLE DOES NOT HANG. Add `import App;` to the top of `util/Greeter.js` and run again: it should
//     complete rather than recurse. (Then take it out — it is legal, not advisable.)
//
// And one that should NOT happen: nothing here should offer a member of `Greeter` that the file does not
// actually export. Exports built dynamically are deliberately not guessed at — see `JsExports`.

import util.Greeter;

var name = Greeter.defaultName;

console.log(Greeter.greet(name));
console.log(Greeter.murmur(name));
