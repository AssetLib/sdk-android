# Verification — October 10, 2026: release v0.4.0-preview.1 (tintable icons)

Release AAR `assetlib-android-0.4.0-preview.1.aar`, SHA-256 `03d55eaa1975fe86494fb2f65e69e0ad7ad3f5a60d492f02d3c06bf3bdc9a98a`, built on macOS with JDK 17 by `./gradlew :sdk:testDebugUnitTest :sdk:lint :sdk:assembleRelease` (102 tests, 0 failures, 1 skipped; lint clean) and `node scripts/prepare-release.mjs`. Earlier checks on branch `feat/tintable-icons`:

- `node --test scripts/codegen.test.mjs`: 3 passed. A catalog without template placements, and one with explicit `"original"`, generate output byte-identical to the previous generator.
- `./gradlew :sdk:testDebugUnitTest :sdk:lint`: exit 0. JVM unit tests: 102 run, 0 failures, 1 skipped (the optional hosted test, without `ASSETLIB_PUBLIC_CONFIG_FILE`). Lint clean. The corpus has 115 signed manifest cases and 10 rendering resolution cases; bundled rendering cases assert that no artwork body is requested.
- The fixture directory is byte-identical to the generated shared corpus (196 files).

Not run: the release build, instrumented tests, any emulator or device check, and CI.

# Verification — October 9, 2026: release v0.3.1-preview.1

Release `v0.3.1-preview.1` (tag on commit `a7f40eb`), AAR SHA-256 `d3f725c1270987ae06da4f5f9cf0673e165f2b2fd7449537adfff367460a1bc3`:

- `node --test scripts/codegen.test.mjs`: 2 passed.
- `./gradlew :sdk:testDebugUnitTest :sdk:lint :sdk:assembleRelease`: exit 0. JVM unit tests: 98 run, 0 failures, 1 skipped (the optional hosted test, without `ASSETLIB_PUBLIC_CONFIG_FILE`). Lint clean. The new parameterized runner checks all 43 shared public-config cases and 39 pinned-envelope expectations; a regression covers exactly-4096-byte configurations, including set-only and explicit non-first single-pin forms.
- `node scripts/prepare-release.mjs` produced the AAR and `SHA256SUMS` attached to the release; `shasum -a 256 -c SHA256SUMS` passed.
- `./gradlew :sdk:connectedDebugAndroidTest` on an Android 16 (API 36) emulator: 5 passed, 0 failed (`AccessibilitySemanticsTest` and the four `AndroidAssetsTest` cases).
- The fixture directory is byte-identical to the generated shared corpus (180 files).
- GitHub Actions passed for the release commit.

Not established: no emulator or device run of the configuration changes inside a real app, no hosted publish, refresh and rollback with a native app on this version, and no physical device check.

# Verification — October 9, 2026 (America/New_York)

Release `v0.3.0-preview.1` (commit `4d6745c`), rechecked on `main` with the restored accessibility instrumentation test:

