# ProxyX Visual Concepts

Date: 2026-11-03

Status: the three light concepts below were not approved and have been superseded by the owner's neon reference. See NEON-REFERENCE-BRIEF.md and ../.impeccable/mocks/04-neon-four-screens.png for the revised preview. No new application UI has been implemented. These are AI-generated design concepts, not screenshots of a running application. Every endpoint, count and measurement in them is illustrative.

## Shared Native Direction

Material 3 controls, readable Persian RTL, separate test/connect actions, a light neutral working surface, cobalt primary actions, emerald confirmed states and amber cautions. The one recurring signature is a compact sequence of protocol/stability checkpoints. Controls and labels will be semantic native components, not image assets.

## 1. Clear Routes

Image: ../.impeccable/mocks/01-clear-routes.png

Selected proxy and its evidence appear above the list. The connection and retest actions are immediately visible. Trade-off: the fixed summary leaves less first-screen space for the list. Long names, large text and the summary's maximum height need implementation testing.

## 2. List First

Image: ../.impeccable/mocks/02-list-first.png

Search and filters precede a larger scan-friendly list. A contextual tray keeps the selected host, test action and Telegram action in reach. Recommended home composition for repeated proxy selection. Trade-off: the tray must avoid covering content or consuming excessive height with large text or the keyboard.

## 3. Test Guided

Image: ../.impeccable/mocks/03-test-guided.png

The testing destination leads with the user-network context, a count field, real phase progress and compact results. This complements option 2 as a dedicated benchmark screen rather than duplicating testing on Home. Trade-off: actual Pause, Resume, Cancel, failure, interrupted and optional TDLib stage states still need to be designed and wired to existing state.

## Translation Boundaries

- Blue and green concept fills have subtle generation shading; implementation uses deliberate Material color roles, not decorative gradients.
- The current-network row is informative, not a network selector or a claim that the app can switch Wi-Fi.
- A completed checkpoint may be drawn only when that specific observed phase succeeds. Optional TDLib transfer is separate and requires authorized login.
- Domain names stay LTR inside RTL layouts. Prototype sample values are not shipped fixtures in the real app.
- Icon weight, typography, shape roles, large-font reflow, dark theme and the exact text will be normalized and verified in native implementation.
- No image of UI will be shipped as the interface itself.

## Selection

Pending. Do not set a concept approved or begin interface implementation until the owner chooses a concept, requests a combination or explicitly delegates the choice.
