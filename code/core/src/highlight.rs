//! Syntax colouring for fenced code blocks: one tree-sitter highlighter shared by the Mac and the phone.
//!
//! `highlight_code` returns sorted, non-overlapping spans with **UTF-16** offsets (what NSString and
//! Kotlin String index by); uncovered text is plain. Each thread keeps one `Highlighter` and builds a
//! language's configuration on first use, so a keystroke never pays for compiling a query twice.
use std::cell::RefCell;
use std::collections::HashMap;

use tree_sitter::Language;
use tree_sitter_highlight::{HighlightConfiguration, HighlightEvent, Highlighter};

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum CodeToken {
    Keyword,
    String,
    Comment,
    Number,
    Function,
    Type,
    Constant,
    Variable,
    Operator,
    Punctuation,
    Tag,
    Attribute,
}

/// A coloured run of `code`, in UTF-16 code units.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Record)]
pub struct CodeSpan {
    pub start: u32,
    pub end: u32,
    pub token: CodeToken,
}

/// Capture-name roots we colour. A capture matches the longest listed prefix (`function.method` finds
/// `function`); roots not listed here are never emitted. The extras fold into a token below.
const CAPTURES: &[(&str, CodeToken)] = &[
    ("keyword", CodeToken::Keyword),
    ("string", CodeToken::String),
    ("escape", CodeToken::String),
    ("character", CodeToken::String),
    ("comment", CodeToken::Comment),
    ("number", CodeToken::Number),
    ("function", CodeToken::Function),
    ("type", CodeToken::Type),
    ("constructor", CodeToken::Type),
    ("constant", CodeToken::Constant),
    ("boolean", CodeToken::Constant),
    ("variable", CodeToken::Variable),
    ("operator", CodeToken::Operator),
    ("punctuation", CodeToken::Punctuation),
    ("tag", CodeToken::Tag),
    ("attribute", CodeToken::Attribute),
    ("property", CodeToken::Attribute),
];

#[cfg(any(feature = "lang-javascript", feature = "lang-typescript"))]
const JS_QUERY: &str = include_str!("highlight/queries/javascript.scm");
#[cfg(any(feature = "lang-javascript", feature = "lang-typescript"))]
const JSX_QUERY: &str = include_str!("highlight/queries/javascript-jsx.scm");
#[cfg(any(feature = "lang-c", feature = "lang-cpp"))]
const C_QUERY: &str = include_str!("highlight/queries/c.scm");
#[cfg(feature = "lang-kotlin")]
const KOTLIN_QUERY: &str = include_str!("highlight/queries/kotlin.scm");

/// The canonical names `highlight_code` accepts (aliases and `tsx` resolve to these); only the languages compiled in.
const LANGUAGES: &[&str] = &[
    "python",
    #[cfg(feature = "lang-javascript")]
    "javascript",
    #[cfg(feature = "lang-typescript")]
    "typescript",
    #[cfg(feature = "lang-rust")]
    "rust",
    #[cfg(feature = "lang-swift")]
    "swift",
    #[cfg(feature = "lang-kotlin")]
    "kotlin",
    #[cfg(feature = "lang-json")]
    "json",
    #[cfg(feature = "lang-bash")]
    "bash",
    #[cfg(feature = "lang-c")]
    "c",
    #[cfg(feature = "lang-cpp")]
    "cpp",
    #[cfg(feature = "lang-go")]
    "go",
    #[cfg(feature = "lang-html")]
    "html",
    #[cfg(feature = "lang-css")]
    "css",
    #[cfg(feature = "lang-yaml")]
    "yaml",
    #[cfg(feature = "lang-toml")]
    "toml",
    #[cfg(feature = "lang-sql")]
    "sql",
    #[cfg(feature = "lang-java")]
    "java",
    #[cfg(feature = "lang-c-sharp")]
    "c_sharp",
];

