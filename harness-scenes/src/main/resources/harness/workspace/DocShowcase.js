/**
 * DocShowcase.js — every JSDoc construct, and the Java bridge underneath it.
 *
 * `DocShowcase.java` is the same idea for javadoc. This file has two jobs, and they fail differently:
 *
 *   1. JSDOC ITSELF. §6.1 of the javadoc plan: JavaScript has a renderer, a model, a link gesture and
 *      `Resolver.describe`, all language-neutral — and no EMITTER. So today a documented function
 *      hovers as a declaration with nothing under it. Everything in sections 1-6 is what that emitter
 *      has to read.
 *
 *   2. THE BRIDGE. A Java receiver's documentation comes from the JAVA engine through
 *      `InteropResolver`, so hovering `list.add` in this file should show the JDK's own comment —
 *      the same text a .java file shows, quoted from src.zip where it is attached. Section 7 is that,
 *      and it works today.
 *
 * JSDoc descriptions are MARKDOWN, not HTML, which is the whole reason the emitter is a second parser
 * rather than a second renderer: `MarkupParser` reads HTML and stays as it is.
 *
 * Sections marked PARITY are legal JSDoc that nothing here reads yet. They are present so the gap is
 * visible — a construct nobody wrote is a construct nobody notices is missing.
 */

import java.util.ArrayList;
import java.util.HashMap;

// ── 1. The tags that are read today ─────────────────────────────────────────────────────────────
//
// `RhinoJsDoc` is "a tag grammar, not a documentation renderer" by its own first line, and it reads
// exactly four things: param (and its aliases), returns, type, deprecated. Everything it reads is a
// TYPE, used for resolution — none of the prose reaches a popup.

/**
 * Joins a name and a count into a label.
 *
 * @param {string} name the name to show
 * @param {number} count how many there are
 * @returns {string} the label
 */
function summarise(name, count) {
    return name + " (" + count + ")";
}

/**
 * The same tags under their older spellings, which are equally legal.
 *
 * @arg {string} first `@arg` is an alias for `@param`
 * @argument {string} second and so is `@argument`
 * @return {boolean} `@return` is an alias for `@returns`
 */
function aliases(first, second) {
    return first === second;
}

/** @type {number} A declared type on a variable, which resolution reads. */
var declaredCount = 0;

/**
 * @deprecated Use {@link summarise} instead.
 * @param {string} text anything
 * @returns {string} the same thing
 */
function oldSummarise(text) {
    return text;
}

// ── 2. Type expressions, which are most of JSDoc ────────────────────────────────────────────────
//
// The type between the braces is its own small language. A resolver that reads only bare names gets
// the first of these and none of the rest.

/**
 * Every type expression shape in one signature.
 *
 * @param {string} plain a bare name
 * @param {string|number} union either of two
 * @param {?string} nullable may be null
 * @param {!Object} nonNull may not be null
 * @param {string=} optionalSuffix optional, in the trailing-equals spelling
 * @param {number} [optionalBracket] optional, in the bracket spelling
 * @param {number} [withDefault=10] optional with a default
 * @param {Array<string>} generic an array of strings
 * @param {Object.<string, number>} record the older map spelling
 * @param {{name: string, rows: number}} inline an inline object type
 * @param {function(string, number): boolean} callback a function type
 * @param {*} anything the any type
 * @param {...number} rest a rest parameter
 * @returns {Promise<Array<string>>} a nested generic
 */
function everyType(plain, union, nullable, nonNull, optionalSuffix, optionalBracket,
                   withDefault, generic, record, inline, callback, anything, ...rest) {
    return Promise.resolve([plain]);
}

/**
 * A destructured parameter, documented member by member — the dotted spelling.
 *
 * @param {Object} options the options object
 * @param {string} options.name what to call it
 * @param {number} [options.retries=3] how many times to try
 * @param {Object} options.nested a nested object
 * @param {boolean} options.nested.enabled whether it is on
 */
function withOptions(options) {
    return options.name;
}

// ── 3. Markdown in the description ──────────────────────────────────────────────────────────────

