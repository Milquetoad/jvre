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

## Sequencing surprise
Shader illustration is the **easier** target: a fullscreen frag shader = screen-covering triangle + fragment shader + a few uniforms ≈ barely more than the "first triangle" milestone. The 2D primitive layer (batching + font atlas text) is *more* work. → expect a cool Shadertoy-style demo **early**, before the full 2D/GUI layers.

Related: [[Self-Built GUI (planned)]] is an L2 feature; [[Roadmap - Clear to Color]] is the L0/L1 groundwork.

#design #vision #api
