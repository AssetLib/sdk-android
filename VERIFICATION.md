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

This records the uncommitted Kotlin implementation of staging, appearance/arm cells, and decision callbacks. It does not update the published SDK version or the earlier release evidence above.

1. Files added and changed: added `sdk/src/test/java/com/assetlib/sdk/VariantTest.kt`; changed `sdk/src/main/java/com/assetlib/sdk/{Protocol,AssetClient,AndroidAssets}.kt`, `sdk/src/test/java/com/assetlib/sdk/{AssetClientTest,FixtureDecoder}.kt`, `README.md`, and this file. Earlier accessibility/code-generation work and the H0 fixture changes were preserved. No dependency, fixture, or release-version changes were made for H2.

2. Exact verification commands and output:

`./gradlew :sdk:testDebugUnitTest :sdk:lint --offline` exited 1 before compilation or lint:

```text
Exception in thread "main" java.io.FileNotFoundException: /Users/tylerzhao/.gradle/wrapper/dists/gradle-8.14.3-all/10utluxaxniiv4wxiphsi49nj/gradle-8.14.3-all.zip.lck (Operation not permitted)
	at java.base/java.io.IoOverNioFileSystem.convertNioToIoExceptionInStreams(IoOverNioFileSystem.java:123)
	at java.base/java.io.IoOverNioFileSystem.initializeStreamsUsingNio0(IoOverNioFileSystem.java:377)
	at java.base/java.io.IoOverNioFileSystem.initializeStreamUsingNio(IoOverNioFileSystem.java:344)
	at java.base/java.io.RandomAccessFile.<init>(RandomAccessFile.java:298)
	at java.base/java.io.RandomAccessFile.<init>(RandomAccessFile.java:244)
	at org.gradle.wrapper.GradleWrapperMain.main(SourceFile:67)
Caused by: java.nio.file.FileSystemException: /Users/tylerzhao/.gradle/wrapper/dists/gradle-8.14.3-all/10utluxaxniiv4wxiphsi49nj/gradle-8.14.3-all.zip.lck: Operation not permitted
	at java.base/sun.nio.fs.UnixException.translateToIOException(UnixException.java:102)
	at java.base/sun.nio.fs.UnixException.rethrowAsIOException(UnixException.java:108)
	at java.base/sun.nio.fs.UnixException.rethrowAsIOException(UnixException.java:114)
	at java.base/sun.nio.fs.UnixFileSystemProvider.newFileChannel(UnixFileSystemProvider.java:224)
	at java.base/java.io.IoOverNioFileSystem.initializeStreamsUsingNio0(IoOverNioFileSystem.java:359)
	... 4 more
```

**Gradle cannot run in this environment.** The sandbox prohibits creating its wrapper lock under `~/.gradle`; the operator must run the requested Gradle tasks.

As a supplemental offline check, a temporary runner compiled every main and JVM test Kotlin source with cached Kotlin 2.2.20, JDK 17/JVM target 17, the installed Android 36 `android.jar`, and the existing dependency versions. It then ran all four test classes with JUnit 4.13.2. No dependencies were installed or changed. The JVM fixture decoder is used, not Android's actual bitmap decoder. `ASSETLIB_PUBLIC_CONFIG_FILE` was explicitly empty, so the optional hosted smoke test was assumption-skipped (37 passed, one skipped, zero failures).

`python3 /private/tmp/assetlib-h2-verification/run-jvm.py --compile-only` exited 0:

```text
Kotlin 2.2.20 compilation (JVM target 17): 0
```

`python3 /private/tmp/assetlib-h2-verification/run-jvm.py` exited 0:

```text
Kotlin 2.2.20 compilation (JVM target 17): 0
JUnit version 4.13.2
......................................
Time: 0.76

OK (38 tests)

```

This includes all 100 signed manifest corpus cases with their selected configurations, all 18 resolution entries, six stateful entries, and two byte-failure entries. Additional signed mutation tests cover axis/schema/cell boundaries, cell rendition/accessibility validation, ignored native states, staging isolation, callback reentry/cancellation/failure, and cell selection through cache/restart/historical fallback. Original decoder and positional format call shapes are also exercised.

`git diff --check` exited 0 with no output.

3. Deviations: no implementation deviations. Direct compiler/JUnit execution supplements the blocked Gradle command; it does not establish an Android Gradle build or lint success. Native state sets remain ignored as specified.

4. Unfinished: operator execution of `./gradlew :sdk:testDebugUnitTest :sdk:lint --offline`. No H2 implementation work remains. No commit, stash, deployment, publication, or version bump was performed.
