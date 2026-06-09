# API Vision — Layered Altitudes

**The north star (decided 2026-06-09):** jvre exposes **two altitudes on ONE engine**, not two engines. Design principle: *"simple things simple, complex things possible"* (Alan Kay).

## The two use-cases
**1. High-level, no shaders ("just draw"):**
```java
Window window = new Window(800, 600, "Sorting Visualizer");
Renderer2D g = window.renderer2D();
while (window.isOpen()) {
    g.begin();
    g.fillRect(x, 0, w, height, Color.SKYBLUE);
    g.text("comparisons: " + count, 10, 10);
    g.end();
    window.present();
}
```
User sees **primitives** (`fillRect`, `line`, `circle`, `text`, later `Mesh`/`Camera`) — never a shader/buffer/pipeline.

**2. Shader illustration ("write a fragment shader"):**
```java
ShaderEffect effect = ShaderEffect.fromFragment("ripple.frag");
while (window.isOpen()) {
    effect.set("uTime", window.time());
    window.drawFullscreen(effect);   // per-pixel, Shadertoy-style
    window.present();
}
```
Custom shaders also plug into 3D as materials: `mesh.setMaterial(Material.fromShaders("custom.vert","custom.frag"))`.

## Why it's one engine: the layers
```
L2  Convenience API   Renderer2D (shapes/text), Scene/Mesh/Camera, ShaderEffect
        (batteries-included; ships built-in shaders)        ← use-case 1
                 ↑ built on ↑
L1  jvre core         Device, Swapchain, Pipeline, Buffer, Shader, Texture
        ("the elementaries" — clean, explicit, PUBLIC)      ← use-case 2
                 ↑ built on ↑
L0  Raw Vulkan (LWJGL)   instance, queues, command buffers, sync   (internal)
```
High-level primitives are *implemented with shaders* on L1. So exposing the shader-art path (L1) is nearly **free** — it's the layer we already build + use internally, made public.

## Design rules that follow
- **L1 must be clean and public; L2 is built on top of it and never hides it.** A power user can always drop from L2 to L1 (grab a `Pipeline`, write a custom shader) without leaving the engine. That escape hatch = real framework, not toy.
- Reinforces the "refactor the linear bootstrap into elementaries" plan — L1 *is* the elementaries.

## L1 visibility: hide infrastructure, expose creative points (decided 2026-06-09)
"Public L1" does **not** mean the user assembles every Vulkan object by hand -- that's a thin wrapper (all the pain, none of the power saved). It means the few objects that add **expressive power** are reachable; the orchestration is owned and hidden. **Three tiers, not one:**
- **Infrastructure (owned, hidden):** Instance, Surface, Device, Swapchain, ImageViews, RenderPass, Framebuffers, CommandPool/Buffers, Sync, the fullscreen triangle. A `Renderer`/context creates + manages these. Reachable if truly needed, never *constructed* by the user.
- **Creative surface (what "dropping to L1" really means):** `Shader`, `Pipeline` (often defaulted), `Buffer`/uniforms, `Texture`. The thin escape-hatch tier.
- **L2 convenience:** `ShaderEffect`, `Renderer2D`, ... built on top.

**Litmus -- a Shadertoy-style fullscreen fragment shader.** You touch: (1) your fragment shader (GLSL); (2) a few uniforms set by name (`uTime`/`uResolution`/`uMouse`); (3) per frame "draw fullscreen + present." Hidden/owned: instance, surface, device, swapchain, image views (+ resize recreation), render pass, framebuffers, the **fullscreen triangle** (generated in the vertex shader -- no vertex buffer), the pipeline, command pool/buffers, sync. So ~1 artifact + a few setters vs ~a dozen objects. "Low-level" = you **author a shader**, not you assemble plumbing.

**Refactor implication:** the clean internal classes (`Window`, `Instance`, `Surface`, `Device`, ...) are good *internal* engineering (separation, testability, the recreatable-context seam) + the rare deep escape -- NOT things users construct. The user-facing surface is a small facade/context that vends the creative tier; you never write `new Instance(...)`. See [[Design North Star]].

## Naming: match each layer's audience (decided 2026-06-09)
A framework's **public API -- names included -- IS part of the product** (unlike an app, where only behavior ships). So name each layer for ITS audience:
- **L1 elementaries: Vulkan-faithful.** A class wrapping one Vulkan object takes that object's name (`Instance`, `Surface`, `Device`, `Swapchain`, `RenderPass`, `Pipeline`, `Buffer`). L1's audience -- power users dropping down via the escape hatch, plus anyone reading the source -- already speaks Vulkan, so matching its vocabulary (read `Swapchain` -> `VkSwapchainKHR` with zero translation) *helps* them; inventing "friendlier" synonyms would force them to learn jvre's paraphrase on top of Vulkan. Don't paraphrase Vulkan here.
- **jvre's own abstractions** (no 1:1 Vulkan object) take role names: `Window` (GLFW, not Vulkan), `Renderer`/`DeviceContext` (the recreatable coordinator -- Vulkan has no such object).
- **L2 convenience: intuitive.** The "just draw" audience never learns Vulkan, so L2 is friendly and domain-facing (`Renderer2D`, `fillRect`, `text`, `Mesh`, `Camera`).

Corollary: the *implementation* isn't the product (name internals for clarity), but the *public surface* is (name it for the audience). See [[Design North Star]].

## Sequencing surprise
Shader illustration is the **easier** target: a fullscreen frag shader = screen-covering triangle + fragment shader + a few uniforms ≈ barely more than the "first triangle" milestone. The 2D primitive layer (batching + font atlas text) is *more* work. → expect a cool Shadertoy-style demo **early**, before the full 2D/GUI layers.

## Refactor sequencing — when to extract the elementaries (decided 2026-06-09)
The linear `Main.java` will be split into reusable classes ("the elementaries") — but **not until clear-to-color is working**. The question is *when*, not whether.

**Decision: finish clear-to-color first, then refactor as its own milestone.** Reasoning:
- The target boundary is the [[Device Selection and Cross-Platform (planned)|seam]]: instance + surface = **stable, long-lived, GPU-agnostic**; everything device-dependent (logical device, swapchain, image views, pipelines, command buffers) = **one recreatable "device context."**
- The *correct shape* of that recreatable unit only becomes visible once the **render loop + swapchain recreation exist** — they reveal exactly which fields must tear down and rebuild together. Splitting earlier means inventing the context object blind and redrawing its boundaries within a step or two.
- Concretely: a `Swapchain` class pulled out now would need `device`, `physicalDevice`, `surface`, `window`, and queue families injected — i.e. we'd end up building the context anyway. Cheaper to let the remaining steps expose the dependencies, then cut once.

**Cheap intermediate move (if Main's bulk starts hurting before then):** extract only the *stable* part — instance + debug messenger + surface — into its own class. That boundary is already firm and won't move; the device/swapchain cluster stays in Main until the loop exists.

Related: [[Self-Built GUI (planned)]] is an L2 feature; [[Roadmap - Clear to Color]] is the L0/L1 groundwork.

#design #vision #api
