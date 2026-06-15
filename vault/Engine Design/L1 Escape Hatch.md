# L1 Escape Hatch (user-defined pipelines)

**Phase 2a COMPLETE (2026-06-15).** The most *vision-significant* gap closed: the [[Design North Star]] promised dropping to custom shaders/geometry "without leaving the engine," but before this only `ShaderEffect` (fullscreen, no geometry) honored it. Now a user renders **their own geometry + shaders + uniforms + textures**, mixed with L2 in one frame -- and the engine's own textured cube is built on this same public API (the SCENE retirement: nothing privileged left baked in).

## The shape
```java
byte[] vs = ShaderCompiler.compileVertex(src, "tri.vert");   // or precompiled SPIR-V
byte[] fs = ShaderCompiler.compileFragment(src, "tri.frag");
VertexLayout layout = VertexLayout.builder(5 * Float.BYTES)
    .attribute(0, AttribFormat.VEC2, 0)
    .attribute(1, AttribFormat.VEC3, 2 * Float.BYTES).build();

Pipeline p = renderer.createPipeline(PipelineSpec.builder()
    .vertexShader(vs).fragmentShader(fs).vertexLayout(layout).build());
Buffer geo = renderer.createVertexBuffer(verts);

renderer.setSceneRenderer(frame -> {
    frame.bind(p);
    frame.bindVertexBuffer(geo);
    frame.draw(3);
});
```

## Decisions

**Thin + guarded (design A), not a structured Mesh/Material (B).** The escape hatch's whole job is flexibility, so jvre hands the user the hard parts it OWNS -- swapchain formats + sample count (injected by `createPipeline`), the frame loop, sync, render-pass setup, viewport -- and the user composes the rest from L1 primitives. The user is writing Vulkan-flavored code, but never touches swapchain/sync/format boilerplate. A structured Mesh/Material is a nice L1.5 convenience to build ON this later, not instead of it.

**The seam is a jvre-owned `FrameRenderer`, not the raw `VkCommandBuffer`.** The user gets full draw control (`bind`/`bindVertexBuffer`/`draw`) without a single LWJGL Vulkan call in their draw code -- keeps custom code L1-clean and lets jvre keep owning the frame (it has already begun the render pass + set viewport/scissor before calling). A content seam, sibling of `renderer2D()` and `setEffect`.

**Additive + DRY via `Pipeline.Kind.CUSTOM`.** Rather than duplicate the ~150-line pipeline bake, a new `CUSTOM` kind funnels through the SAME bake; only `CUSTOM` clauses were added (vertex input from the `VertexLayout`, cull/depth/blend from the spec, no descriptor layout, no push). The 3 built-in pipelines are untouched. Reliable + maintainable over clever.

**The record seam is additive.** Non-effect frames run the scene renderer (if set) AND the L2 shapes (if drawn) -- custom geometry under, L2 UI on top. The cube demo is the fallback when neither is set.

## Beat 2 (done): bound resources + Camera + the cube dogfood
- **Index buffers**: `FrameRenderer.bindIndexBuffer`/`drawIndexed`, `Renderer.createIndexBuffer`.
- **Bound resources**: `PipelineSpec.uniformBuffer(size, stage)` (binding 0) + `texture(stage)` (binding 1) + `pushConstants(size, stage)`, addressed via a `Stage` enum. jvre owns the descriptor plumbing -- the `Pipeline` holds a per-frame UBO buffer + descriptor set (UBO written once, texture written per frame); `frame.bind` auto-binds the set, `frame.uniform`/`frame.texture`/`frame.pushConstants` fill the data. No descriptor pool in user code.
- **`Camera`** (Phase 2c): perspective/lookAt; `viewProjection()` as JOML `Matrix4f` AND `float[16]`; bakes the Vulkan-correct projection (Y-flip + zZeroToOne).
- **The dogfood + SCENE retirement**: the engine's exact textured cube now renders through `createPipeline` + the scene seam; the hardcoded SCENE (cube data, its pipeline/UBO/descriptors/texture, `modelViewProjection`, the `triangle.*` shaders) was deleted. The public path is the only path.

## Known limit (catalogued)
A user pipeline bakes the swapchain format; a format change on resize (rare) would invalidate it. A rebuild hook is a later refinement (see `Renderer.createPipeline`).

## Not unit-tested -- hardware-verified
Pipeline creation + the draw seam are GPU-coupled, so per the [[Definition of Done]] this is verified on hardware (a custom RGB triangle rendering under the L2 scene), like every rendering path.

#design #L1 #pipeline #escape-hatch
