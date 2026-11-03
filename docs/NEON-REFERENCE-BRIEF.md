# Neon Reference Revision

Date: 2026-11-03

## Owner Direction

Use the supplied `Neon Persian VPN App Showcase.png` as the visual reference, while prioritizing a user-friendly and smooth native Android app. Previous light concepts are not approved. Application code remains unchanged during the revised preview round.

## What Carries Forward

- Near-black background, dark surfaces, luminous cyan and violet accents.
- Strong primary circular control, clear Persian typography and familiar bottom navigation.
- Highlighted selected states, compact technical rows and restrained depth.
- A coherent design across Home, testing, the library and settings.

## What Must Not Be Copied As Product Truth

- ProxyX is not a device-wide VPN. Opening a proxy link does not prove that Telegram is connected.
- Do not add unsupported VLESS, VMess, Trojan, Shadowsocks or VPN controls just because the reference shows them.
- Do not claim unlimited access, security guarantees or working proxy status without evidence.
- Show file throughput only when a real authorized TDLib transfer measured it. Missing values stay unknown.
- The owner image is a design reference, not evidence of working code or benchmark results.

## Performance Contract For Implementation

- Keep the native Compose renderer and paged database list.
- Draw bounded ring/progress accents with native primitives; avoid an embedded web animation runtime or decorative 3D scene.
- No continuously animated blur or background shader. Background accents are static and lightweight.
- Animate only meaningful changes; stop animation when not visible and respect system animation settings.
- Stable row keys, reserved measurement space and bounded expanding details; large lists must not recompose as a whole for every progress tick.
- Test small-screen RTL, long domains, system font scale, dark/light behavior, cancellation and app backgrounding on the implemented build.
- Emulator screenshots do not establish 120 Hz performance. Device performance remains a separate verification item.

## Preview Data

Any hosts, latency or counts drawn in the concept images are illustrative. They are not real test results and must never be seeded into the application's user database.

## Approval

The style reference is confirmed. The revised composition preview awaits owner approval before UI code is written.

Preview: ../.impeccable/mocks/04-neon-four-screens.png. Produced with the built-in image generator, using the owner's image only as a style reference. The full generation prompt is retained in its JSON sidecar and image metadata.

Before implementation, normalize the active-stage marker: a running stage must not be rendered as a completed aggregate stage merely because the concept image uses three check icons. The 64/100 and 54 ms shown in the concept are illustrative, not observed app measurements. The source/library navigation must retain all existing routes; the preview shows only the first viewport.
