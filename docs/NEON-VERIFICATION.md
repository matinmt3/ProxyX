# ProxyX 8.0.0 Preview: Neon Redesign

Date: 2026-11-03. Status: Debug preview only; final native UI acceptance blocked.

## Scope

The owner approved the neon four-screen concept and prioritized lightweight effects. The original Desktop/proxyx source is preserved. Changes live in this separate native Android copy. This is a presentation rebuild, not a replacement network engine.

| Before | After | Why |
|---|---|---|
| Teal neutral theme; small technical tagline | Dark cyan/violet system, locally bundled Persian font, contextual app bar | Match the approved reference and improve readable hierarchy |
| Fixed selection header consumed the list viewport | Selection/action scrolls with a paged list; back-to-top action retained | Preserve usable list space and large-font access |
| Dense default proxy rows | Compact endpoint/status rows, expand for existing tests, comparison and evidence | Keep essential work visible without deleting advanced functionality |
| Benchmark options and direct speed test preceded primary test controls | Count, measured protocol gauge and actual stages lead; planning details expand separately | Make running, pausing and cancelling easier to reach |
| All advanced settings appeared first | Appearance/network basics lead; advanced settings and maintenance can expand | Reduce default complexity while retaining controls |
| Phone bottom navigation at all widths | Bottom navigation on phones; rail at 600 dp and above; saved page state | Adapt the working UI and retain page state |

## Verification So Far

- Original audit build's baseline test task completed successfully in 42 seconds, up-to-date. This was not a fresh re-execution of all baseline test bodies.
- First redesign compilation failed because the DarkMode icon import was missing; corrected.
- Final unit test result XML: 361 tests across 64 suites, 0 failures, 0 errors, 0 skips. Nineteen new cases cover stage/measurement presentation, contrast, theme persistence, compact sizing, endpoint direction and private-node evidence freshness.
- Final combined test/lint/APK/instrumentation-APK build: BUILD SUCCESSFUL in 7m 1s, 88 tasks (20 executed, 68 up-to-date). Log: ../proxyx-neon-review-final-20261103.log.
- Lint: 0 errors, 5 warnings (1 GradleDependency, 3 UseKtx, 1 UseTomlInstead). These warnings are not described as fixed.
- Final API 34 emulator instrumentation: 7 tests passed in 4.161 seconds. These cover database migrations and TDLib native runtime loading, not account login or real file transfer. Repeating the earlier same seven tests is not counted as fourteen distinct tests.
- Debug APK signature verified (v2, Android Debug certificate); 16 KiB zip alignment check exited successfully. Manifest: version 8.0.0-preview / 76, minSdk 24, targetSdk 36, arm64-v8a / armeabi-v7a / x86_64.
- APK SHA-256: 37797879762A4D3C642B7AAFCB13A253CC03395D8B87B8D34CE73CA1C8C33F4E.

## Implementation Boundaries

- Main Telegram database schema and network protocol engines remain unchanged. Only the fresh-install theme default changed in settings persistence; explicit existing dark/light/system choices are preserved.
- No continuous 3D renderer, web animation runtime, background shader or animated blur was added. The focal ring uses bounded native drawing.
- The image generator's sample values were never inserted into the app database. Current-network latency requires actual verified protocol evidence.
- A completed pipeline with no candidates for later stages displays those stages as not run. Optional transfer is separate and is not marked completed from protocol checks.
- The existing source, favorites, comparison, private, WEB and generic HTTP/SOCKS routes remain separate and accessible.
- Protected signing/Telegram configuration, sessions and private databases were excluded. Authenticated Telegram login, Saved Messages and real transfer cannot be accepted from this credential-free build.

## Design Tool Limits

The approved composition is a four-screen design board, not a browser viewport. Native semantics, font scaling and Android screenshots are the implementation acceptance evidence. The generic font-match heuristic misclassified Persian lettering as Latin decorative scripts; that recommendation is not appropriate for the product. The implementation uses the approved Persian-capable Vazirmatn face with Material typography roles instead.

## Emulator Resource Limit

The first native UI pass produced usable four-screen screenshots but also a ProxyX focus-timeout ANR during live display resizing. Its full app stack timed out; exact attribution is unresolved (NEON-ANR-TRIAGE.md). The final build's repeat used the laptop Intel host GPU after Telegram and Teams were closed with owner permission. No account logout, private-file modification or operating-system service disabling was performed. No standalone browser tabs were open to close. The networking tool and Codex remained running.

Even after this cleanup, Windows free physical memory fell below 100 MiB with the 2 GiB emulator running. Android system_server and System UI displayed repeated not-responding dialogs; the final am start -W timed out after 13.161 seconds. These are recorded environment failures, not evidence that the app is smooth or that the earlier app ANR is resolved. Instrumentation did finish successfully despite the slow surrounding environment. No physical-device frame-rate or 120 Hz claim is made.

## Pending Native Acceptance

The emulator was stopped after repeated system ANRs. Inspect the final compact/wide/scaled-font layouts and exercise the main workflows on a usable emulator or device before release. Build and unit tests do not substitute for those flows. Authenticated Telegram transfer and release signing are outside this credential-free preview's acceptance scope.

The independent finish review returned **recapture**, with source corrections acknowledged but their final rendered behavior unverified. See NEON-FINISH-REVIEW.md. This package does not claim release acceptance, complete button coverage, resolved resize ANR, authenticated transfer success or measured physical-device smoothness.
