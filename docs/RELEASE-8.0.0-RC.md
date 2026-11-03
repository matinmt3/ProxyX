# ProxyX 8.0.0-rc5

Experimental native Android release candidate, version code 81.

## Changes

- Native dark cyan/violet interface with bundled Persian font, RTL/LTR,
  light/dark appearance, paged results and adaptive navigation.
- Separate Telegram opening, protocol tests and optional authenticated file
  transfer. Real speed values require actual transfer evidence.
- Explicit TCP/MTProto latency labels; current-network evidence is distinguished
  from historical results.
- Accurate small overall-progress labels, accessibility names for transfer and
  private-node switches, and an accessible side-label gauge at larger font sizes.
- Protected external API/signing inputs and a fail-closed publication-signing
  build option. Keys, API configuration and sessions are not public source files.

## Verification Boundary

See PHONE-QA-20261103.md. The live four-stage run used eight MTProto proxies:
seven reached native file transfer, three succeeded, four failed, none skipped.
Those are public-relay results, not a promise that all proxies are available.
The 1100-entry queue was started, paused, resumed and deliberately cancelled;
it was not run to completion. The all-count input also accepted 1592.

Known limitations remain: diagnostic scroll jank on the API 26 phone, an
unreachable WEB provider/feeds on that network, incomplete modern-Android and
16 KiB runtime acceptance, and no configured signed in-app update manifest.
Export/import full round-trip and some positive destructive paths are not
accepted by this device run. No stable or universal-compatibility claim is made.

## Source And Licenses

Distribute matching source with the APK. Preserve the GPL-2.0 transport notice
and license in third_party/telegram-web and all bundled dependency/font notices.
See third_party/tdlib/README.md for the native runtime's provenance and build
instructions. Source builds need the builder's own private API/signing inputs.

The poster in media/proxyx8-promo.png is AI-generated promotional artwork, not
a device screenshot or a measurement. Real app captures are supplied separately.
