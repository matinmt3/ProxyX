---
name: ProxyX Native Neon
description: Persian-first native proxy utility with bounded cyan/violet emphasis; debug preview only.
colors:
  primary: "#22D9E8"
  on-primary: "#00363D"
  primary-container: "#103840"
  on-primary-container: "#B6F8FF"
  secondary: "#B79AFF"
  on-secondary: "#2D1555"
  secondary-container: "#37265E"
  on-secondary-container: "#F0E7FF"
  tertiary: "#FFCF77"
  on-tertiary: "#422D00"
  tertiary-container: "#5A4108"
  on-tertiary-container: "#FFE5AD"
  background: "#080D1B"
  surface: "#111B30"
  on-background: "#F3F6FF"
  on-surface: "#F3F6FF"
  surface-variant: "#1B2941"
  on-surface-variant: "#B2BDD1"
  outline: "#7183A3"
  outline-variant: "#344762"
  error: "#FFB4AB"
  on-error: "#690005"
  error-container: "#5B1B21"
  on-error-container: "#FFDAD6"
typography:
  headlineLarge: { fontFamily: "Vazirmatn", fontSize: "32sp", fontWeight: 700, lineHeight: "40sp", letterSpacing: "0sp" }
  headlineSmall: { fontFamily: "Vazirmatn", fontSize: "24sp", fontWeight: 700, lineHeight: "32sp", letterSpacing: "0sp" }
  titleLarge: { fontFamily: "Vazirmatn", fontSize: "22sp", fontWeight: 600, lineHeight: "28sp", letterSpacing: "0sp" }
  titleMedium: { fontFamily: "Vazirmatn", fontSize: "16sp", fontWeight: 600, lineHeight: "24sp", letterSpacing: "0sp" }
  titleSmall: { fontFamily: "Vazirmatn", fontSize: "14sp", fontWeight: 600, lineHeight: "20sp", letterSpacing: "0sp" }
  bodyLarge: { fontFamily: "Vazirmatn", fontSize: "16sp", fontWeight: 400, lineHeight: "24sp", letterSpacing: "0sp" }
  bodyMedium: { fontFamily: "Vazirmatn", fontSize: "14sp", fontWeight: 400, lineHeight: "20sp", letterSpacing: "0sp" }
  bodySmall: { fontFamily: "Vazirmatn", fontSize: "12sp", fontWeight: 400, lineHeight: "18sp", letterSpacing: "0sp" }
  labelLarge: { fontFamily: "Vazirmatn", fontSize: "14sp", fontWeight: 600, lineHeight: "20sp", letterSpacing: "0sp" }
  labelMedium: { fontFamily: "Vazirmatn", fontSize: "12sp", fontWeight: 500, lineHeight: "18sp", letterSpacing: "0sp" }
  labelSmall: { fontFamily: "Vazirmatn", fontSize: "11sp", fontWeight: 500, lineHeight: "16sp", letterSpacing: "0sp" }
rounded:
  extraSmall: "4dp"
  small: "8dp"
  medium: "8dp"
  large: "8dp"
  extraLarge: "8dp"
spacing:
  gap-4: "4dp"
  gap-6: "6dp"
  gap-8: "8dp"
  gap-10: "10dp"
  gap-12: "12dp"
  inset-14: "14dp"
  inset-16: "16dp"
components:
  button-benchmark:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.small}"
    height: "56dp"
  button-open-expanded:
    rounded: "{rounded.small}"
    size: "48dp"
  proxy-row:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.small}"
    padding: "12dp"
  vault-entry:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.small}"
    padding: "14dp"
  chip-library-selected:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.on-secondary-container}"
  settings-row:
    typography: "{typography.titleSmall}"
    padding: "10dp 16dp"
  navigation-selected:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.secondary}"
  telegram-action:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.primary}"
---

# Design System: ProxyX Native Neon

## Overview

**Creative North Star: "The Neon Proxy Console"**

A near-black native Android utility with luminous cyan actions and violet selection, grounded in the owner's approved Persian four-screen concept. Vazirmatn, readable evidence and familiar Material 3 interactions carry the identity. The previous light design proposals were rejected; the implemented optional light theme is a user preference, not a competing approved concept.

