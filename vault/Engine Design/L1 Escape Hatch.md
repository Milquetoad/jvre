# L1 Escape Hatch (user-defined pipelines)

**Beat 1 shipped 2026-06-15.** [[Roadmap]] Phase 2a -- the most *vision-significant* gap closed: the [[Design North Star]] promises dropping to custom shaders/geometry "without leaving the engine," but before this only `ShaderEffect` (fullscreen, no geometry) honored it. Now a user renders **their own geometry + shaders**, mixed with L2 in one frame.

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

## Beat-1 boundaries (what beat 2 adds)
- **No bound resources yet** -- no descriptors/UBO/push, so no per-draw uniforms or textures. Beat 2 grows `PipelineSpec` (descriptor layout + push) and `FrameRenderer` (`bindIndexBuffer`/`drawIndexed`/`pushConstants`/`bindDescriptor`).
- **The cube dogfood + the [[Roadmap|Camera (2c)]]** land in beat 2: port the hardcoded cube onto this public path (the real validation) with a real `Camera` feeding the MVP.
- **Format-change rebuild:** a user pipeline bakes the swapchain format; a format change on resize (rare) would invalidate it. A rebuild hook is a later refinement.

## Not unit-tested -- hardware-verified
Pipeline creation + the draw seam are GPU-coupled, so per the [[Definition of Done]] this is verified on hardware (a custom RGB triangle rendering under the L2 scene), like every rendering path.

#design #L1 #pipeline #escape-hatch
