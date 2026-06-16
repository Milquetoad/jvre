# Public API surface

The complete set of public types in `jvre.core` — the surface jvre commits to. At
1.0 this becomes a semver compatibility promise, so it is kept **deliberately
small**: everything here is intended for users; everything *not* here is internal
and may change freely.

> **Design principle — tighten now, widen later.** Going package-private → public
> (or adding overloads / new types) is an *additive, non-breaking* change shippable
> in any minor release. The reverse — making a public thing private or changing a
> signature — is *breaking* and forces a major version. So a minimal surface is
> what *preserves* freedom to grow: it leaves every door open to add well-designed
> public API later, exactly when a feature lands. (Audit: 2026-06-16.)

## Setup
| Type | Role |
|---|---|
| `Window` | the OS window + input (GLFW) |
| `Instance` | the Vulkan instance (+ optional validation) |
| `Surface` | the window ↔ Vulkan bridge |
| `Renderer` | owns the device, swapchain, and the frame loop; the hub |
| `RendererOptions` | creation-time config (clear color, vsync, MSAA, GPU preference) |

## L2 — "just draw"
| Type | Role |
|---|---|
| `Renderer2D` | the 2D surface: shapes, strokes, text, images, transform stack |
| `Color` | immutable color value |
| `Filter` | texture sampling filter (NEAREST / LINEAR) |

## Shader effects
| Type | Role |
|---|---|
| `ShaderEffect` | a fullscreen fragment-shader effect |
| `ShaderCompiler` | runtime GLSL → SPIR-V (for custom pipelines) |

## L1 — the escape hatch
| Type | Role |
|---|---|
| `PipelineSpec` | describes a user pipeline (builder) |
| `VertexLayout`, `AttribFormat` | the vertex format vocabulary |
| `Cull`, `Stage` | fixed-function + shader-stage enums |
| `SceneRenderer`, `FrameRenderer` | record draws against a jvre-owned facade |
| `Camera` | Vulkan-correct view + projection matrices |
| `Device` | the raw-Vulkan escape: exposes `VkPhysicalDevice` / `VkDevice` / `VkQueue` for anything jvre doesn't yet wrap |

## Handles (opaque — you hold them, jvre makes them)
Created via the `Renderer` (`createPipeline`, `createVertexBuffer`,
`createIndexBuffer`, `createImage`, `loadImage`, `font`); the only method you call
on them directly is `close()` (plus `Texture.width()` / `height()`):

`Pipeline`, `Buffer`, `Texture`, `Font`

## Utility
| Type | Role |
|---|---|
| `Diagnostics` | the crash-log / environment-fingerprint helper |
| `Input`, `Key`, `MouseButton` | the per-frame input snapshot + its enums |

---

**Internal (not public API):** `Vk`, `Commands`, `Swapchain`, and the
implementation methods of the handle types are package-private — reachable only
within `jvre.core`. Render-to-texture / offscreen targets, custom-font loading,
and raw resource creation with custom flags will arrive as *deliberate* public
APIs when those features land, rather than by leaking internals into 1.0.
