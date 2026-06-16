# Glossary

Quick definitions for terms and jargon used across the vault and code. One line each; follow the `[[link]]` for the full note where one exists. Alphabetical.

- **BOM (Bill of Materials)** — a Gradle dependency that pins a whole family of libraries (e.g. all `lwjgl-*`) to one version, so you don't repeat the version string. See [[Gradle]].
- **Boilerplate** — repetitive, structural code you *must* write to satisfy a language/API but that carries little unique intent (scaffolding, not logic). Vulkan is infamously boilerplate-heavy: `sType` on every struct, the `calloc`->fill pattern, the [[#two-call-idiom|two-call idiom]]. Absorbing it is much of what makes a higher layer *approachable*. (Name: 1800s pre-cast metal printing plates with standardized text.)
- **Device, physical vs logical** — the **physical device** is a read-only handle to a real GPU (you *select* it, never create/destroy). The **logical device** (`VkDevice`) is the connection you *create* and own; all real work flows through it. See [[Logical Device and Queues]].
- **Extension vs layer** — an **extension** adds new API features (e.g. `VK_KHR_swapchain`); a **layer** intercepts existing calls to add behavior (e.g. the validation layer). Both exist at instance *or* device scope. See [[Validation Layer and Debug Messenger]].
- **Handle (opaque handle)** — an identifier (often a `long`) for a Vulkan object whose internals you never see; you pass it back to the API. `VK_NULL_HANDLE` (0) means "none."
- **Headless rendering** — rendering with no window/surface (offscreen -> image/PNG). The right tool for automated tests and over SSH. See [[Device Selection and Cross-Platform (planned)]].
- **ICD (Installable Client Driver)** — the vendor's actual Vulkan driver that the **loader** dispatches to. Why `lwjgl-vulkan` ships no Windows natives: the loader (`vulkan-1.dll`) is system-provided. See [[LWJGL]].
- **Image vs image view** — a `VkImage` is memory + metadata; a `VkImageView` is a "lens" saying *how to interpret* it (type, format, which slice). You render to/sample from views, not images. See [[Image Views]].
- **JNI (Java Native Interface)** — the bridge that lets Java call native C functions (and vice-versa); how [[LWJGL]] reaches Vulkan/GLFW. See [[JNI]].
- **Loader (Vulkan)** — the library that finds layers/ICDs and routes each Vulkan call to the right driver. Discovers layers via the registry, which is why validation worked once the SDK was installed.
- **MemoryStack** — LWJGL's fast scratch allocator for short-lived [[Off-Heap Memory|off-heap]] structs, freed in bulk when the `try`-block closes. Used in nearly every Vulkan call here. See [[MemoryStack]].
- **Off-heap memory** — memory outside the JVM heap (so native code can read it). Vulkan structs live here; the GC won't touch it, so *you* manage its lifetime. See [[Off-Heap Memory]].
- **Present mode** — *how* swapchain images reach the screen: `FIFO` (vsync queue, always available), `MAILBOX` (low-latency triple buffering, not always available). See [[Swapchain]].
- **Queue / queue family** — you submit GPU work to typed **queues**, not the GPU directly. A **family** groups queues with the same capabilities (graphics, present, compute...). See [[Physical Device and Queue Families]].
- **Smoke test** — a quick, *shallow* check that a system turns on / its critical path works at all, run before deeper testing. jvre's first run (GLFW inits, loader reachable, extensions returned) was one. (Name: power on hardware, see if it smokes.)
- **SPIR-V** — the compiled bytecode format Vulkan consumes for shaders; compiled from GLSL ahead of time (jvre's build uses the bundled **shaderc**; `glslc` is the equivalent SDK command-line tool). See [[Shaders - GLSL and SPIR-V]].
- **sType / pNext** — every Vulkan struct starts with an `sType` enum (which struct this is) and a `pNext` pointer (chain in extension structs). The pervasive struct convention. See [[Vulkan Struct Conventions]].
- **Surface (`VkSurfaceKHR`)** — the window <-> Vulkan bridge; an instance-owned handle GLFW creates from the window. See [[Windowing - GLFW and the Surface]].
- **Swapchain** — the queue of images presented to the surface (the double/triple-buffering machinery): you draw into one while another is shown, then swap. See [[Swapchain]].
- <a id="two-call-idiom"></a>**Two-call idiom (enumeration)** — the recurring pattern of calling a `vkEnumerate*`/`vkGet*` function **twice**: first with a null output to learn the count, then with an allocated buffer to fill it.
- **Validation layer** — a development-only layer that checks every Vulkan call against the spec and reports mistakes through the debug messenger; the project's safety net. See [[Validation Layer and Debug Messenger]].
- **Wrapper (Gradle)** — the `gradlew`/`gradlew.bat` scripts that download and run a pinned Gradle version, so no global Gradle install is needed. See [[Gradle Wrapper]].

#concepts #glossary #reference
