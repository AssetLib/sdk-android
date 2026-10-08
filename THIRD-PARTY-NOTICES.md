# Third-party components

The SDK is MIT licensed. Dependencies retain their own licenses:

- Bouncy Castle `bcprov-jdk18on` 1.86: Bouncy Castle License (MIT-style); exact license retained in `licenses/bouncycastle-LICENSE.html`. Used directly for Ed25519 primitives with full public-point validation; no global JCA provider registration. Source: https://github.com/bcgit/bc-java/tree/r1rv86 .
- Kotlin standard library 2.2.20 and kotlinx coroutines 1.10.2 / serialization 1.9.0: Apache 2.0. https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines and https://github.com/Kotlin/kotlinx.serialization .
- OkHttp 4.12.0 / Okio: Apache 2.0. https://github.com/square/okhttp/tree/parent-4.12.0 .

The AAR contains SDK code, not a shaded copy of these runtime dependencies. Applications must retain applicable dependency license notices in their distribution. The demo's release build enables R8 shrinking, but no specific size saving is promised.
