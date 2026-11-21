use crate::highlight::*;

fn kinds(language: &str, code: &str) -> Vec<(String, CodeToken)> {
    let units: Vec<u16> = code.encode_utf16().collect();
    highlight_code(language.into(), code.into())
        .iter()
        .map(|s| (String::from_utf16(&units[s.start as usize..s.end as usize]).unwrap(), s.token))
        .collect()
}

fn has(spans: &[(String, CodeToken)], token: CodeToken) -> bool {
    spans.iter().any(|(_, t)| *t == token)
}

fn check_sorted(code: &str, spans: &[CodeSpan]) {
    let len = code.encode_utf16().count() as u32;
    let mut at = 0;
    for s in spans {
        assert!(s.start >= at && s.end > s.start && s.end <= len, "{s:?} after {at}");
        at = s.end;
    }
}

#[test]
fn every_language_has_keyword_string_and_comment() {
    let samples: &[(&str, &str)] = &[
        ("python", "# note\ndef greet(name):\n    return \"hi \" + name\n"),
        ("javascript", "// note\nfunction greet(name) {\n  return \"hi \" + name;\n}\n"),
        ("typescript", "// note\nfunction greet(name: string): string {\n  return \"hi \" + name;\n}\n"),
        ("tsx", "// note\nfunction greet(name: string) {\n  return <b>{\"hi \" + name}</b>;\n}\n"),
        ("rust", "// note\nfn greet(name: &str) -> String {\n    format!(\"hi {}\", name)\n}\n"),
        ("swift", "// note\nfunc greet(_ name: String) -> String {\n    return \"hi \" + name\n}\n"),
        ("kotlin", "// note\nfun greet(name: String): String {\n    return \"hi \" + name\n}\n"),
        ("bash", "# note\nif [ -f x ]; then\n  echo \"hi\"\nfi\n"),
        ("c", "// note\nint main(void) {\n  return printf(\"hi\");\n}\n"),
        ("cpp", "// note\nclass A {\n public:\n  int f() { return std::string(\"hi\").size(); }\n};\n"),
        ("go", "// note\nfunc greet(name string) string {\n\treturn \"hi \" + name\n}\n"),
        ("java", "// note\nclass A {\n  String greet(String n) {\n    return \"hi \" + n;\n  }\n}\n"),
        ("c_sharp", "// note\nclass A {\n  public string Greet(string n) {\n    return \"hi \" + n;\n  }\n}\n"),
        ("sql", "-- note\nSELECT name FROM users WHERE name = 'hi';\n"),
        ("yaml", "# note\nname: \"hi\"\nok: true\n"),
        ("toml", "# note\n[pkg]\nname = \"hi\"\nn = 3\n"),
        ("css", "/* note */\na { color: red; content: \"hi\"; }\n@media print { a { margin: 0 } }\n"),
        ("html", "<!-- note -->\n<div class=\"hi\">text</div>\n"),
        ("json", "{\"name\": \"hi\", \"n\": 3, \"ok\": true}\n"),
    ];
    let built = highlight_languages();
    for (language, code) in samples {
        if !built.iter().any(|l| l == language) && !(*language == "tsx" && built.iter().any(|l| l == "typescript")) {
            continue;
        }
        let spans = kinds(language, code);
        check_sorted(code, &highlight_code((*language).into(), (*code).into()));
        // Which of the three each grammar can show: JSON has no keywords or comments; markup and YAML have
        // tags/keys rather than keywords.
        let string = has(&spans, CodeToken::String) || has(&spans, CodeToken::Attribute) && *language == "css";
        let comment = has(&spans, CodeToken::Comment);
        let keyword = has(&spans, CodeToken::Keyword);
        match *language {
            "json" => assert!(string && has(&spans, CodeToken::Number), "{language}: {spans:?}"),
            "html" => assert!(comment && has(&spans, CodeToken::Tag) && string, "{language}: {spans:?}"),
            "yaml" | "toml" => assert!(comment && string, "{language}: {spans:?}"),
            "css" => assert!(comment && string && keyword, "{language}: {spans:?}"),
            _ => assert!(keyword && string && comment, "{language}: {spans:?}"),
        }
    }
}

