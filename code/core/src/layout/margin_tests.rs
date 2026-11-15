use super::*;

#[derive(serde::Deserialize)]
struct Vectors {
    vectors: Vec<Vector>,
}

#[derive(serde::Deserialize)]
struct Vector {
    name: String,
    input: MarginInput,
    expected: MarginLayout,
}

fn vectors() -> Vec<Vector> {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../shared/layout/margin-v1.json");
    serde_json::from_slice::<Vectors>(&std::fs::read(path).unwrap()).unwrap().vectors
}

#[test]
fn shared_vectors_give_the_expected_layout() {
    let all = vectors();
    assert!(all.len() >= 8);
    for v in all {
        assert_eq!(layout_margins(v.input), v.expected, "{}", v.name);
    }
}

#[test]
fn layout_is_deterministic_and_leaves_its_input_order_alone() {
    for v in vectors() {
        let first = layout_margins(v.input.clone());
        assert_eq!(first, layout_margins(v.input), "{}", v.name);
    }
}

#[test]
fn no_two_cards_in_a_lane_overlap() {
    for v in vectors() {
        let out = layout_margins(v.input.clone());
        for lane in [MarginLane::Left, MarginLane::Right] {
            let cards: Vec<_> = out.placements.iter().filter(|p| p.lane == lane).collect();
            for pair in cards.windows(2) {
                assert!(pair[1].y >= pair[0].y + pair[0].height + v.input.spacing, "{}", v.name);
            }
        }
    }
}
