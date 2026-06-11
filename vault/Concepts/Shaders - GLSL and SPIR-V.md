# Shaders — GLSL and SPIR-V

Vulkan **requires** shaders — there's no fixed-function fallback, so *nothing draws* without one. But the pipeline is indirect:

- The GPU consumes **SPIR-V**, a compact precompiled **binary** format — *not* GLSL source.
- We write shaders in **GLSL** (human-readable), then compile to SPIR-V ahead of time with **`glslc`** (from the Vulkan SDK / `shaderc`).
- We hand the SPIR-V bytes to Vulkan via `vkCreateShaderModule` — an [[LWJGL]] call.

So [[LWJGL]] is **not** involved in GLSL the language — only in loading the *compiled* result.

> A solid-color clear needs **no** shaders or pipeline at all — the clear is performed by `loadOp = CLEAR`. Shaders first appeared at the **first triangle** (2026-06-11) — see [[Graphics Pipeline]].

## The automation (built 2026-06-11, as planned)
The `compileShaders` [[Gradle]] task runs `glslc` over `src/main/glsl/*.vert|frag|comp` into `build/generated/shaders/`, registered as a resource dir — so each shader ships in the jar as `/shaders/<name>.spv` and `Pipeline` loads it from the classpath. `glslc` infers the stage from the file extension.

## What the first shaders taught
- **Stages talk by `location`, not by name**: the vertex shader's `layout(location = 0) out vec3` feeds the fragment shader's `layout(location = 0) in vec3`; the fragment's `location = 0` *output* is **color attachment 0**.
- **The rasterizer interpolates** vertex outputs across the triangle (barycentric) — the RGB blend in the middle of the triangle is free; no shader computes it.
- **Vulkan NDC**: x right, **y DOWN** (unlike OpenGL), z in [0,1]. `gl_Position` is the one mandatory vertex output.
- `gl_VertexIndex` lets a shader fabricate vertices with **no vertex buffer** — how the first triangle isolates pipeline machinery from memory machinery.
- The swapchain is an sRGB format, so linear fragment-shader outputs get sRGB-encoded on write.

#concept #shaders
