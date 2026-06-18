# Public API surface

The complete set of public types in `jvre.core` — the surface jvre commits to. As
of 1.0 this is a semver compatibility promise, so it is kept **deliberately
small**: everything here is intended for users; everything *not* here is internal
and may change freely.

> **Design principle — tighten now, widen later.** Going package-private → public
> (or adding overloads / new types) is an *additive, non-breaking* change shippable
> in any minor release. The reverse — making a public thing private or changing a
> signature — is *breaking* and forces a major version. So a minimal surface is
> what *preserves* freedom to grow: it leaves every door open to add well-designed
> public API later, exactly when a feature lands. (Audit: 2026-06-18.)

## Setup
| Type | Role |
|---|---|
| `Window` | the OS window + input (GLFW) |
| `Instance` | the Vulkan instance (+ optional validation) |
| `Surface` | the window ↔ Vulkan bridge |
| `Renderer` | owns the device, swapchain, and the frame loop; the hub. Windowed (`Instance`+`Surface`+`Window`) or **headless** (`Instance`+`RendererOptions` — no window; render into targets via `render()` + `readPixels`) |
| `RendererOptions` | creation-time config (clear color, vsync, MSAA, GPU preference) |

## L2 — "just draw"
| Type | Role |
|---|---|
| `Renderer2D` | the 2D surface: shapes, strokes, text, images, transform stack |
| `Color` | immutable color value |
| `Filter` | texture sampling filter (NEAREST / LINEAR) |
| `WrapMode` | texture address mode (CLAMP / REPEAT / MIRROR / BORDER) |
| `TextureOptions` | bundled sampler config (filter + wrap + mipmaps + anisotropy) for `createImage`/`loadImage` |

## Shader effects
| Type | Role |
|---|---|
| `ShaderEffect` | a fullscreen fragment-shader effect; up to 4 Shadertoy-style input channels (`iChannel0..3`, via `Renderer.setEffectChannel`), and live hot-reload (re-call `Renderer.setEffect`) |
| `ShaderCompiler` | runtime GLSL → SPIR-V (for custom pipelines) |
| `ShaderCompileException` | thrown on a failed runtime compile; carries the structured diagnostics |
| `ShaderDiagnostic` | one parsed compiler message (name, line, severity, message) |

## L1 — the escape hatch
| Type | Role |
|---|---|
| `PipelineSpec` | describes a user pipeline (builder) |
| `VertexLayout`, `AttribFormat` | the vertex format vocabulary |
| `Cull`, `Stage` | fixed-function + shader-stage enums |
| `SceneRenderer`, `FrameRenderer` | record draws against a jvre-owned facade |
| `Camera` | Vulkan-correct view + projection matrices |
| `TargetFormat` | a render target's colour format — `DEFAULT` (LDR), `HDR` (16-bit float), or `HDR_FLOAT32` (32-bit float); for `createRenderTarget` + `createPipeline(spec, target)` |
| `Device` | the raw-Vulkan escape: exposes `VkPhysicalDevice` / `VkDevice` / `VkQueue` for anything jvre doesn't yet wrap |

## Handles (opaque — you hold them, jvre makes them)
Created via the `Renderer` (`createPipeline`, `createVertexBuffer`,
`createIndexBuffer`, `createImage`, `loadImage`, `createCubemap`, `createVolume`,
`font`, `loadFont`, `loadMsdfFont`, `createRenderTarget`); the only method you call
on them directly is `close()` (plus `Texture.width()` / `height()`):

`Pipeline`, `Buffer`, `Texture`, `Font`, `RenderTarget`

`RenderTarget` (render-to-texture) is an offscreen image you render into instead of
the screen, then sample back: `target.texture()` (the result, a `Texture`),
`width()` / `height()`, `close()`. You render into it with `renderer.drawToTarget`
(L1 geometry) or `renderer.createCanvas` (an L2 `Renderer2D`). See the
[render-to-texture guide](render-to-texture.md).

## Utility
| Type | Role |
|---|---|
| `Diagnostics` | the crash-log / environment-fingerprint helper |
| `Input`, `Key`, `MouseButton` | the per-frame input snapshot + its enums |
| `CursorShape` | standard OS cursor shapes for `Window.setCursor` |

---

**Internal (not public API):** `Vk`, `Commands`, `Swapchain`, and the
implementation methods of the handle types are package-private — reachable only
within `jvre.core`. Render-to-texture / offscreen targets (the `RenderTarget` API)
and custom-font loading (`Renderer.loadFont` + a public `Font.close()`) **landed**
as deliberate additive public API; raw resource creation with custom flags will
arrive the same way when that feature lands, rather than by leaking internals.
