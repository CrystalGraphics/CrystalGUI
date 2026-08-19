; javascript/folds.scm
;
; Vendored from nvim-treesitter, Apache License 2.0.
;   https://github.com/nvim-treesitter/nvim-treesitter/blob/master/queries/ecma/folds.scm, jsx/folds.scm
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

; --- from queries/ecma/folds.scm ---
[
  (arguments)
  (for_in_statement)
  (for_statement)
  (while_statement)
  (arrow_function)
  (function_expression)
  (function_declaration)
  (class_declaration)
  (method_definition)
  (do_statement)
  (with_statement)
  (switch_statement)
  (switch_case)
  (switch_default)
  (import_statement)+
  (if_statement)
  (try_statement)
  (catch_clause)
  (array)
  (object)
  (generator_function)
  (generator_function_declaration)
] @fold

; --- from queries/jsx/folds.scm ---
(jsx_element) @fold
