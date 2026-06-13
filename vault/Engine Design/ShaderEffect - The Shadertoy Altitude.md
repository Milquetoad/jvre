# ShaderEffect -- The Shadertoy Altitude

The **first realized altitude** from [[API Vision - Layered Altitudes]], and the **first user-facing creative-tier class to exist as real code**. A `ShaderEffect` is a user-authored fullscreen fragment shader: the user touches their `.frag` and nothing else; jvre supplies the fullscreen triangle, the pipeline, and fills the built-in uniforms every frame. Verified on the RTX 4090 (the [[#The demo|ripple demo]]; mouse-driven interference shifts live).

The API Vision's "sequencing surprise" made good: shader illustration is the *easier* altitude (barely more than the first-triangle milestone), so it lands well before the L2 2D layer. Predicted early, arrived early.

## The v1 contract (and how it differs from the sketch)

The user writes a **complete, valid** fragment shader that declares this exact block; jvre keeps it filled, no calls needed:

```glsl
layout(location = 0) out vec4 outColor;
layout(push_constant) uniform Push {
    vec2 uResolution;   // framebuffer size in pixels      @0
    vec2 uMouse;        // cursor px, top-left origin       @8
    float uTime;        // seconds since renderer start     @16
} pc;                   // 20 bytes, std430, well under the 128-byte spec floor
```

The API Vision *sketched* `effect.set("uTime", t)` + `window.drawFullscreen(effect)`. The realized v1 is deliberately smaller:
- **Built-ins are auto-filled, not set.** This is **Shadertoy's own answer** -- it has no arbitrary uniforms either, just `iTime`/`iResolution`/`iMouse`. So v1 needs no `set()` at all: `recordCommandBuffer` pushes the 20 bytes itself every frame.
- **Install, don't draw-per-frame.** `renderer.setEffect(effect)` once; `drawFrame()` then renders the effect instead of the cube. The cube machinery stays warm underneath (`EFFECT = null` in `Main` flips back) -- this is the renderer's **first content seam**.
- **`set(name, value)` arbitrary uniforms are post-v1** -- they need SPIR-V reflection (discover names/offsets). See [[#The known gap|the gap]] below; that same reflection is also the Ring 3 guard.

## Runtime compilation -- the whole point of the altitude

jvre now has **two GLSL->SPIR-V paths**, by audience:
- **jvre's OWN shaders** (the cube pair, the fullscreen-triangle vert) are fixed at build time -> the `compileShaders` Gradle task runs `glslc`, ships `.spv` resources. Zero runtime cost; errors break the build. (`fullscreen.vert` lives in `src/main/glsl/` so the task globs it automatically.)
- **USER shaders** arrive at RUNTIME as text -> compiled in-process by **shaderc-as-a-library** (`org.lwjgl:lwjgl-shaderc`, the same compiler `glslc` wraps). The point: requiring `glslc` would put the **Vulkan SDK on every consumer's machine** -- a toolchain tax on the *easy* altitude. shaderc removes it; compile errors come back as Java exceptions carrying the shader's own line numbers; live-reload becomes possible later.

`ShaderCompiler` is a stateless utility (compiler + options created/released per call -- shaderc compilers are cheap and not thread-safe to share). The demo `ripple.frag` lives in `resources/demo/`, *outside* `src/main/glsl/`, precisely so the build-time task never touches it -- editing + rerunning needs no SDK and no build step.

## No preamble injection (a decided principle)

jvre does **not** wrap a preamble around user source. The user writes the `#version`, the push block, everything. Reason: injection would **shift every line number** in compile errors. For an audience of Java programmers debugging their first GLSL, honest line numbers beat four saved lines of boilerplate. (This is also why `ShaderCompiler` passes the source through verbatim, and why the error message must carry the file label + line -- the [[Testing and CI-CD|unit test]] asserts exactly that.)

## The fullscreen triangle

The vertex shader fabricates one screen-covering triangle from `gl_VertexIndex` alone -- **no vertex buffer, no vertex input state at all**:

```glsl
vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);  // (0,0),(2,0),(0,2)
gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);                    // NDC (-1,-1),(3,-1),(-1,3)
```

ONE oversized triangle whose far corners overshoot the screen (the rasterizer clips to the viewport) instead of a two-triangle quad -- **no diagonal seam, and no double-shaded pixels** along it. "No vertex buffer" is a *pipeline-level fact*, not just an unbound buffer (see below). `vkCmdDraw(cmd, 3, 1, 0, 0)` -- literally "run the vertex shader three times," no indices, no instances.

## The effect pipeline -- every difference flows from "one triangle, no geometry"

`Pipeline.fullscreenEffect(...)` is the second constructor path (shared private ctor, a `boolean fullscreen` threading each difference, each guarded with its reasoning):

| State | Scene (cube) | Fullscreen effect | Why |
|---|---|---|---|
| Vertex input | interleaved `[xyz|rgb|uv]` | **empty** (0 bindings/attrs) | triangle comes from `gl_VertexIndex` |
| Cull mode | BACK + CCW | **NONE** | one known triangle; no winding question |
| Depth test/write | on (LESS) | **off** | 2D pass, nothing to occlude (format still declared -- the render-pass instance carries one) |
| Descriptor set layout | UBO + sampler | **VK_NULL_HANDLE** | v1 effects bind no resources |
| Push range (fragment) | 4 bytes (time pulse) | **20 bytes** (the built-in block) | the entire effect interface |
| Blend | src-over alpha | **off / REPLACE** | the effect writes every pixel opaquely |

The pipeline lives in the **Renderer**, not in `ShaderEffect` -- pipelines bake the swapchain formats + sample count, which only the renderer knows. So `ShaderEffect` holds just compiled SPIR-V + a name; `setEffect` builds the pipeline; **swapchain recreation rebuilds it too** (`buildEffectPipeline` after the scene pipeline -- same baked formats, but no recompile: the user's SPIR-V is still in the `ShaderEffect`). When an effect is active, the per-frame UBO upload is **skipped entirely** -- a pass with no UBO does no UBO work.

## First unit tests

`ShaderCompiler` is a **pure function** -- GLSL in, SPIR-V out, no GPU, no window, no device -- unlike nearly everything else in a renderer. That made it jvre's **first unit-testable surface**, so JUnit 5 arrives here ([[Testing and CI-CD]]). The three tests: a valid frag compiles to a SPIR-V module (checks the `0x07230203` magic), a broken frag throws with the file label in the message (the no-injection promise, asserted), and the fullscreen vert compiles. The suite doubles as proof the shaderc **natives load** on this machine before anything is wired against them.

## The contract, now ENFORCED (Ring 3 guard) ✅

This was jvre's **first "outside input" surface** -- before ShaderEffect, every input was jvre-authored at build time -- and the v1 contract was at first only *documented*. A user shader that *compiles* but breaks it would sail past shaderc and detonate later (or silently, with validation off). That gap is now closed by **`ShaderReflection`** ([[Diagnostics and the Crash Log|alongside]] the Ring 2 work), via **SPIRV-Cross** (`lwjgl-spvc`):

- It reflects the **same optimized SPIR-V jvre actually runs** (not a re-compile), so the check matches exactly what the pipeline will execute -- if the optimizer drops an unused resource, so does the check, consistently.
- **Two rules, both robust against the optimizer + both catching real CRASH cases:** (1) **no descriptor-bound resources** (the effect pipeline has no set layout, so any UBO/sampler/image is a guaranteed mismatch; binding decorations are interface and survive optimization); (2) **push block <= 20 bytes** (jvre fills exactly its 20-byte range; a bigger block reads past it). No block at all is fine.
- Thrown at **creation**, in the user's terms ("...declares a bound resource (sampler 'tex'), but v1 effects bind no resources..."), the same fail-fast spot the compile error already uses -- not a Vulkan validation spew three layers down, and never silent UB.

**Why SPIRV-Cross over hand-parsing SPIR-V** (decided): the guard's whole job is to be *trustworthy*; hand-rolling a SPIR-V parser to defend against malformed shaders has the wrong risk profile, and binary reflection is not the mechanism worth learning here. Tested ([[Testing and CI-CD]]): a clean effect + a no-push effect pass; a sampler-binding shader + an oversized-push shader are rejected with named errors.

**Still open:** "never writes `outColor`" (a blank screen, not a crash) is left for later. The same reflection is the **foundation for the post-v1 `set(name, value)`** uniforms -- the twofer that justified building it now.

#design #shadereffect #shaders #api
