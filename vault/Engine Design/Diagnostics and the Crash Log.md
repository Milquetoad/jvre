# Diagnostics and the Crash Log

The answer to **"a fault happened on someone else's machine -- what do we have to go on?"** A `Diagnostics` log that captures an environment fingerprint to a file the user can attach to a bug report. This is the **Ring 2 guard** from the [[Progress Log|"what breaks jvre" discussion]] (the three rings: environment / your own Vulkan bugs / outside input).

## Why a fingerprint, not a trace

A **Ring 2 fault** -- a hand-rolled-Vulkan bug (a sync hazard, a wrong format, a lost device) -- is **environment-dependent by definition**: it didn't happen on the author's GPU but did on theirs. So the single most valuable thing in a bug report is *not* a per-frame play-by-play; it's the answer to **"what machine, configured how?"** The validation layers are the author's dev-time net; the fingerprint is what you get when the fault is on a machine where validation is **off** (no SDK) and the code path may be one the author **never executed**.

Concretely, the fingerprint makes the [[Swapchain]]'s never-run `CONCURRENT` branch **self-reporting**: the 4090 shares graphics+present families (-> `EXCLUSIVE`), so a split-family GPU logging `sharing CONCURRENT (graphics=0, present=2)` tells us in one line that it's standing in the untested branch -- diagnosis before a single stack frame is read.

## What it captures (verified 2026-06-13, the 4090)

```
================ jvre diagnostics ================
started   : 2026-06-13 14:20:56 CEST
os        : Windows 11 10.0 (amd64)
jvm       : 21.0.11 (Eclipse Adoptium)
lwjgl     : 3.3.4+7
...
Vulkan loader: 1.4.350  validation layers: ON
Picked GPU: NVIDIA GeForce RTX 4090 [discrete] (score 33768)
  vendor=0x10DE device=0x2684  apiVersion=1.4.329  driverVersion=0x94D84000 (vendor-encoded)
Swapchain created: ... format 50, present mode MAILBOX, sharing EXCLUSIVE (graphics=present=0).
```

- **Static half** (no Vulkan needed): OS+arch, JVM, LWJGL -- written eagerly at startup.
- **Dynamic half**: the **loader** core version + whether validation was on ([[Vulkan Instance|Instance]]); the GPU name + **type/vendor/device/apiVersion/driverVersion** ([[Physical Device and Queue Families|Device]]); the **queue-sharing mode** + swapchain format/present mode/extent ([[Swapchain]]); sample count + depth format. Each owning object logs its own decision as it comes up.
- `driverVersion` is **vendor-encoded** (not the `VK_VERSION` major.minor.patch layout), so it's logged raw in hex -- honest beats a wrong decode.

## Two design decisions worth keeping

1. **The tee, not a rewrite.** `Diagnostics.init` installs a two-destination `OutputStream` ([decorator] over `System.out`/`System.err`) that writes to **both the console and one shared log file**. Every existing `System.out.println` in jvre -- the GPU line, the swapchain line, validation messages from the [[Validation Layer and Debug Messenger|debug callback]] -- is captured **for free**, no call sites rerouted. The console still gets everything; the file is just a second sink. (Enrichment was then a handful of *richer* prints, not a logging-framework migration.)

2. **A deliberate static singleton -- the one place it earns its keep.** [[Push Constants|`Vk.check`]] is `static` and called everywhere; the validation callback is `static`. Logging is the textbook **cross-cutting concern reachable from static contexts**, so a global sink is the standard (every logging framework is effectively one). Threading an instance through every call site would be pure noise. Stated outright in the class comment so it reads as a *choice*, not an accident, against jvre's usual strict-ownership grain.

## The cardinal rule: eager + flushed

The fingerprint is written and **flushed at startup**, because it is most valuable exactly when the process dies hardest -- a driver `SIGSEGV` three seconds later must still find it on disk. `autoflush=true` on the tee'd `PrintStream`s flushes after **every** `println`, so the last line before a hard crash survives. (A shutdown hook flushes on graceful exit too, but hard crashes can bypass hooks -- per-line flush is the real guarantee.) Corollary: the **hot render loop stays silent** -- log events (startup, recreation, faults), never frames (MAILBOX runs thousands of fps; per-frame logging would be useless *and* a perf lie).

## Where it lives, and how you send it

- **Per-OS app-data dir** (each platform's blessed spot): `%LOCALAPPDATA%\jvre\` / `~/Library/Logs/jvre/` / `$XDG_STATE_HOME` (-> `~/.local/state/jvre/`; the XDG spec files *logs* under "state"). The immediately-previous run is kept as `jvre.log.1` so a crash log isn't clobbered by a relaunch before it's sent.
- **Frictionless MANUAL send** (decided): on a fault, `reportFault` prints the log's full path + a GitHub issue link. One click, consent preserved, **no server to operate**. True auto-upload was *deferred on purpose* -- it's not a difficulty problem but a **consent + infrastructure** one (the log path itself contains the user's username; uploading is outward-facing data movement that should be opt-in). Verified end-to-end: a bad shader path threw, and the log captured the full startup fingerprint + the FATAL stack trace + the "attach this file" footer.

## Failure-tolerance

Diagnostics must **never take down the app it's diagnosing**: a missing app-data dir or an unwritable file degrades to "no log file" with a console note, never an exception.

## Next

The [[ShaderEffect - The Shadertoy Altitude#The known gap|Ring 3 SPIR-V reflection guard]] is the next beat -- and its user-facing creation errors will flow through *this* sink for free.

#design #diagnostics #robustness #logging