The visual world supports choosing an endpoint, inspecting network-specific evidence, testing independently and opening Telegram. Light comes from bounded native drawing and tonal surfaces, not a web runtime or continuous effects. Keep the six-block surface contract and its quality bar in `docs/NEON-SURFACE-BRIEF.md`; the four-screen composition is not a universal screen template.

This is a source-grounded record of a **DEBUG PREVIEW**, not final rendered acceptance. Authority: `app/src/main/java/com/example/ui/theme/{Color,Theme,Type}.kt`, `ui/NeonComponents.kt`, `ui/NeonPresentation.kt`, `ui/{DashboardScreen,BenchmarkScreen,SettingsScreen,VaultScreen,WebProxyPanel}.kt` and `MainActivity.kt`, with all Java paths relative to `app/src/main/java/com/example/`. Direction: `PRODUCT.md`, the surface brief and `.impeccable/mocks/04-neon-four-screens.png`.

Evidence freshness matters: the first-pass captures under `artifacts/neon-device/` and the compilation/tests reported in `docs/NEON-VERIFICATION.md` are historical, not acceptance of the current source. The owner identifies `artifacts/phone-20261103/review-phone-{home,test,library,settings}.png` as physical-device **RC3** captures, not RC5. RC5 compilation was reported in progress at the start of this refresh; its result was not checked here. Current component rules below are source-backed only. Earlier emulator system ANRs and the ProxyX resize/focus-timeout finding remain historical unresolved evidence, neither attributed to nor cleared by RC5. Current compact, wide, scaled-font and workflow acceptance still requires native verification. This pass read source/context and inventoried the RC3 filenames; it did not inspect rasters, run builds, perform device/UI automation or assert preview pixel gates.

**Key Characteristics:**
- Persian RTL and English LTR with explicitly LTR endpoint text.
- Cyan action/evidence, violet selection and restrained amber uncertainty.
- Outlined native surfaces, compact controls and bounded Canvas rings.
- Scrollable work areas with adaptive native navigation.
- Source-recorded design; final rendering and release acceptance remain pending.

## Colors

The fixed palette combines near-black indigo surfaces, ice-white text and two distinct luminous accents; frontmatter values are the exact opaque Kotlin colors, not screenshot samples.

### Primary
- **Signal Cyan** (`primary`, `on-primary`): primary buttons, Telegram action, protocol evidence and selected utility icons.
- **Deep Cyan Well** (`primary-container`, `on-primary-container`): the explicitly declared primary container pair. Use Material roles rather than raw colors at call sites.

### Secondary
- **Selection Violet** (`secondary`, `on-secondary`): selected navigation and favorites; the stage strip also uses violet for active or paused states.
- **Violet Well** (`secondary-container`, `on-secondary-container`): selected library chips and sorting segments. Navigation deliberately uses `secondary` for selected icon/text over the indicator, not the chip's content role.

### Tertiary
- **Caution Amber** (`tertiary`, `on-tertiary`, `tertiary-container`, `on-tertiary-container`): incomplete/TCP-only evidence and network mismatch warnings, not Telegram success.

### Neutral
- **Night Field** (`background`): the scaffold, app bar and navigation chrome.
- **Panel Ink** (`surface`, `surface-variant`): repeated entries and local grouping; translucent variants remain native role copies rather than new solid tokens.
- **Ice Text** (`on-background`, `on-surface`): primary readable content.
- **Quiet Text** (`on-surface-variant`): metadata, untested and old-network evidence.
- **Measured Outline** (`outline`, `outline-variant`): control and entry boundaries.
- **Failure Coral** (`error`, `on-error`, `error-container`, `on-error-container`): failure, interruption and destructive state. The source's cancel button overrides its container only; do not infer a custom content-color override.
- **Optional Light Theme:** retained in `LightColorScheme`, not duplicated into this compact dark-world token set. Unspecified Material defaults are unknown here, not extracted or guessed. Dynamic color is disabled by default, and `MainActivity` does not opt into it; system theme selection is separate from dynamic color.