/**
 * A description with **bold**, _italic_, `inline code`, ~~strikethrough~~ and a [link](https://example.com).
 *
 * # A heading
 * ## A second-level heading
 * ### A third
 *
 * A paragraph after the headings, with a hard break at the end of this line
 * and the continuation on the next.
 *
 * - a bullet
 * - another bullet
 *   - nested
 * * an asterisk bullet, which is the same thing
 *
 * 1. an ordered item
 * 2. a second
 *
 * > A blockquote, which markdown spells with an angle bracket.
 *
 * ```js
 * const list = new java.util.ArrayList();
 * list.add("fenced code, with a language");
 * ```
 *
 *     an indented code block, which is the older spelling
 *
 * | Column | Meaning |
 * | ------ | ------- |
 * | one    | the first |
 * | two    | the second |
 *
 * ---
 *
 * Inline HTML is also legal inside markdown: <b>bold</b> and <em>emphasis</em>.
 *
 * An image is its alt text, and must not be read as the link it looks like: ![a diagram](diagram.png)
 * beside a real [link](https://example.com), so the two are visibly different things.
 *
 * @returns {void}
 */
function markdownDescription() {
}

// ── 4. Inline tags ──────────────────────────────────────────────────────────────────────────────

/**
 * References written inline: {@link summarise} names a function in this file, {@link everyType|with a
 * label} uses the pipe spelling, {@link https://example.com|an external link} points outside, and
 * {@linkcode summarise} renders in a code face. {@tutorial getting-started} points at a tutorial.
 *
 * @returns {void}
 */
function inlineTags() {
}

// ── 5. Structural tags ──────────────────────────────────────────────────────────────────────────

/**
 * A shape, described once and referred to by name.
 *
 * @typedef {Object} Summary
 * @property {string} name what the row is called
 * @property {number} rows how many rows it holds
 * @property {boolean} [complete] whether it finished
 */

/**
 * A function type, described the same way.
 *
 * @callback RowVisitor
 * @param {string} row the row
 * @param {number} index where it was
 * @returns {boolean} false to stop
 */

/**
 * Takes the shapes above by name.
 *
 * @param {Summary} summary a value of the typedef'd shape
 * @param {RowVisitor} visit called once per row
 * @returns {Summary} the same shape back
 */
function useTypedef(summary, visit) {
    return summary;
}

/**
 * A class, with the tags a class carries.
 *
 * @class
 * @classdesc The longer description a class gets, as opposed to the constructor's.
 * @param {string} name what to call it
 * @property {string} name the name it was given
 * @since 1.0
 * @author nobody
 * @version 2.1
 * @see summarise
 * @example
 * const t = new Table("rows");
 * console.log(t.describe());
 */
function Table(name) {
    /** @type {string} */
    this.name = name;
}

/**
 * A method on the prototype.
 *
 * @memberof Table
 * @instance
 * @returns {string} a description of this table
 * @throws {TypeError} if the name was never set
 */
Table.prototype.describe = function () {
    if (typeof this.name !== "string") throw new TypeError("name");
    return summarise(this.name, 0);
};

/**
 * A subclass, in the spelling this engine can parse.
 *
 * NOT `class WideTable extends Table` — Rhino 1.9.1 refuses ES6 classes outright ("classes are not
 * supported by Rhino 1.9.1"), and a parse error poisons the whole file rather than its own line, so a
 * single `class` here would take every section below it down with it. The ES6 forms are listed as
 * comments in the parity section instead, where they cost nothing.
 *
 * @augments Table
 * @param {string} name what to call it
 * @param {number} width how wide
 */
function WideTable(name, width) {
    Table.call(this, name);
    /** @type {number} */
    this.width = width;
}

WideTable.prototype = Object.create(Table.prototype);
WideTable.prototype.constructor = WideTable;

/**
 * Overrides the parent's, and says so.
 *
 * @override
 * @returns {string} a wider description
 */
WideTable.prototype.describe = function () {
    return Table.prototype.describe.call(this) + " x" + this.width;
};

/**
 * @readonly
 * @returns {number} the width, which nothing may set
 */
WideTable.prototype.size = function () {
    return this.width;
};

/**
 * A static factory, which is a property on the constructor rather than on the prototype.
 *
 * @static
 * @param {string} name what to call it
 * @returns {WideTable} a default-sized table
 */