fn canonical(language: &str) -> Option<&'static str> {
    let l = language.trim().to_lowercase();
    let name = match l.as_str() {
        "py" | "python" => "python",
        "js" | "jsx" | "javascript" => "javascript",
        "ts" | "typescript" => "typescript",
        "tsx" => "tsx",
        "rs" | "rust" => "rust",
        "swift" => "swift",
        "kt" | "kts" | "kotlin" => "kotlin",
        "json" => "json",
        "sh" | "shell" | "zsh" | "bash" => "bash",
        "h" | "c" => "c",
        "c++" | "cc" | "cxx" | "hpp" | "cpp" => "cpp",
        "c#" | "cs" | "csharp" | "c_sharp" => "c_sharp",
        "golang" | "go" => "go",
        "html" => "html",
        "css" => "css",
        "yaml" | "yml" => "yaml",
        "toml" => "toml",
        "sql" => "sql",
        "java" => "java",
        _ => return None,
    };
    (LANGUAGES.contains(&name) || name == "tsx" && LANGUAGES.contains(&"typescript")).then_some(name)
}

fn build(name: &str) -> Option<HighlightConfiguration> {
    let (language, query): (Language, String) = match name {
        "python" => (tree_sitter_python::LANGUAGE.into(), tree_sitter_python::HIGHLIGHTS_QUERY.into()),
        #[cfg(feature = "lang-javascript")]
        "javascript" => (tree_sitter_javascript::LANGUAGE.into(), [JS_QUERY, JSX_QUERY].join("\n")),
        #[cfg(feature = "lang-typescript")]
        "typescript" => (
            tree_sitter_typescript::LANGUAGE_TYPESCRIPT.into(),
            [JS_QUERY, tree_sitter_typescript::HIGHLIGHTS_QUERY].join("\n"),
        ),
        #[cfg(feature = "lang-typescript")]
        "tsx" => (
            tree_sitter_typescript::LANGUAGE_TSX.into(),
            [JS_QUERY, JSX_QUERY, tree_sitter_typescript::HIGHLIGHTS_QUERY].join("\n"),
        ),
        #[cfg(feature = "lang-rust")]
        "rust" => (tree_sitter_rust::LANGUAGE.into(), tree_sitter_rust::HIGHLIGHTS_QUERY.into()),
        #[cfg(feature = "lang-swift")]
        "swift" => (tree_sitter_swift::LANGUAGE.into(), tree_sitter_swift::HIGHLIGHTS_QUERY.into()),
        #[cfg(feature = "lang-kotlin")]
        "kotlin" => (tree_sitter_kotlin_ng::LANGUAGE.into(), KOTLIN_QUERY.into()),
        #[cfg(feature = "lang-json")]
        "json" => (tree_sitter_json::LANGUAGE.into(), tree_sitter_json::HIGHLIGHTS_QUERY.into()),
        #[cfg(feature = "lang-bash")]
        "bash" => (tree_sitter_bash::LANGUAGE.into(), tree_sitter_bash::HIGHLIGHT_QUERY.into()),
        #[cfg(feature = "lang-c")]
        "c" => (tree_sitter_c::LANGUAGE.into(), C_QUERY.into()),
        #[cfg(feature = "lang-cpp")]
        "cpp" => (tree_sitter_cpp::LANGUAGE.into(), [C_QUERY, tree_sitter_cpp::HIGHLIGHT_QUERY].join("\n")),
        #[cfg(feature = "lang-go")]
        "go" => (tree_sitter_go::LANGUAGE.into(), tree_sitter_go::HIGHLIGHTS_QUERY.into()),
        #[cfg(feature = "lang-html")]
        "html" => (tree_sitter_html::LANGUAGE.into(), tree_sitter_html::HIGHLIGHTS_QUERY.into()),
        #[cfg(feature = "lang-css")]
        "css" => (tree_sitter_css::LANGUAGE.into(), tree_sitter_css::HIGHLIGHTS_QUERY.into()),
        #[cfg(feature = "lang-yaml")]
        "yaml" => (tree_sitter_yaml::LANGUAGE.into(), tree_sitter_yaml::HIGHLIGHTS_QUERY.into()),
        #[cfg(feature = "lang-toml")]
        "toml" => (tree_sitter_toml_ng::LANGUAGE.into(), tree_sitter_toml_ng::HIGHLIGHTS_QUERY.into()),
        #[cfg(feature = "lang-sql")]
        "sql" => (tree_sitter_sequel::LANGUAGE.into(), tree_sitter_sequel::HIGHLIGHTS_QUERY.into()),
        #[cfg(feature = "lang-c-sharp")]
        "c_sharp" => (tree_sitter_c_sharp::LANGUAGE.into(), tree_sitter_c_sharp::HIGHLIGHTS_QUERY.into()),
        #[cfg(feature = "lang-java")]
        "java" => (tree_sitter_java::LANGUAGE.into(), tree_sitter_java::HIGHLIGHTS_QUERY.into()),
        _ => return None,
    };
    let query = query.replace(" @spell", "").replace(" @nospell", "");
    let mut config = match HighlightConfiguration::new(language, name, &query, "", "") { Ok(c) => c, Err(e) => { eprintln!("{name}: {e}"); return None } };
    let names: Vec<&str> = CAPTURES.iter().map(|(n, _)| *n).collect();
    config.configure(&names);
    Some(config)
}