### Named Rules
**The Evidence Before Glow Rule.** Color accompanies explicit status text or icons; only fresh, current-network protocol evidence earns a measured latency. Unknown, historical and untested data never borrows a success treatment.

## Typography

**Display Font:** Vazirmatn, native bundled resource.
**Body Font:** Vazirmatn, the same native family.

**Character:** Persian-capable, calm and work-focused. `Type.kt` bundles regular and bold resources (`R.font.vazirmatn_regular`, `R.font.vazirmatn_bold`); medium and semibold are requested Material weights, not separately evidenced font files. There is no invented Latin display pairing or web-font stack.

### Hierarchy
- **Display:** declared in `Type.kt`, but omitted from this compact token set because these working screens do not establish hero-scale use. Other unused headline/label declarations are likewise not promoted.
- **Headline:** `headlineLarge` supplies the regular latency numeral; `headlineSmall` supplies the compact large-font numeric face.
- **Title:** `titleLarge` is the app title with an explicit bold override in the shell; `titleMedium` serves section/action headings; `titleSmall` serves endpoint and settings titles, sometimes explicitly bold.
- **Body:** `bodyMedium` carries endpoint rows, `bodySmall` metadata and status; native fields inherit Material text styling.
- **Label:** `labelMedium` is reused for bottom-navigation labels, stage labels and small context. `labelSmall` captions the Home MTProto/TCP metric; selected-endpoint values use `labelLarge`, row values `labelMedium`. Labels retain natural Persian/English case.

All sizes and line heights above are Android `sp`; layout values are `dp`. Font scale is system-driven, not viewport-driven. `endpointStyle()` changes text direction to `TextDirection.Ltr` without replacing the family. Selected endpoint alignment follows language; favorite-history text uses directional isolation. Some inherited detail views still contain ad hoc small `sp` overrides and a monospace metric override. Those are observed drift, not additions to the shared type ramp, and their readability is not accepted by this document.

### Named Rules
**The Unbroken Script Rule.** Keep Vazirmatn and zero letter spacing in shared typography; isolate endpoint direction without reversing Persian layout or introducing a decorative display face.

## Layout

`MainActivity` uses `BoxWithConstraints` and an inset-aware `Scaffold`. Below (600dp) it shows four bottom destinations; at (600dp) and above it replaces them with a native navigation rail. Persian layout places the rail on the RTL start side; English uses LTR. The working page is top-centered and constrained to a maximum (1000dp). `rememberSaveableStateHolder` preserves page state, with saveable main/library selection and Back returning to Home.

Common content insets are (16dp). Home and settings use lazy lists; benchmark uses a vertically scrollable column. Repeated gaps are (4/6/8/10/12dp), with (14dp) entry padding in the vault. These are observed reusable steps, not a claim of a universal eight-point grid. The selected endpoint/action is a scrolling Home item, not fixed chrome. The paged Home list reserves (80dp) bottom content padding.

Home uses the compact ring below (560dp) of available content height and hides the large circular action only when filters are visible or available height is below (360dp); the endpoint then retains a compact Telegram button. Benchmark also selects the compact gauge below (560dp) available height. These are content constraints after shell layout, not physical screen-height breakpoints. Library categories scroll horizontally; sorting uses native single-choice segments with physical end-corner handling for RTL, not independent pills.

Within a compact gauge only, system font scale above (1.15) switches to a (96dp) numeric face with captions beside it, separated by (16dp) and constrained to at most (180dp) caption width. At or below that scale, compact retains the (140dp) face with internal captions; noncompact remains (176dp). This source-defined reflow is not proof that RC5 avoids clipping on a device.

The product requires at least (48dp) touch targets and system font scaling. Several action controls explicitly meet that minimum in source, but this is not a blanket touch-target or scaled-font audit pass. Fixed-height benchmark controls, long labels, compact geometry and resize behavior still need final native capture.

## Elevation & Depth

Depth is principally tonal: a night field, brighter panels and thin outlines. Proxy rows and settings groups explicitly use zero card elevation, and bottom navigation uses zero tonal elevation. Other Material controls keep their library defaults where not overridden; this is not a claim that every native surface has zero elevation. Do not invent a CSS shadow scale or flatten legitimate Material interaction feedback.

