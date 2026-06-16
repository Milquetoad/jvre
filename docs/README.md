# jvre documentation

User-facing guides for **jvre**, a from-scratch Java + Vulkan rendering framework
(via [LWJGL](https://www.lwjgl.org/)). If you are looking for the *internal*
design notes and the learning diary, those live in [`../vault/`](../vault/); this
directory is for people who want to *use* jvre.

> Pre-1.0: the API is still settling and may change between `0.x` releases. These
> docs track the current `main`.

## Start here

- **[Getting started](getting-started.md)** — add the dependency, then a complete
  "window with a rectangle" program you can run in a couple of minutes.

## Guides

- **[2D graphics](2d-graphics.md)** — the high-level "just draw" surface
  (`Renderer2D`): shapes, strokes, text, images, the transform stack, input, and
  time. This is where most programs start.
- **[Shader effects](shader-effects.md)** — the Shadertoy-style altitude
  (`ShaderEffect`): one fragment shader over a fullscreen quad.
- **[Custom pipelines](custom-pipelines.md)** — the low-level escape hatch: your
  own geometry, vertex layout, shaders, uniforms, and textures, mixed with the 2D
  surface in one frame; plus the `Camera` helper for 3D.

## Reference

- **[Public API surface](api-surface.md)** — the complete set of public types
  (the 1.0 compatibility contract), grouped by role.

## The shape of jvre: two altitudes, one engine

jvre is deliberately layered. You pick the altitude that fits the job, and you can
mix them in a single frame.

| Altitude | Class | You write… | Use it for |
|---|---|---|---|
| **L2 — "just draw"** | `Renderer2D` | plain method calls (`fillRect`, `text`, …) | 2D scenes, UI, visualizations — no shaders required |
| **Effects** | `ShaderEffect` | one GLSL fragment shader | full-screen procedural / Shadertoy-style visuals |
| **L1 — escape hatch** | `PipelineSpec` + `FrameRenderer` | your own shaders + geometry | custom meshes, 3D, anything the high level doesn't cover |

Underneath all three is the same machinery you can also reach directly: `Window`,
`Instance`, `Surface`, and the `Renderer` that owns the device, swapchain, and
per-frame command recording.

### The core objects

Every program builds the same short stack once, then loops:

```
Window      the OS window + input (GLFW)
  Instance      the Vulkan instance (+ optional validation layers)
    Surface       the window<->Vulkan bridge
      Renderer      owns the GPU device, swapchain, and the frame loop
        renderer2D()    -> Renderer2D   (the 2D surface)
        setEffect(...)  -> a full-screen ShaderEffect
        createPipeline()-> a custom L1 pipeline
```

You create `Window` → `Instance` → `Surface` → `Renderer`, draw each frame between
`pollEvents()` and `renderer.drawFrame()`, and close them in reverse order at the
end. The [getting-started guide](getting-started.md) shows the whole thing.

## Requirements

- **JDK 21** (the Gradle toolchain enforces it).
- A **Vulkan 1.3** GPU + driver (any vendor; a discrete GPU is auto-preferred).
- The **Vulkan SDK is optional** — only for validation layers during development.
  jvre compiles its shaders with the bundled shaderc, so no SDK is needed to build
  or run.

See the top-level [README](../README.md) for the project overview and the
[roadmap](../vault/Project/Roadmap.md) for where the API is headed.
