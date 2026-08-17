; glsl/indents.scm
;
; Vendored from nvim-treesitter, Apache License 2.0.
;   https://github.com/nvim-treesitter/nvim-treesitter/blob/master/queries/c/indents.scm
;
; MODIFICATIONS: the upstream files are split across an `; inherits:` chain that this engine does not
; implement -- there is no query-inheritance mechanism here, and adding one to read six files would be
; machinery for a feature nobody asked for. The chain is therefore RESOLVED AT VENDORING TIME by
; concatenating the inherited files ahead of the language's own, in inheritance order, which is exactly
; what nvim-treesitter's loader does at runtime. Nothing else is changed: no pattern is edited, removed
; or reordered. Where this engine needs a deviation it is applied at LOAD time, in Queries.java, so the
; deviation stays one reviewable table rather than a fork of somebody else's query.
;
; See THIRD-PARTY.md.

; --- from queries/c/indents.scm ---
[
  (compound_statement)
  (field_declaration_list)
  (case_statement)
  (enumerator_list)
  (compound_literal_expression)
  (initializer_list)
  (init_declarator)
] @indent.begin

; With current indent logic, if we capture expression_statement with @indent.begin
; It will be affected by _parent_ node with error subnodes deep down the tree
; So narrow indent capture to check for error inside expression statement only,
(expression_statement
  (_) @indent.begin
  ";" @indent.end)

(ERROR
  "for"
  "(" @indent.begin
  ";"
  ";"
  ")" @indent.end)

((for_statement
  body: (_) @_body) @indent.begin
  (#not-kind-eq? @_body "compound_statement"))

(while_statement
  condition: (_) @indent.begin)

((while_statement
  body: (_) @_body) @indent.begin
  (#not-kind-eq? @_body "compound_statement"))

((if_statement)
  .
  (ERROR
    "else" @indent.begin))

(if_statement
  condition: (_) @indent.begin)

; Supports if without braces (but not both if-else without braces)
(if_statement
  consequence: (_
    ";" @indent.end) @_consequence
  (#not-kind-eq? @_consequence "compound_statement")
  alternative: (else_clause
    "else" @indent.branch
    [
      (if_statement
        (compound_statement) @indent.dedent)? @indent.dedent
      (compound_statement)? @indent.dedent
      (_)? @indent.dedent
    ])?) @indent.begin

(else_clause
  (_
    .
    "{" @indent.branch))

(compound_statement
  "}" @indent.end)

[
  ")"
  "}"
  (statement_identifier)
] @indent.branch

[
  "#define"
  "#ifdef"
  "#ifndef"
  "#elif"
  "#if"
  "#else"
  "#endif"
] @indent.zero

[
  (preproc_arg)
  (string_literal)
] @indent.ignore

((ERROR
  (parameter_declaration)) @indent.align
  (#set! indent.open_delimiter "(")
  (#set! indent.close_delimiter ")"))

([
  (argument_list)
  (parameter_list)
] @indent.align
  (#set! indent.open_delimiter "(")
  (#set! indent.close_delimiter ")"))

(comment) @indent.auto