- JDK 17, Gradle 8.14.3, Android Gradle Plugin 8.13.2, SDK 36 / build-tools 36.1.0.
- `node --test scripts/codegen.test.mjs`: 2 passed.
- `./gradlew :sdk:testDebugUnitTest :sdk:lint :sdk:assembleRelease :sdk:compileDebugAndroidTestKotlin`: exit 0. JVM unit tests: 54 run, 0 failures, 1 skipped (the optional hosted test, without `ASSETLIB_PUBLIC_CONFIG_FILE`). Lint clean. The shared corpus covers 100 signed manifest cases (production and staging), 18 resolution entries, 6 stateful entries, 2 byte-failure entries, and 4 rendition selections.
- The release AAR contains no debug-only classes or activities.
- `./gradlew :sdk:connectedDebugAndroidTest` on an Android 16 (API 36) emulator: 5 passed, 0 failed. That is `AccessibilitySemanticsTest` (restored from `v0.2.1-preview.1`) and the four `AndroidAssetsTest` cases: native PNG and WebP decoding, signed dimension and MIME rejection, and offline restart.
- GitHub Actions passed for the release commit ([run 37888080051](https://github.com/AssetLib/sdk-android/actions/runs/37888080051)).

Not established: no emulator or device run of the staging, variant cell, or decision callback paths in a real app; no hosted publish, refresh and rollback with a native app on this version; no physical device, API 26 device, manual TalkBack, or performance check.

# Verification — October 7, 2026 (America/New_York)

Local evidence for SDK 0.1.0-preview.1, not a claim of broad production readiness:

- JDK 17, Gradle 8.14.3, Android Gradle Plugin 8.13.2, SDK 36 / build-tools 36.1.0.
- `:sdk:testDebugUnitTest`: **14 passed, zero skipped/failures**, including the opt-in read-only hosted test. That test accepted the real hosted signed release, downloaded/verified artwork, then loaded an independent client with network disabled from persisted state and cache.
- Shared interop corpus: **43 signed manifest cases**. Additional stateful tests reject stale sequence, changed/reformatted payload with the same sequence, and Unicode normalization differences. Integer JSON representations such as `1.0`/`1e0` match JavaScript's integral-number semantics.
- `:sdk:connectedDebugAndroidTest`: **2 passed** on the existing Android 36.1 arm64 emulator. Native WebP decoding, logical 600×450 versus actual 1200×900 dimensions, invalid content/aspect rejection, and independent offline restart were exercised.
- `:sdk:lint` and `:sdk:assembleRelease`: passed. Node offline code-generation tests: passed.
- Bouncy Castle 1.86 resolved from Maven Central. OSV direct-version query returned zero advisories at verification time; this is not proof that the dependency or SDK has no vulnerabilities.
- Release AAR SHA-256: `adb0f4fdc5ceaa69c2092e3987ebc17afc0fb5f34bbf7576eea025147445eba2`. The AAR contains SDK classes/manifest/rules/metadata, no fixture assets, workspace configuration, credentials, or third-party runtime jars.

The separately built native demo also exercised this released AAR on the emulator against all three hosted artwork placements, decoded their real images, and resolved a cached image after an independent client restart with network explicitly disabled. Its normal app APK contains no hosted test configuration.

Manual emulator UI inspection, physical-device testing, Android 26 device coverage, real-device memory/performance characterization, app-store submission, key rotation, hardware-backed rollback resistance, and independent security audit were not established by these checks. Remote CI initially exposed missing command-line tools and a retired tools-package default. Explicit setup fixed both. [CI run 37719685676](https://github.com/AssetLib/sdk-android/actions/runs/37719685676) passed at commit `5e1663b9a9833d107cbd4183656907b85b94ce65`. Its Linux-built AAR was downloaded and has the exact same SHA-256 as the published artifact above. Those post-release commits change CI only; release runtime source remains `f2dafc0bd82114128ad5e0fa5f48c7ebcf5188b2`.

## October 8, 2026 — Phase 4 H2 source verification

Recorded while the staging, appearance and arm cell, and decision callback work was still uncommitted. It was committed and released as `0.3.0-preview.1` on October 9, 2026; the Gradle tasks this entry could not run were run for that release (see the October 9 entry above).

At the time, `./gradlew :sdk:testDebugUnitTest :sdk:lint --offline` exited 1 before compiling because the sandbox could not create the wrapper lock under `~/.gradle` (`Operation not permitted`). As a supplemental check, a temporary runner outside this repository compiled every main and JVM test Kotlin source with Kotlin 2.2.20, JDK 17, and the Android 36 `android.jar`, then ran the four JVM test classes with JUnit 4.13.2: 38 tests, all passing, with the optional hosted test skipped. That covered all 100 signed manifest cases with their configurations, all 18 resolution entries, 6 stateful entries, and 2 byte-failure entries, plus signed mutation tests for variant axes, cells, staging isolation, decision callbacks, and cache, restart, and historical fallback. A direct compiler and JUnit run is not evidence of a Gradle build or lint pass; those came with the release.
