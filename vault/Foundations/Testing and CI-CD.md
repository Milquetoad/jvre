# Testing and CI/CD

How jvre proves itself automatically. Two layers: **unit tests** (run everywhere) and **CI** (runs them on every push/PR, on Linux + Windows).

## What's testable without a GPU
Most of a renderer needs a GPU, but the parts that don't are unit-tested ([[Definition of Done]] baseline). Current suite (`src/test/java/jvre/core`):
- **`ColorTest`** -- sRGB->linear conversion.
- **`Renderer2DTest`** -- the CPU half of L2: the vertex stream each primitive emits, the SDF fields, the run model, and the **transform stack** math (translate/scale/rotate a point, push/pop balance). Pure CPU -- no GPU, no window.
- **`ShaderCompilerTest`** -- GLSL->SPIR-V via the in-process **shaderc** native lib (CPU, no GPU).
- **`ShaderReflectionTest`** -- SPIR-V reflection via **spvc**, enforcing the effect contract.

GPU/GLFW-coupled code (`Texture`, `Font`, `Window`, `Input`, actual drawing) is **verified on real hardware** instead, never mocked into a false green -- the DoD's "shipped" tier.

## CI -- `.github/workflows/ci.yml`
**Added 2026-06-14 ([[Roadmap]] 1c).** Since Hal stays on Windows, the **Ubuntu runner IS the Linux test bed** -- this is how cross-platform gets validated without a Linux box.

- **Matrix:** `ubuntu-latest` + `windows-latest` (`fail-fast: false` -- an OS-specific break is the whole point).
- **Triggers:** push to `main` + every PR to `main`.
- **Runs:** `./gradlew test -x compileShaders` (JDK 21, temurin, gradle cache).

**Two deliberate choices:**
1. **No GPU in CI.** Runners are headless -- CI proves the code *compiles* and the *GPU-free unit tests pass* on both OSes. It catches a Linux build break, a cross-platform native-loading failure, or a logic regression. It does NOT (cannot) verify rendering -- that's the hardware step.
2. **`-x compileShaders`.** The `compileShaders` task needs `glslc` (Vulkan SDK), which the runners lack. The unit tests use the *in-process* shaderc/spvc, not the compiled `.spv`, so skipping it loses no coverage. Verifying shader compilation cross-platform (install glslc in CI) is a catalogued later enhancement.

## `main` is protected (set 2026-06-14)
CI is only as good as its enforcement. `main` is a protected branch: **no direct pushes** (every change is a branch + PR), and a merge requires **both CI checks green** + an up-to-date branch. PR required with **0 approvals** (so the solo owner isn't locked out -- you can't approve your own PR), enforced on admins, linear history, force-push/delete blocked. So the workflow is fixed: branch -> PR -> green CI -> rebase-merge -> sync. (Set via `gh api ... branches/main/protection` -- note: on Git Bash, omit the leading slash or MSYS rewrites the endpoint to a filesystem path.)

## What made Linux CI possible: de-pinned natives (R1)
`build.gradle` used to hardcode `lwjglNatives = 'natives-windows'`, so a Linux build pulled the wrong `.dll`s and the native-loading tests (shaderc/spvc/stb) would fail. Now the classifier is **auto-detected from the host** `os.name`/`os.arch`, so the same build works on any dev machine and on each CI runner.

## Publishing the artifact (R2, done 2026-06-14 -- the CD half begins)
`maven-publish` + `java-library` produce a consumable artifact: **jar + `-sources.jar` + `-javadoc.jar` + a complete POM** (name/description/AGPL license/SCM/developer), coordinates `io.github.milquetoad:jvre:0.1.0`. Verify locally with `gradlew publishToMavenLocal` (-> `~/.m2`).
- **api vs implementation:** lwjgl core + lwjgl-vulkan are `api` (public L1 exposes `Vk*` types -> consumers compile against them); the rest is `implementation` (runtime scope).
- **Natives are NOT in the POM.** They live in a private `nativesRuntime` configuration (wired onto `run` + `test`, never published), so a consumer picks the natives for THEIR platform -- the R1 "consumer picks" promise enforced at publish time. Verified: the generated POM has zero `natives-*` classifiers.
- **Gotcha hit + fixed:** the SOURCES jar reads the generated-shaders resource dir, so Gradle's overlapping-output validation failed it until `sourcesJar` declared `dependsOn 'compileShaders'`; the compiled `.spv` are also `exclude`d (a sources jar carries source, not build artifacts).

**Still ahead:** R3 (JitPack / tagged GitHub Releases -- the first *public* consumption path), then R4 (Maven Central at 1.0 with GPG signing). Those wire into this workflow: build -> test -> sign -> publish on a tag.

#testing #ci #process
