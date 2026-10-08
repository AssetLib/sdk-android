# 0.2.0-preview.1

- Recognizes the signed rendition-schema-1 extension without breaking legacy WebP manifests. Rejects malformed or unknown explicit extensions, including null arrays and versions.
- Selects PNG/WebP according to an explicit target pixel size; defaults to logical reference dimensions. Deterministic area/byte/hash ordering, bounded per-candidate downloads, then legacy WebP fallback. Historical candidates remain cache-only.
- Validates actual raster MIME, declared rendition dimensions, full native decode, hash/byte length, and aspect/pixel bounds before caching or rendering. Returns selected MIME, pixel dimensions, asset ID, hash, source, and rendered release sequence.
- Preserves origin/path binding, pinned signatures, durable high-water marks, same-sequence equivocation checks, concurrent storage guards, and corrupt-state fail-closed behavior.
- SVG candidates are validated but skipped on Android. No native SVG renderer or Compose dependency is added; the demo uses a normal BitmapPainter adapter.

API change: `AssetClient`'s custom decoder now returns `AssetImageInfo?` instead of using the earlier Boolean `validateImage` hook. Default native decoding is enabled even when constructing `AssetClient` directly. Apps using the standard factory need no custom decoder.

Validation: 65 shared signed manifest cases; target ranking, PNG metadata, candidate failure, legacy fallback, historical cache-only and anti-replay unit checks; native PNG/WebP decode and offline restart on Android API 36.1 emulator. See the test reports for exact run counts. Preview status remains; no all-device performance or production support guarantee.
