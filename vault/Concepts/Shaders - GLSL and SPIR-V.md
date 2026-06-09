# Shaders — GLSL and SPIR-V

Vulkan **requires** shaders — there's no fixed-function fallback, so *nothing draws* without one. But the pipeline is indirect:

- The GPU consumes **SPIR-V**, a compact precompiled **binary** format — *not* GLSL source.
- We write shaders in **GLSL** (human-readable), then compile to SPIR-V ahead of time with **`glslc`** (from the Vulkan SDK / `shaderc`).
- We hand the SPIR-V bytes to Vulkan via `vkCreateShaderModule` — an [[LWJGL]] call.

So [[LWJGL]] is **not** involved in GLSL the language — only in loading the *compiled* result.

> A solid-color clear needs **no** shaders or pipeline at all — the clear is performed by the **render pass** (`loadOp = CLEAR`). Shaders first appear at the "first triangle" step. See [[Roadmap - Clear to Color]].

Planned automation: a [[Gradle]] task runs `glslc` on our `.vert`/`.frag` files → `.spv` before each build.

#concept #shaders
