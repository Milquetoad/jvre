# LWJGL

**Lightweight Java Game Library** — a thin *translation layer* giving Java access to native **C** libraries: Vulkan, GLFW (windowing/input), OpenGL, OpenAL, etc. Explicitly **"not a framework"**; its Vulkan binding is nearly **1:1** with the C API.

> ⚠️ LWJGL exposes **Vulkan**, *not* GLSL. GLSL is the shader language, compiled separately by `glslc` — see [[Shaders - GLSL and SPIR-V]].

## How it translates (the "how")
Vulkan is a C library — `vulkan-1.dll` exposes C functions like `vkCreateInstance`. The JVM can't call C directly, so LWJGL bridges two gaps:
1. **Calls** — via [[JNI]], LWJGL's Java methods forward into the real native C functions and bring results back.
2. **Data layout** — C wants *pointers to structs* with an exact byte layout; Java objects don't look like that. LWJGL allocates [[Off-Heap Memory]] and writes fields at the offsets C expects (using [[MemoryStack]] + generated struct classes like `VkApplicationInfo`).

## Why the 1:1-ness matters
Essentially every Vulkan tutorial/book is C/C++. Our Java reads almost line-for-line the same — the world's Vulkan knowledge becomes usable, in Java, at near-zero translation cost.

Modules: `lwjgl` (core), `lwjgl-glfw`, `lwjgl-vulkan`. See [[Toolchain Setup]].

#concept #lwjgl