The Telegram ring is a remembered sweep brush drawn into a bounded Canvas: an (18dp) stroke at alpha (0.12), a (6dp) main stroke and a (1dp) outer line at primary alpha (0.25). The latency gauge uses a (7dp) round-capped arc with (33) ticks; the unmeasured arc is attenuated to alpha (0.35). These are draw operations, not blur, a connection animation or evidence of throughput. No continuous blur, 3D renderer, web runtime or background shader was added for decorative neon. The protocol-specific WEB panel's functional WebView is separate from this drawing policy. Event-driven favorite scaling and a (180ms) progress tween remain; reduced-motion behavior and smoothness have not been verified in final rendering.

### Named Rules
**The Bounded Light Rule.** Keep luminous emphasis local to native ring/gauge drawing and state; do not add continuous blur, 3D or a web animation runtime to reproduce the approved concept.

## Shapes

`AppShapes` assigns (4dp) to `extraSmall` and (8dp) to small, medium, large and extraLarge. Repeated proxy/vault entries, settings groups and explicitly shaped controls use (8dp) corners; their regular borders use (1dp) `outlineVariant`. This is not a global ban on round controls: `CircleShape` is native to the Telegram action, stage markers and selected utility treatments. Material buttons/chips/indicators without shape overrides retain their native defaults; notably the Home test action does not declare an (8dp) shape. Do not infer its shape from `AppShapes` alone.

## Components

### Buttons
Direct native commands, with icon semantics and state-aware availability.
- Benchmark start/pause/resume/cancel controls are explicitly (56dp) high with (8dp) corners; the cancel container uses the error role. Disabled start uses primary at alpha (0.30).
- Home testing uses a full-width `OutlinedButton` with a minimum (48dp) height and vertical (8dp) external padding. It switches its command/icon to cancellation while testing; opening Telegram remains independent.
- Expanded row tools use (48dp) icon targets; the explicit Telegram `FilledIconButton` is an (8dp) square. Native Material defaults supply state layers, ripple, focus and disabled handling; no custom hover/focus timing was extracted.

### Chips
Native `FilterChip` categories use secondary-container/on-secondary-container when selected. Sorting is a `SingleChoiceSegmentedButtonRow`, with (8dp) only on physical outer ends and square shared edges. Preserve semantic selection and RTL ordering. The malformed first-pass segmented corners are not a reusable pattern.

### Cards / Containers
Repeated entries use surface, outline-variant borders and the shared compact shape. Home rows have (12dp) internal padding; vault entries have (14dp). Selected Home rows use secondary-container at alpha (0.35) and a secondary border. Settings groups have no shared outer internal padding: each row owns horizontal (16dp), vertical (10dp) insets, and dividers are inset (16dp) with on-surface alpha (0.08). Do not generalize screen-specific grouping into nested decorative cards.

Home metric captions name the evidence, not generic ping: a current reachable row shows `MTProto` when eligible protocol latency is selected, otherwise `TCP` with TCP latency; TCP-latency sorting explicitly chooses the latter. Historical rows suppress this compact value. The noncollapsed selected endpoint shows an `MTProto` caption only with eligible protocol latency; its collapsed layout uses the Telegram action in that position. Do not silently substitute TCP under an MTProto label or infer protocol success from reachability.

### Inputs / Fields
`OutlinedTextField` retains native Material focus, error and disabled behavior, not an invented neon focus glow. The benchmark count is single-line, numeric-keyboard input with digit normalization and an adjacent All command; it is disabled during conflicting work. Home search and vault metadata/link dialogs use existing native outlined fields. No custom field stroke widths or internal padding were extracted.

### Switches
Native Material switches retain checked/enabled semantics and add a localized `contentDescription` directly to the control. The benchmark finalist-transfer switch names the TDLib transfer option; private monitoring names the monitoring option; each private-node enable switch includes the node label; the WEB transfer switch names real Telegram file transfer. WEB transfer is checked only with a signed-in session and disabled while signed out or running. These source labels are not a TalkBack or full accessibility acceptance result.

