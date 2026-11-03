# Resize ANR Triage

Date: 2026-11-03. First implementation build, before the review-fix rebuild.

## 1. Trace Validity

Subject: input dispatch timed out after 5327 ms waiting for a focus event in com.aistudio.proxyx.vbnmkw/com.example.MainActivity. Foreground process PID 4869. This was a user-visible ProxyX ANR, not merely the separate System UI boot ANR.

Evidence: artifacts/neon-resize-lastanr.txt, artifacts/neon-resize-anr-trace.txt, artifacts/neon-anr-events.txt and the neon-state-after-resize screenshot. The event followed live resolution/density changes on the isolated API-34 emulator. Display overrides were restored in finally.

The app's full stack dump timed out. An initial waiting-channel sample has its main thread in binder_ioctl_write_read; a later sample has it in futex_wait. Neither identifies an app callsite, binder target or lock holder. The detailed later main-thread stacks in the multi-process report are from other processes and are not attributed to ProxyX.

## 2. System Health

ActivityManager logged total CPU 99-100%, approximately 73% kernel time, kswapd0 around 18%, and substantial major page faults across the app, system_server, System UI and input method. ProxyX's interval showed about 0.1% user CPU, 25% kernel CPU and 11,388 major faults; its Signal Catcher dominated a shorter dump interval. The trace recorded about 89 MiB VM swap. A later app meminfo sample showed approximately 129,460 KiB PSS and 12,072 KiB Java-heap PSS. The Windows host had only roughly 74-135 MiB free during parts of the build/emulator work.

## 3. Attribution

Diagnosis: indeterminate because the app stack capture timed out; strong external/guest memory-pressure evidence is present. No concrete app-side lock, synchronous I/O callsite, deadlock or view-thread violation was established. Resource pressure is not proof that the app is defect-free, and the missing stack is not proof of a sync-barrier leak.

No precise root-cause or resolved-ANR claim is made. This remains an acceptance limitation until a controlled retest supplies sufficient evidence or the relevant path is reproduced on a suitable physical device.

## 4. Remediation Boundary

The UI review batch fixes independently observed layout, bidirectional text, stage-state and system-bar defects. Those are not asserted as ANR fixes. No speculative app workaround was added solely from this stackless trace.

For the next emulator pass, attempt the installed emulator's supported host-GPU renderer instead of SwiftShader, serialize builds and emulation, and settle each test-device configuration before opening the app. These are test-environment changes, not a claim of phone performance or proof that live resizing is fixed. Keep normal-page interaction results separate from the unresolved live-resize stress result.
