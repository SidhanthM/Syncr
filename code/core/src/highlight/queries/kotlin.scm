; Written for Syncr against tree-sitter-kotlin-ng 1.1.0 (the crate ships no highlights query).
; Original work, same licence as this repository.

(identifier) @variable

[
  "package" "import" "class" "interface" "object" "fun" "val" "var" "typealias" "constructor"
  "init" "this" "super" "return" "if" "else" "when" "for" "while" "do" "try" "catch" "finally"
  "throw" "in" "is" "as" "as?" "by" "where" "get" "set" "companion" "enum"
  "data" "sealed" "open" "abstract" "final" "override" "private" "public" "protected" "internal"
  "inner" "lateinit" "const" "suspend" "inline" "operator" "infix" "tailrec" "vararg"
  "noinline" "crossinline" "out" "annotation" "external" "expect" "actual"
] @keyword

(class_modifier) @keyword
(function_modifier) @keyword
(member_modifier) @keyword
(visibility_modifier) @keyword
(property_modifier) @keyword
(inheritance_modifier) @keyword
(parameter_modifier) @keyword
(platform_modifier) @keyword

(user_type (identifier) @type)
(class_declaration (identifier) @type)
(object_declaration (identifier) @type)
(type_alias (identifier) @type)
(type_parameter (identifier) @type)

(function_declaration (identifier) @function)
(call_expression (identifier) @function)
(call_expression (navigation_expression (identifier) @function .))
(constructor_invocation (user_type (identifier) @function))
(parameter (identifier) @variable.parameter)
(class_parameter (identifier) @variable.parameter)
(annotation (user_type (identifier) @attribute))
(annotation (constructor_invocation (user_type (identifier) @attribute)))
(enum_entry (identifier) @constant)

(string_literal) @string
(multiline_string_literal) @string
(character_literal) @string
(escape_sequence) @string
(number_literal) @number
(float_literal) @number

(line_comment) @comment
(block_comment) @comment

[ "+" "-" "*" "/" "%" "=" "==" "!=" "===" "!==" "<" ">" "<=" ">=" "&&" "||" "!" "?:" "->" ".." "..<" "+=" "-=" "*=" "/=" "++" "--" "?." "::" ] @operator
[ "(" ")" "[" "]" "{" "}" "," "." ":" ";" ] @punctuation

; This grammar leaves these as plain identifiers.
((identifier) @constant (#any-of? @constant "true" "false" "null"))
((identifier) @keyword (#any-of? @keyword "break" "continue"))