### Benchmark Progress
The displayed percentage is explicitly labeled `Overall` (localized in Persian), with LTR-isolated percentage text. `benchmarkProgressLabel` clamps finite fractions to (0..1) and treats nonfinite input as zero: exact endpoints display `0%`/`100%`; positive progress below (0.1%) displays `<0.1%`; below (1%) uses one decimal; larger incomplete progress uses integer percentages capped at `99%`. This is overall pipeline progress, not a stage pass rate or transfer speed.

The native linear indicator is (8dp) high, clipped to (4dp) corners, with primary fill and primary-alpha (0.20) track. Its callback reads the animated state (`progress = { animatedProgress.value }`) from the existing (180ms) tween; the text formats the runtime fraction, not the animated draw value. Do not infer device smoothness or successful completion from the animation.

### Navigation
A center-aligned native app bar pairs the ProxyX title with the current destination context and relevant icon actions. Bottom navigation and the wide rail use secondary selected icon/text plus secondary-container indicators; bottom inactive content uses on-surface-variant. Native system bars are transparent with icon appearance selected by theme, backed by the scaffold. The first wide capture's light system-navigation strip is a defect, not a light footer or palette token; final correction is source-only evidence until recapture.

### Telegram Action / Latency Gauge / Stages
`NeonTelegramAction` is a (128dp) circle or (96dp) compact circle, with (46dp)/(36dp) send icons, a localized button description and enabled state tied to endpoint selection. It opens Telegram; it never claims a VPN connection. The gauge uses the regular/compact/large-font reflow recorded in Layout. Internal captions occupy a (130dp)/(110dp) column only when side labels are not active; the large-font fallback leaves only the LTR numeral or missing-value dash inside the face. Missing measurement is explicit rather than a fabricated value. Only verified, positive protocol latency on the current network is eligible; active runs additionally filter the candidate's evidence by run start time.

Gauge drawing retains a (12dp) inset, a (150-degree) start and a (240-degree) sweep. Compact tick outer radius is the arc radius plus (11dp); regular uses the arc radius minus (10dp). Major ticks extend inward (7dp), other ticks (3dp). Preserve this compact outward tick placement alongside the text reflow; source geometry alone does not establish collision-free rendering.

Stage markers are (32dp) circles separated by equal weighted columns and (4dp) gaps. Complete, active, paused, stopped, pending and not-run states have distinct labels/icons. There are three protocol stages, or four when transfer is requested. A completed pipeline with no attempted candidates marks a stage not run; authenticated transfer completion is never inferred from protocol success. Stored favorites say they are previous results requiring retest, not fresh current-network evidence.

## Do's and Don'ts

### Do:
- **Do** use native Material color and typography roles from the recorded Kotlin theme, keeping dp and sp distinct.
- **Do** retain Persian RTL, English LTR and explicit LTR endpoint text without adding letter spacing.
- **Do** keep Telegram opening separate from testing and label historical, missing and other-network evidence explicitly.
- **Do** preserve scrolling work areas, adaptive navigation and at least 48dp action targets while checking scaled-font layouts on a usable native device.
- **Do** treat the approved concept as visual direction and first-pass/RC3 captures as historical evidence; require current native verification before RC5 acceptance.

### Don't:
- **Don't** restore the rejected light proposals as the approved identity or remove the retained optional light/system preference.
- **Don't** translate native dp/sp or Material defaults into invented CSS pixels, web hover tokens or fabricated component previews.
- **Don't** add continuous blur, 3D, background shaders or a web runtime for decorative neon effects.
- **Don't** promote ad hoc tiny type, monospace metric overrides, emoji flag icons or inconsistent hard-coded status colors into shared design tokens.
- **Don't** canonize first-pass ring absence, endpoint direction problems, segmented-corner defects, stale-result ambiguity or the wide light system strip; source corrections still require rendered confirmation.
- **Don't** call this release-ready, resolve the resize ANR by assumption, or claim authenticated transfer, complete workflow coverage or measured device smoothness from compilation and tests.