/// The language set this build was made with: all 17, or the phone's python + typescript + bash + cpp.
fn expected() -> Vec<&'static str> {
    let mut v = vec!["python"];
    for (on, l) in [
        (cfg!(feature = "lang-javascript"), "javascript"), (cfg!(feature = "lang-typescript"), "typescript"),
        (cfg!(feature = "lang-rust"), "rust"), (cfg!(feature = "lang-swift"), "swift"), (cfg!(feature = "lang-kotlin"), "kotlin"),
        (cfg!(feature = "lang-json"), "json"), (cfg!(feature = "lang-bash"), "bash"), (cfg!(feature = "lang-c"), "c"),
        (cfg!(feature = "lang-cpp"), "cpp"), (cfg!(feature = "lang-go"), "go"), (cfg!(feature = "lang-html"), "html"),
        (cfg!(feature = "lang-css"), "css"), (cfg!(feature = "lang-yaml"), "yaml"), (cfg!(feature = "lang-toml"), "toml"),
        (cfg!(feature = "lang-sql"), "sql"), (cfg!(feature = "lang-java"), "java"), (cfg!(feature = "lang-c-sharp"), "c_sharp"),
    ] {
        if on { v.push(l) }
    }
    v
}

fn on(l: &str) -> bool { expected().contains(&l) }

#[test]
fn listed_languages_are_what_was_compiled_in() {
    let mut got = highlight_languages();
    got.sort();
    let mut want: Vec<String> = expected().into_iter().map(String::from).collect();
    want.sort();
    assert_eq!(got, want);
    if cfg!(feature = "all-languages") { assert_eq!(got.len(), 18); }
    else if cfg!(feature = "main-languages") { assert_eq!(got, ["c", "cpp", "java", "python"]); }
}

#[test]
fn languages_not_compiled_in_give_nothing() {
    for (l, code) in [("javascript", "let a = 1; // c"), ("rust", "fn f() {} // c"), ("swift", "func f() {} // c"), ("c", "int x = 1; // c"),
        ("go", "func f() {} // c"), ("java", "class A {} // c"), ("kotlin", "fun f() {} // c"), ("sql", "SELECT 1; -- c"),
        ("bash", "echo hi # c"), ("typescript", "const a: number = 1; // c"), ("tsx", "const a = 1;"), ("cpp", "int x = 1; // c"), ("c_sharp", "class A {} // c")] {
        if !on(l) && !(l == "tsx" && on("typescript")) {
            assert!(highlight_code(l.into(), code.into()).is_empty(), "{l}");
        }
    }
}

#[test]
fn aliases_match_their_language() {
    let code = "def f():\n    return 'x'  # c\n";
    let base = highlight_code("python".into(), code.into());
    assert!(!base.is_empty());
    for alias in ["py", " Python ", "PY"] {
        assert_eq!(highlight_code(alias.into(), code.into()), base, "{alias}");
    }
    let same = |aliases: &[&str], canon: &str, code: &str| {
        if !on(canon) { return }
        let base = highlight_code(canon.into(), code.into());
        assert!(!base.is_empty(), "{canon}");
        for a in aliases { assert_eq!(highlight_code((*a).into(), code.into()), base, "{a}"); }
    };
    same(&["rs"], "rust", "fn main() { let s = \"x\"; }");
    same(&["js", "jsx"], "javascript", "let a = 1;");
    same(&["sh", "shell", "zsh"], "bash", "echo \"x\" # c");
    same(&["golang"], "go", "func f() {}");
    same(&["ts"], "typescript", "const a: number = 1;");
    same(&["c++", "cc", "cxx", "hpp"], "cpp", "int main() { return 0; }");
    same(&["h"], "c", "int main(void) { return 0; }");
    same(&["c#", "cs", "csharp"], "c_sharp", "class A { string s = \"x\"; } // c");
    same(&["kt", "kts"], "kotlin", "fun f() {}");
    same(&["yml"], "yaml", "a: 1");
    if on("typescript") {
        assert!(!highlight_code("tsx".into(), "const a = <b/>;".into()).is_empty());
    }
}