struct Engine {
    highlighter: Highlighter,
    configs: HashMap<&'static str, Option<HighlightConfiguration>>,
}

thread_local! {
    static ENGINE: RefCell<Engine> = RefCell::new(Engine { highlighter: Highlighter::new(), configs: HashMap::new() });
}

/// UTF-8 byte offsets to UTF-16 offsets, for ascending queries (events arrive in order).
struct Utf16Cursor<'a> {
    code: &'a str,
    byte: usize,
    units: u32,
}

impl<'a> Utf16Cursor<'a> {
    fn at(&mut self, byte: usize) -> u32 {
        let byte = byte.min(self.code.len());
        if byte < self.byte {
            self.byte = 0;
            self.units = 0;
        }
        // A byte inside a character rounds down to its start.
        let mut end = byte;
        while !self.code.is_char_boundary(end) {
            end -= 1;
        }
        for c in self.code[self.byte..end].chars() {
            self.units += c.len_utf16() as u32;
        }
        self.byte = end;
        self.units
    }
}

/// Sorted, non-overlapping spans for `code` in `language` (a fence info string); an unknown or empty
/// language gives no spans.
#[uniffi::export]
pub fn highlight_code(language: String, code: String) -> Vec<CodeSpan> {
    let Some(name) = canonical(&language) else { return vec![] };
    if code.is_empty() {
        return vec![];
    }
    ENGINE.with(|cell| {
        let mut engine = cell.borrow_mut();
        let Engine { highlighter, configs } = &mut *engine;
        let Some(config) = configs.entry(name).or_insert_with(|| build(name)).as_ref() else { return vec![] };
        let Ok(events) = highlighter.highlight(config, code.as_bytes(), None, |_| None) else { return vec![] };
        let mut cursor = Utf16Cursor { code: &code, byte: 0, units: 0 };
        let mut stack: Vec<CodeToken> = vec![];
        let mut out: Vec<CodeSpan> = vec![];
        for event in events {
            match event {
                Ok(HighlightEvent::HighlightStart(h)) => stack.push(CAPTURES[h.0].1),
                Ok(HighlightEvent::HighlightEnd) => {
                    stack.pop();
                }
                Ok(HighlightEvent::Source { start, end }) => {
                    let Some(&token) = stack.last() else { continue };
                    let (s, e) = (cursor.at(start), cursor.at(end));
                    if e <= s {
                        continue;
                    }
                    match out.last_mut() {
                        Some(last) if last.token == token && last.end == s => last.end = e,
                        _ => out.push(CodeSpan { start: s, end: e, token }),
                    }
                }
                // A parse that fails midway keeps what it coloured so far.
                Err(_) => break,
            }
        }
        out
    })
}

#[uniffi::export]
pub fn highlight_languages() -> Vec<String> {
    LANGUAGES.iter().map(|s| s.to_string()).collect()
}
