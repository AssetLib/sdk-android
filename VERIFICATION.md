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