#[test]
fn unknown_or_empty_gives_nothing() {
    assert!(highlight_code("brainfuck".into(), "+++".into()).is_empty());
    assert!(highlight_code("".into(), "def f(): pass".into()).is_empty());
    assert!(highlight_code("python".into(), "".into()).is_empty());
}

#[test]
fn offsets_are_utf16() {
    // The emoji is two UTF-16 units and four UTF-8 bytes; the accent is one unit and two bytes.
    let code = "x = \"caf\u{e9} \u{1F600} ok\"  # \u{1F600}\u{1F600} done\ny = 1\n";
    let spans = kinds("python", code);
    assert!(spans.contains(&("\"caf\u{e9} \u{1F600} ok\"".into(), CodeToken::String)), "{spans:?}");
    assert!(spans.contains(&("# \u{1F600}\u{1F600} done".into(), CodeToken::Comment)), "{spans:?}");
    assert!(spans.contains(&("1".into(), CodeToken::Number)), "{spans:?}");
    check_sorted(code, &highlight_code("python".into(), code.into()));
}

#[test]
fn malformed_code_still_gives_ordered_spans() {
    for (language, code) in [
        ("python", "s = \"never closed\nx = (1 +\n# tail"),
        ("rust", "fn f( { let s = \"open\n/* never"),
        ("javascript", "const a = `open ${\n"),
        ("json", "{\"a\": [1, 2,"),
        ("swift", "func \u{1F600}( { \"\"\"open"),
    ] {
        let spans = highlight_code(language.into(), code.into());
        check_sorted(code, &spans);
    }
}

#[test]
fn spans_do_not_depend_on_what_the_thread_did_before() {
    let a = highlight_code("python".into(), "def f(): return 'a'".into());
    let _ = highlight_code("rust".into(), "fn main() {}".into());
    assert_eq!(highlight_code("python".into(), "def f(): return 'a'".into()), a);
}

fn python_block(lines: usize) -> String {
    let mut s = String::new();
    for i in 0..lines / 4 {
        s += &format!("def f{i}(a, b=\"x\"):\n    # step {i}\n    total = a * {i} + len(b)\n    return total if total > 3 else None\n");
    }
    s
}

fn rust_file(lines: usize) -> String {
    let mut s = String::new();
    for i in 0..lines / 5 {
        s += &format!("/// doc {i}\npub fn f{i}(a: u32, b: &str) -> Option<u32> {{\n    let n = a * {i} + b.len() as u32; // note\n    if n > 3 {{ Some(n) }} else {{ None }}\n}}\n");
    }
    s
}

fn p95(language: &str, code: &str) -> (f64, f64) {
    let _ = highlight_code(language.into(), code.into()); // build the configuration first
    let mut times: Vec<f64> = (0..50)
        .map(|_| {
            let t = std::time::Instant::now();
            let n = highlight_code(language.into(), code.into()).len();
            assert!(n > 0);
            t.elapsed().as_secs_f64() * 1000.0
        })
        .collect();
    times.sort_by(|a, b| a.partial_cmp(b).unwrap());
    (times[24], times[47])
}

#[test]
fn benchmark_prints_percentiles() {
    let (p50, p95a) = p95("python", &python_block(200));
    println!("highlight bench: python 200 lines  p50 {p50:.3} ms  p95 {p95a:.3} ms");
    if on("rust") {
        let (p50, p95b) = p95("rust", &rust_file(2000));
        println!("highlight bench: rust 2000 lines   p50 {p50:.3} ms  p95 {p95b:.3} ms");
    }
    if !cfg!(debug_assertions) {
        assert!(p95a < 5.0, "python 200 lines p95 {p95a} ms");
    }
}
