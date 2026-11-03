# ProxyX

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

People choosing Telegram proxies on their own Android network. Persian RTL and English LTR are existing supported languages.

## Product Purpose

Collect proxies, distinguish untested endpoints from observed Telegram protocol success, compare network-specific evidence, and open a chosen proxy in Telegram.

## Operating Context

The current product is a Kotlin/Jetpack Compose Android application using Material 3, Room, Paging, DataStore, WorkManager and optional TDLib. The owner delegated architecture selection to achieve a user-friendly result. Native Android is selected, retaining the tested network/data core while rebuilding the presentation.

## Capabilities and Constraints

- Preserve source collection, source health, current-network testing, cancellation, staged benchmarking, favorites, comparison, private nodes, settings and optional TDLib functionality.
- Keep MTProto, Telegram WEB and generic HTTP/SOCKS workflows distinct.
- A TCP response is not Telegram success. A protocol response is not measured file throughput.
- Unknown, stale-network, failed and untested measurements must not be presented as fresh success.
- Preserve the original project at C:/Users/User/Desktop/proxyx. Rebuild in this separate project directory.
- Do not read, copy or change protected local configuration, signing material, account sessions or user databases. The redesign copy intentionally excludes these.
- Do not add a web runtime just to use web animation libraries in a native interface.
- The owner requested visual concepts before implementation and will choose the direction before the new interface is coded.

## Brand Commitments

Retain the ProxyX name, existing product functionality and owner attribution. On 2026-11-03 the owner supplied `C:/Users/User/Downloads/Neon Persian VPN App Showcase.png` as the visual reference: a dark, luminous cyan/violet interface with prominent primary controls. This explicit reference replaces the previous light proposals. Native Android affordances and truthful product states remain mandatory. A revised image preview is required before interface implementation.

## Evidence on Hand

Existing source code and docs/audit reports describe the current implementation and historical checks. These are not acceptance evidence for a redesigned APK. Concept images will use clearly identified illustrative data, never claimed as live measurements.

## Product Principles

1. Make selecting and testing a proxy straightforward.
2. Keep connecting to Telegram independent from testing.
3. Reveal technical detail without overwhelming the normal workflow.
4. Preserve truthful network-specific evidence and cancellation.
5. Use familiar, accessible Android interactions and lightweight motion.

## Accessibility & Inclusion

Preserve Persian RTL and English LTR, support system font scaling and reduced motion, retain at least 48 dp touch targets, and never communicate test status through color alone.
