use super::*;

#[derive(serde::Deserialize)]
struct File<I, E> {
    vectors: Vec<Vector<I, E>>,
}
#[derive(serde::Deserialize)]
struct Vector<I, E> {
    name: String,
    input: I,
    expected: E,
}
#[derive(serde::Deserialize, Clone)]
struct PagesIn {
    pages: Vec<PageExtent>,
    mode: PageMode,
    gap: f32,
}
#[derive(serde::Deserialize, Clone)]
struct CardsIn {
    anchors: Vec<CardAnchor>,
    cards: Vec<CanvasCard>,
    gap: f32,
}
fn load<I: serde::de::DeserializeOwned, E: serde::de::DeserializeOwned>(
    name: &str,
) -> Vec<Vector<I, E>> {
    let path = format!("{}/../shared/layout/{name}", env!("CARGO_MANIFEST_DIR"));
    serde_json::from_slice::<File<I, E>>(&std::fs::read(path).unwrap())
        .unwrap()
        .vectors
}

#[test]
fn shared_page_vectors_give_the_expected_rects() {
    let all = load::<PagesIn, Vec<PageRect>>("pages-v1.json");
    assert!(all.len() >= 7);
    for v in all {
        let got = layout_pages(v.input.pages.clone(), v.input.mode, v.input.gap);
        assert_eq!(got.len(), v.expected.len(), "{}", v.name);
        for (g, e) in got.iter().zip(&v.expected) {
            assert_eq!(g.sheet_id, e.sheet_id, "{}", v.name);
            assert_eq!(g.row, e.row, "{}", v.name);
            for (a, b) in [
                (g.x, e.x),
                (g.y, e.y),
                (g.width, e.width),
                (g.height, e.height),
            ] {
                assert!((a - b).abs() < 1e-3, "{}: {g:?} vs {e:?}", v.name);
            }
        }
    }
}

#[test]
fn shared_card_vectors_give_the_expected_placements() {
    let all = load::<CardsIn, Vec<CardPlacement>>("canvas-cards-v1.json");
    assert!(all.len() >= 6);
    for v in all {
        let got = layout_canvas_cards(v.input.anchors.clone(), v.input.cards.clone(), v.input.gap);
        assert_eq!(got, v.expected, "{}", v.name);
        // Deterministic, and no two cards overlap.
        assert_eq!(
            got,
            layout_canvas_cards(v.input.anchors, v.input.cards.clone(), v.input.gap)
        );
        for (i, a) in got.iter().enumerate() {
            for (b, cb) in got.iter().zip(&v.input.cards).take(i) {
                let ca = &v.input.cards[i];
                let apart = a.x >= b.x + cb.width
                    || b.x >= a.x + ca.width
                    || a.y >= b.y + cb.height
                    || b.y >= a.y + ca.height;
                assert!(apart, "{}: {} overlaps {}", v.name, a.id, b.id);
            }
        }
    }
}
