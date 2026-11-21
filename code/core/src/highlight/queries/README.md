# Vendored highlight queries

- `javascript.scm`, `javascript-jsx.scm`: highlights.scm and highlights-jsx.scm from tree-sitter-javascript 0.25.0 (MIT).
- `c.scm`: highlights.scm from tree-sitter-c 0.24.2 (MIT).
- `kotlin.scm`: written for this repository (tree-sitter-kotlin-ng ships no query).

TypeScript and C++ queries build on the JavaScript and C ones. The text is vendored so the phone build, which has no
JavaScript or C parser, does not link them just for their queries.
