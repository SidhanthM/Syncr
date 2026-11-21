# Dry-ink cache: wet-frame timings (2026-11-21)

`SYNCR_MAC_DRY_CACHE=0` turns the cache off. Measure with:

```sh
cd code/mac && swift build -c release --product SyncrMacChecks && .build/release/SyncrMacChecks --dry-bench
```

The fixtures are generated in memory by `dryDrawing` (checks target): a light note of 50 strokes and a heavy one of 5,000, all in view
(every 11th a highlighter), meshes from the core. Each row drives 5 pen strokes (2,320 samples, 580 wet frames) through the real live-ink
engine (ingest, tessellation, publish), drawn offscreen at 2880x1800 on a still view; one warm-up stroke is not counted. GPU time is
`gpuEndTime - gpuStartTime` of each frame's command buffer; "input-to-presented" is frame received to GPU-complete (the offscreen
"presented"), so it excludes display scan-out. Release build, M-series Mac, run 2026-11-21 (second run in brackets).

| note | strokes | cache | GPU p50 ms | GPU p95 ms | encode p50 / p95 ms | input-to-presented p50 / p95 ms |
|---|---|---|---|---|---|---|
| light | 50 | off | 1.26 (1.27) | 1.35 (1.43) | 0.03 / 0.05 | 1.87 / 2.59 (2.12 / 6.15) |
| light | 50 | on | 0.55 (0.51) | 0.66 (0.55) | 0.02 / 0.08 | 1.48 / 2.44 (1.57 / 5.25) |
| heavy | 5000 | off | 23.26 (23.31) | 25.26 (26.46) | 2.65 / 5.19 | 28.75 / 36.97 (30.16 / 48.59) |
| heavy | 5000 | on | 0.35 (0.48) | 0.48 (1.91) | 0.01 / 0.02 | 0.99 / 1.35 (1.29 / 2.85) |

Not measured here: a real window and `CAMetalLayer` present (needs the app and a display), and frames that rebuild the cache
(first frame after a pan settles, pen-up, progressive load), which cost about one uncached frame.
