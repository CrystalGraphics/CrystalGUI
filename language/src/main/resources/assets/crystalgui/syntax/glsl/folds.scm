; glsl/folds.scm
;
; Vendored from nvim-treesitter, Apache License 2.0.
;   https://github.com/nvim-treesitter/nvim-treesitter/blob/master/queries/c/folds.scm
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

; --- from queries/c/folds.scm ---
[
  (for_statement)
  (if_statement)
  (while_statement)
  (do_statement)
  (switch_statement)
  (case_statement)
  (function_definition)
  (struct_specifier)
  (enum_specifier)
  (comment)
  (preproc_if)
  (preproc_elif)
  (preproc_else)
  (preproc_ifdef)
  (preproc_function_def)
  (initializer_list)
  (gnu_asm_expression)
  (preproc_include)+
] @fold

(compound_statement
  (compound_statement) @fold)
