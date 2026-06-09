# Toolchain Setup

Everything installed for jvre, and why.

| Tool | Version | Role |
|---|---|---|
| JDK (Temurin) | 21 | Compile/run Java; modern LTS |
| Gradle | 8.10.2 | [[Build Tools|build tool]], via [[Gradle Wrapper]] (no global install) |
| [[LWJGL]] | 3.3.4 | Java bindings to native Vulkan + GLFW |
| Vulkan SDK (LunarG) | 1.4.350.0 | `glslc` (GLSL→SPIR-V) + **validation layers** |

## Machine graphics
- Vulkan runtime `vulkan-1.dll` present (from GPU driver).
- **Two GPUs:** NVIDIA **RTX 4090** (discrete — prefer this) + AMD Radeon integrated.
- → physical-device selection will deliberately prefer the discrete GPU.

## Key paths
- Gradle dist: `%USERPROFILE%\tools\gradle-8.10.2` (used once to generate the wrapper; not needed afterwards)
- Vulkan SDK: `C:\VulkanSDK\1.4.350.0` (`Bin\glslc.exe`)
- `VULKAN_SDK` env var set (machine scope) — may need a fresh terminal to appear on PATH.

## Build/run
```
.\gradlew.bat run
```

LWJGL modules used: `lwjgl` (core), `lwjgl-glfw`, `lwjgl-vulkan`. Natives = `natives-windows`. (`lwjgl-vulkan` has no Windows natives — the loader is system-provided.)

#project #tooling
