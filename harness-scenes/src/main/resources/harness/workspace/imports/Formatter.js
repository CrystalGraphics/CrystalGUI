// Bottom of the chain — imports nothing, and exports without saying so.
//
// There is no `exports.` anywhere in this file. A module that says nothing exports every TOP-LEVEL
// declaration, which is the default because this language already writes its own `import util.Formatter;`
// rather than ES or CommonJS syntax — so requiring Node's `exports.name = …` on the other side would
// leave you writing a Java-shaped import against a Node-shaped export.
//
// It is also better for the editor, and that is not a side effect. `function shout(text)` is a
// DECLARATION: it carries a kind, its parameter names and a precise span, so a hover renders
// `function shout(text)` and Ctrl+B lands on the word. `exports.shout = function (text)` is an assignment
// of an anonymous function to a property — statically that offers a name and very little else.
//
// Part of the M15 S6/S7 fixture. Three files, mirroring the Java one next door:
//
//   * App.js             imports util.Greeter and runs it
//   * util/Greeter.js    imports util.Formatter — a module that itself imports a module
//   * util/Formatter.js  this one, the leaf
//
// Things worth trying here:
//   * Add `function whisper(t) { return t.toLowerCase(); }` — it should appear behind `Formatter.` in
//     Greeter.js on the next analysis, with no export line to write.
//   * Rename `shout` and watch Greeter.js go red, with NO SAVE.
//   * Want something PRIVATE? Add one `exports.shout = shout;` line and the rest of the file stops being
//     exported — an explicit export is taken at its word, which is the only way to hide a helper.

function shout(text) {
    if (!text) {
        return '';
    }
    return String(text).trim().toUpperCase() + '!';
}

function quiet(text) {
    return text ? String(text).trim().toLowerCase() : '';
}
