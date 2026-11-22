# SyncrCompanion

Dependency-free macOS 13+ SwiftPM package. No AppKit or core dependency in the v2 engine; the legacy primitive renderer still uses local system graphics. Existing v1 requests/tools/sessions and stored drawings remain supported. Public additions: [API-CHANGES.md](API-CHANGES.md).

```sh
cd code/companion
swift build
swift run SyncrCompanionChecks
swift run SyncrJudge                       # offline functional fixtures, no credentials/network
swift run SyncrJudge --help
```

## One engine per open note

`CompanionEngine` is an actor. Supply a provider, separate gate/answer `ModelSettings`, an optional production `PictureInspector`, an app-state folder and a budget. `events(for:at:confirmedBudget:)` accepts `.ask(RequestInput)`, `.reply(RequestInput)` or `.pause(RequestInput)` and returns an `AsyncStream<EngineOutput>`. All note text, selection/changed UTF-8 byte spans, threads, Tell, reader and images arrive as plain values. All proposed additions leave as plain values. There is no note-write path in this package.

- At most one pass; a concurrent explicit ask fails clearly. Ineligible pauses produce an empty stream without constructing a request or launching transport.
- Output includes first-token timing, provisional margin/reply text, validated complete items, provisional picture steps, picture replacements, gate decisions and separately categorized token usage.
- **Host transaction rule:** show complete `.item`s immediately, but stage their writes until `.finished`. On `.failed`, the complete validated prefix may be committed; on `.cancelled`, discard the entire pass's staged additions. This preserves the cancellation requirement that the note is unchanged apart from the user's typing. Never store `.textPreview` or `.pictureStep`; nil text/replacement clears a preview. Recheck note identity/mode/generation at the host boundary. An engine cannot undo writes a host prematurely committed.
- Call `activity(at:)` for typing/ink changes (conservatively cancels the current pass); use `idleActivity(at:)` for movement without changes. Cancel/close on note switch, Off or shutdown. The engine never invokes MainActor. Do not await it, snapshot images, or perform disk/network work on the pen's path.
- `lastRequest` is the actual selected route's body/commands without authentication, with images represented by MIME, byte size and SHA-256 instead of bytes. Request objects retain image bytes only to send them. Legacy wire previews are also image-safe, and legacy last-request records no longer retain page image bytes.
- Cache order: standing instructions, selected pattern excerpts, unchanged note, changed passage and dynamic metadata/images. Claude marks the standing instructions, examples and unchanged note with explicit ephemeral cache breakpoints; other routes retain the same stable prefix. `Usage` separates uncached input, output, cache reads and writes (OpenAI's cached input is subtracted from its input total).

### Response framing (v2)

JSON headers are strict single-line objects terminated by LF. No Markdown fences, duplicate/unknown keys, unknown types, NUL or unresolved anchors. A **successful provider terminal event** also delimits the final record; a dropped connection does not. This EOF rule was added after the first real Codex exchange omitted its final LF.

```text
{"type":"margin","quote":"A real passage.","kind":"question","text":"What assumption makes this true?"}
{"type":"picture","quote":"A real passage.","title":"Two cases"}
<svg viewBox="0 0 320 100">
<text x="18" y="24" font-size="16">Two cases</text>
<g data-step="1" data-caption="Read the first case.">
<text x="18" y="60" font-size="12">First</text>
<line x1="18" y1="80" x2="110" y2="80" stroke="var(--ink)"/>
</g>
</svg>
{"type":"margin","quote":"A real passage.","kind":"explanation","text":"The picture separates the cases. Read the labels before the connecting lines."}
```

Other headers:

```json
{"type":"quiet"}
{"type":"reply","threadID":"existing-thread","text":"A reply."}
{"type":"sources","sources":[{"title":"A source","url":"https://example.org/"}]}
```

`quiet` must be alone. Margin kinds: question/explanation/flag, at most 60 words; replies at most 150 unless the request explicitly asks for more than 150. Sources require Web and real HTTPS URLs without userinfo. Stream cap 256 KiB/32 items; SVG cap 24,000 characters (the core independently enforces its scalar/byte limits). A malformed item ends the pass and preserves only completed valid items. `ItemStreamParser.finish(emit:)` is mandatory after successful provider completion to flush an EOF-delimited final item; `finish()` alone only validates already delimited input.

For immediate **text previews**, put `type`, `quote`/`threadID`, and `kind` before `text`, as above. The parser exposes only a valid routing prefix plus decoded partial text; previews are cleared before the complete validated item or at cancellation/failure. Other field orders remain valid, but wait until complete. Picture step groups are recognized as they close, including without an intervening LF. Each provisional SVG prefix must independently pass the supplied filter and display-list check. Full pictures wait for final checking/commentary; rejected results replace/remove the preview.

## Routes

- **Claude key:** `ClaudeKeychain` generic password service `dev.syncr.companion.claude`, account `api-key`, device-only storage. Native Messages SSE (`stream:true`, `anthropic-version:2026-10-21`), text deltas, cumulative usage and cache breakpoints. Paginated `/v1/models`; Web uses the server search tool. `HTTPTransport.stream` is injectable; `URLSessionTransport` uses ephemeral URLSession bytes and cancels its session with the stream. V1 `send` retains native tool calls.
- **OpenAI key:** `OpenAIKeychain`, service `dev.syncr.companion.openai`, same account/storage policy. Responses SSE, `response.output_text.delta` and `response.completed`, `store:false`, image content blocks, usage/cache accounting and optional Web search. Catalog from `/v1/models`. Keys are never shown in previews/logs.
- **Codex sign-in:** pi owns OAuth; Syncr never reads or copies its credentials. Use `pi /login` then `pi auth check --provider openai-codex`. The v2 route keeps one isolated RPC child per provider/engine. Each pass waits for `new_session`, `set_model`, disabled retry/compaction and low thinking, then sends its complete prompt. `message_update.assistantMessageEvent.text_delta` is forwarded immediately; `message_end` supplies usage; only `agent_settled` completes the provider pass. `clear_queue` + `abort` are sent on cancellation, and the child is terminated to unblock stalls. Errors/death/cancellation restart on the next pass; normal passes reuse the child with no history. After five minutes without a pass, a one-shot idle retirement releases the child, pipes and temporary cwd; the per-note engine and semantic state remain, and the next pass starts a fresh child lazily. Request admission invalidates retirement under the ownership lock; timer generation and child identity checks reject stale callbacks. This internal timeout keeps gate/answer/repair bursts warm without retaining a subprocess indefinitely for every idle tab. Closing stdin requests clean shutdown; owned children are reaped. No tools, extensions, MCP, skills, templates, themes or repository-context discovery; ephemeral temp cwd, no saved sessions and offline startup. Optional sanitized `trace` records protocol structure/deltas, not prompts/images/credentials.
- **Codex Web fallback / pi absent:** preserves the existing native Codex CLI route, with isolated ephemeral cwd, read-only sandbox, ignored user config/rules, local tools/plugins/MCP/hooks/agents disabled and native Web enabled only by the switch. V2 consumes its JSON event records and forwards monotonic agent-message updates/completions. This is **item-level fallback streaming**, not a persistent pi process or guaranteed token deltas. Cancellation terminates only that owned child. Recorded protocol verified; no live Web request made here.
- **Recorded:** `RecordedStreamProvider` replays credential-free deltas, delays, usage and injected failures. `RecordedProvider` remains the v1 envelope fixture route, not a v2 streaming adapter.

SSE decoding accepts CRLF, multiline data, comments/pings and unknown events, but rejects malformed JSON, errors, truncation, incomplete turns and oversized responses. No provider text or error metadata is interpreted as a file action.

Official references (consulted on 2026-11-16; downloaded copies under `Reference/` are local-only and ignored):
- https://platform.claude.com/docs/en/build-with-claude/streaming.md
- https://platform.claude.com/docs/en/build-with-claude/prompt-caching.md
- https://developers.openai.com/api/docs/guides/streaming-responses
- OpenAI Responses creation/stream-event/prompt-caching API references (official web search).
- Installed pi 1.5.0 `docs/rpc.md`, `rpc-commands.md`, `pi --help`, auth readiness and catalog, plus the sanitized regression exchange in `Sources/SyncrCompanionChecks/Fixtures/codex-smoke-rpc.jsonl`. Raw smoke traces under `Judgment/Results/` stay local.

## Gate, settings, budget and local evidence

Pause eligibility reuses `TriggerMachine`: Thinks with you, changed note, 3 seconds idle, 20 seconds since the previous pass, no pass in flight, fewer than three undismissed/unpinned notes and budget remaining. Gate input has a bounded changed passage, nearby text, Tell and reader; no images or Web. A strict small JSON decision (`decision:quiet|speak`, one-line `reason`, `kind:question|explanation|picture`) is logged locally. Only speak can launch the answer model; a gate that exhausts the budget cannot silently launch it. Explicit asks bypass the gate; Off gates before provider work and a budget override needs host confirmation.

`ModelSettings.defaults(from:)` picks only IDs the provider actually lists. Lowest positive input+output catalog cost is the gate proxy where available; otherwise nano/haiku/spark/luna/mini/terra family preference. Answers prefer current Sol/Fable/Mythos/Opus/Pro/Astra/Sonnet families, numerically newest within family. Catalogs do not expose a universal size/quality order: this is a documented heuristic, not a measured strength claim. This machine selected **GPT-6 Luna / GPT-6.1 Sol**. Users can select both independently. Legacy v1 static defaults remain for compatibility; v2 hosts should call the catalog selector.

Usage records roll over by local day, retain cache categories and fall back to request counting when unreported. An interrupted unmetered request is charged as unmetered; host persists the meter. `LocalDecisionLog(stateFolder:)` appends `companion-decisions.jsonl` (0600): time, event, passage; gate/model/seconds or picture findings; feedback has action dismiss/pin/addToNote and itemID. These are app-state files, never note contents. Hosts call `recordFeedback` for explicit user actions.

## Pictures: the core boundary and pattern book

`PictureInspector.filter(svg:)` and `.displayList(svg:)` are implemented by the **host using the Rust core**. This package does not link or duplicate the production SVG filter. Its plain `PictureDisplayList` preserves always-shown items, numbered steps and persistent hide/dim. `PictureDrawItem` has paths/text and optional core bounds, linear transform, baseline, font, stroke width and closed flag; adapters should copy these for rotated/scaled text. IDs are adapter-stable names for findings. Core markers are flattened; arrowheads can be inferred from short chevrons/triangles instead of relying on a flag unavailable in the core output.

`ReferencePictureInspector` exists **only in the checks executable**. It parses just the fixtures' subset and is not safe or complete enough for production. Passing it is not a claim that a picture was rendered or passed the real core. Without an inspector the engine emits words, never a stored picture.

Twelve handwritten examples in `Resources/patterns/patterns.json`: board reuse/undo, call-tree hide, branching flow, quadratic graph with marked points, force arrows, vertical labelled molecule chain, orbital boxes/pointers, timeline, number line, before/after, three-actor sequence, and table. Every fixture passes the reference inspector/check. Tag word overlap scores 4 points; description/id word overlap scores 1; lexical ID breaks ties. Three closest examples precede the note. `addPinned` admits only filtered, finding-free pictures into `companion-patterns.json` in explicit host app state; duplicates are ignored, limit 100/3 MiB. `loadPinned`/`closest(additional:)` incorporate them. Pinning itself is not automatic training.

The standing instructions contain the full constrained SVG vocabulary, 320-wide canvas, theme tokens/fonts, one idea per step, title/legend, hide or new panels instead of overdraw, 18-unit baseline spacing, beside-line labels and rotated/stacked layouts. They also require short leading prose, ordered explanations, inline notation, 2–3 sentences after SVG describing actual marks, and no summary/help offer.

### Check thresholds and repair

All findings name the rule, step and item IDs. Visibility accumulates hide across steps; dimmed items still count as visible. At most 64 findings are returned, bounding repair prompts.

| Rule | Measured failure |
|---|---|
| Overlap | Label intersection >1 square unit, or any stroke segment crosses the label box inset by 1 unit. Boxes use supplied core bounds, otherwise 0.6 × size × character count with anchor/baseline/transform. |
| Bounds | A point or label corner exceeds any canvas edge by >3 units, including always-shown titles. |
| Empty | A step has no drawn items (hide-only steps still need a mark/label). |
| Legibility | Font outside 10–20, or estimated label width >canvas width. |
| Caption truth | Shaded needs a non-page filled polygon of area >4; dashed needs a flag or >=3 short collinear segments; arrow needs a flag or 2–20-unit chevron/triangle with comparable sides. Quoted label names, explicit point/atom/box/node/label/site names and single uppercase letter labels (except articles A/I) must exist. Captions are checked at that visible step; commentary against all steps. |
| Crowding | >16 item origins in a fixed 48×48 cell. |

Caption length also fails at >20 words or >160 characters. Geometric/keyword truth checks are heuristics, not subject-matter proof; they cannot establish molecule valence, force correctness or queen safety. Dashed CSS is not permitted by the core, so model-authored dashes must be separate segments.

One repair maximum per picture sends the SVG and findings back. If still failing, accept **only <=2 minor label/label overlaps**, each <=8% of the smaller box's area. Any line overlap, bounds/semantic/crowding/filter/repair failure is major: remove the preview, emit a words-only fallback. A failed repair provider also falls back. Final checked SVG replaces provisional steps. Read-along still emits at most one margin: if prose already exists, a failed picture carries its words-only warning in `.pictureReplace` findings (`rule: "fallback"`) instead of adding a second note. Hosts should show that warning beside the existing prose. No core storage occurs here.

## Speed

`SYNCR_COMPANION_TRACE=<file>` makes the engine append one line per pass with the time of each stage from ask to first text and first picture step (see API-CHANGES, "Speed round"). It records numbers only.

`swift run SyncrCompanionReplay [--runs N] [--gaps 0,2] [--pi-cold S] [--pi-session S] [--provider-scale X] [--only ask-text|ask-picture|read-text] [--json out.json]` runs the real engine and the real pi transport against a fake `pi` that replays recorded answers (`Sources/SyncrCompanionReplay/Fixtures`). Provider time is recorded, so it can be subtracted: `engine = total - provider`. With the default `--provider-scale 0` the provider answers at once and the total is the engine's own time, including a modelled pi start-up (`--pi-cold`, `--pi-session`, measured from the real `pi` with no model call). Conditions: `cold` (first ask), `warm` (second ask), `after-cancel` (typing cancelled a pass); `--gaps` is the pause between typing and asking. Chunking and pace after the first token are synthetic; first-token and gate times and the answer text are recorded.

## Judgment

`Judgment/{student,engineering,journal}.json` contains 24 authored plausible excerpts: 12 quiet, 4 ask, 4 explain, 4 draw, with exact ground-truth passage and Tell. The owner must add the real-use half later; no private library was accessed.

Authored cases and `recorded.json` are versioned. Generated results and raw RPC traces under `Judgment/Results/` are ignored: they can contain prompts, provider output, timings, and machine-specific metadata. Historical live results mentioned below are local evidence, not files supplied by a clone. The sanitized EOF-framing regression fixture remains versioned under the checks target.

```sh
# Offline functional run; no credentials or inference (use a fresh output filename each run):
swift run SyncrJudge --output Judgment/Results/recorded-functional.json
# Actual inference, opt-in, ledgered, maximum 60 engine J1 calls total:
swift run SyncrJudge --route codex --live-manual --output Judgment/Results/local-baseline.json
# Optional --gate-model ID --answer-model ID, or chosen claude/openai route with its key.
# Replay a locally generated baseline without inference:
swift run SyncrJudge --replay Judgment/Results/local-baseline.json
# Use the host's production core adapter for picture scoring:
swift run SyncrJudge --replay Judgment/Results/local-baseline.json --inspector /absolute/path/to/adapter
```

An inspector executable reads `{"svg":"..."}` on stdin and returns `{"svg":"canonical clean SVG","displayList":{...}}` using these Codable display-list fields, or exits nonzero. It has a 10s/2 MiB bound. Without it, **picture pass rate is not measured**, never silently counted as passing.

The runner always gates every case and answers only speak. It reports numerator/denominator for expected quiet, expected speech, correct kind, correct passage, and inspected-picture pass rate; missed speech/errors count against kind/passage. A precise subquote is correct if its resolved UTF-8 span lies within the marked passage. All emitted quotes must satisfy that condition. It writes dated raw responses, errors, usage, timings, settings and measures, refusing to overwrite results. `recorded.json` is a hand-scripted functional fixture, not a quality baseline. Scorer checks inject a false alarm and independently inspect four fixture pictures.

Live baseline on 2026-11-16: 24 gates + 11 answers = 35 Codex calls. Quiet 12/12; spoke 11/12; kind 7/12; passage 9/12 after offline containment rescore (original exact-equality scorer was 6/12). Five generated pictures remain uninspected. Preserve both original and rescored results; replay timings are not live timings. No subject-quality or on-screen claim follows from these numbers.

Live E1 smoke: two calls total, no more planned. First uncovered missing final LF; sanitized real exchange is replayed by checks after the parser fix. Second measured first token **3.538s**, first complete item **4.046s**: it did **not** meet the 3s target. Baseline answer first-token median **2.690s** (range 2.055–8.818); first complete item median **5.527s** (3.133–8.828). Gate median **1.872s** (1.523–5.384), not uniformly under 2s. No live key-route or first-picture-step timing here. No P2 tuning calls. All 37 engine calls were recorded in `tasks/sketch-trial-2/live-calls.jsonl`.

## What checks cover / do not cover

Focused idle lifecycle checks: `swift run --package-path code/companion SyncrCompanionChecks --codex-idle` from the repository root. These use only the offline Python RPC fixture with a short injected timeout, including active-request protection, idle reaping, lazy restart, replayed stale timers, cancellation, immediate shutdown and temporary-cwd cleanup. They also run in the full companion checks.

Recorded checks: v1 hostile envelopes/anchors/readers/graphics/budgets; 600s Off = zero attempts; arbitrary incremental framing and EOF; provisional text; both native SSE routes, truncation/cache accounting; persistent pi reuse/reset/death/restart/abort; native Web fallback/cancellation and blocked-stdin/SIGPIPE regression; simulated hour = 180 gates/60 speak-only answers; cap/budget/invalid gate; all patterns and six seeded faults; one repair success/failure; read-along fallback margin cap; step cancellation; scorer and local feedback.

Not verified here: live Claude/OpenAI keys, live native Web, the actual core adapter or screenshot quality, Mac UI/ink/storage cancellation, the full picture trial, uniform latency targets, image seen/relaunch/sync, or phone behavior. Those are host/core rounds. No device command, real library read, migration, generated-binding edit or new dependency was used by this agent.