WideTable.of = function (name) {
    return new WideTable(name, 80);
};

// ── 6. PARITY — legal JSDoc that nothing here reads yet ─────────────────────────────────────────
//
// Every tag below is standard and is what a production renderer (jsdoc itself, or TypeScript's
// JSDoc support, or IntelliJ) understands. None of it is read today.

/**
 * @abstract
 * @access protected
 * @alias RealName
 * @async
 * @augments Table
 * @borrows summarise as describe
 * @constructs
 * @copyright nobody, 2026
 * @default 42
 * @desc `@desc` is the short spelling of `@description`
 * @enum {number}
 * @event Table#rowAdded
 * @exports module:tables
 * @external Promise
 * @file A file-level comment, which describes the whole module rather than a declaration.
 * @fires Table#rowAdded
 * @generator
 * @global
 * @hideconstructor
 * @ignore
 * @implements {RowVisitor}
 * @inheritdoc
 * @inner
 * @interface
 * @kind function
 * @lends Table.prototype
 * @license MIT
 * @listens Table#rowAdded
 * @member {number}
 * @mixes Serializable
 * @mixin
 * @module tables
 * @name explicitName
 * @namespace tables
 * @package
 * @private
 * @protected
 * @public
 * @requires module:other
 * @summary A one-line summary, said explicitly rather than guessed from the first sentence.
 * @this Table
 * @todo write the emitter that reads all of this
 * @tutorial getting-started
 * @variation 2
 * @yields {string} for a generator
 */
function parityTags() {
}

/**
 * Comment shapes, rather than comment content.
 *
 * The three below are all legal and all reach a reader differently.
 */
function parityShapes() {
}

/** A one-line comment, with the text between both delimiters. */
function parityOneLine() {
}

/**
   A comment with no leading asterisks, which is legal and which the whitespace stripping has to
   handle: the common indent is removed, not a literal "space star space".
 */
function parityNoStars() {
}

/*
 * PARITY, SYNTAX RATHER THAN TAGS — and these are comments on purpose.
 *
 * Rhino 1.9.1 is the newest band this ships against and it refuses the forms below. They are written
 * here rather than run because a parse error is not local: Rhino reports "classes are not supported by
 * Rhino 1.9.1" and then loses the rest of the file, so one live `class` would take every section under
 * it down with it. That is worth knowing before writing a fixture, and it is why the subclass above is
 * in prototype form.
 *
 * Refused outright:
 *
 *     class Table { constructor(name) { this.name = name; } get size() { return 0; } }
 *     class Wide extends Table { describe() { return super.describe(); } }
 *
 * Accepted, and worth keeping an eye on as the band moves: rest parameters (`...rest`, used above),
 * `const` and `let`, template literals, arrow functions, destructuring, `for...of`, default parameter
 * values, shorthand properties, computed keys, generators and `async`/`await` each arrived in a
 * different Rhino release. `JsCompatibilityBandTest` is where a refusal is pinned once it is known.
 *
 * A JSDoc emitter reads COMMENTS, so none of this changes what it has to parse — a `@param` on a class
 * method is the same `@param`. It changes what a fixture may contain.
 */

/*
 * NOT a doc comment — one asterisk, so a reader must ignore it entirely rather than treat it as the
 * documentation for the function below.
 */
function parityNotADocComment() {
}

// ── 7. THE BRIDGE — Java documentation, reached from JavaScript ─────────────────────────────────
//
// This half works today. Hover any Java member below: the text comes from the JAVA engine through
// `InteropResolver`, so it is the same comment a .java file would show for the same member —
// quoted from src.zip when it is attached, and assembled from the binding when it is not.

var names = new ArrayList();
names.add("first");
names.add("second");

// Hover `add`, `size`, `isEmpty`, `stream` — each is the JDK's own comment, not ours.
var count = names.size();
var empty = names.isEmpty();

var index = new HashMap();
index.put("k", names);

// A type reached through Java.type rather than through an import.
var Charset = Java.type("java.nio.charset.Charset");
var utf8 = Charset.forName("UTF-8");

// A static member on a type the popup has to resolve without an instance.
var maxInt = java.lang.Integer.MAX_VALUE;

console.log(summarise("rows", count), empty, utf8, maxInt, index.size());
