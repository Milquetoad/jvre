# Custom pipelines (the L1 escape hatch)

When the 2D surface and shader effects don't cover what you need — your own
geometry, your own vertex *and* fragment shaders, 3D meshes — you drop to L1. You
describe a pipeline (shaders + vertex layout + state), hand jvre your geometry, and
record draw calls in a **scene renderer**. jvre still owns the frame (swapchain,
synchronization, the command buffer); you never touch raw Vulkan handles.

You can mix this with the 2D surface in the same frame — e.g. a custom 3D scene
*under* a `Renderer2D` overlay.

## The pieces

| Step | API |
|---|---|
| Compile GLSL → SPIR-V | `ShaderCompiler.compileVertex/compileFragment` |
| Describe the vertex layout | `VertexLayout.builder(stride).attribute(...)` |
| Build the pipeline | `renderer.createPipeline(PipelineSpec.builder()...)` |
| Upload geometry | `renderer.createVertexBuffer(float[])` / `createIndexBuffer(short[])` |
| Record draws each frame | `renderer.setSceneRenderer(frame -> { ... })` |
| 3D matrices (optional) | `Camera` |

## A minimal example: a coloured triangle

Each vertex here is `[x, y | r, g, b]` — a 2D position in clip space and a colour.

**The shaders** (compiled at runtime, so no build-time step):

```java
String vs = """
    #version 450
    layout(location = 0) in vec2 inPos;
    layout(location = 1) in vec3 inColor;
    layout(location = 0) out vec3 vColor;
    void main() { gl_Position = vec4(inPos, 0.0, 1.0); vColor = inColor; }
    """;
String fs = """
    #version 450
    layout(location = 0) in vec3 vColor;
    layout(location = 0) out vec4 outColor;
    void main() { outColor = vec4(vColor, 1.0); }
    """;
byte[] vSpirv = ShaderCompiler.compileVertex(vs, "tri.vert");
byte[] fSpirv = ShaderCompiler.compileFragment(fs, "tri.frag");
```

**The vertex layout** — describe the stride and where each attribute sits, matching
the shader's `location`s:

```java
VertexLayout layout = VertexLayout.builder(5 * Float.BYTES)   // 5 floats per vertex
        .attribute(0, AttribFormat.VEC2, 0)                  // inPos at offset 0
        .attribute(1, AttribFormat.VEC3, 2 * Float.BYTES)    // inColor after the 2 pos floats
        .build();
```

`AttribFormat` is `FLOAT`, `VEC2`, `VEC3`, or `VEC4`.

**The pipeline and geometry:**

```java
Pipeline tri = renderer.createPipeline(PipelineSpec.builder()
        .vertexShader(vSpirv)
        .fragmentShader(fSpirv)
        .vertexLayout(layout)
        .label("triangle")
        .build());

Buffer verts = renderer.createVertexBuffer(new float[] {
    //   x      y       r   g   b
         0.0f, -0.5f,   1f, 0f, 0f,
         0.5f,  0.5f,   0f, 1f, 0f,
        -0.5f,  0.5f,   0f, 0f, 1f,
});
```

> jvre supplies the swapchain colour/depth formats and sample count to the
> pipeline for you — you never pass them. That's the "guarded" part of the escape
> hatch: full shader/geometry control, but the frame plumbing stays jvre's.

**Record the draw** each frame in a scene renderer (set it once; it runs every
frame):

```java
renderer.setSceneRenderer(frame -> {
    frame.bind(tri);
    frame.bindVertexBuffer(verts);
    frame.draw(3);   // 3 vertices
});
```

`frame` is a `FrameRenderer` — a thin, safe facade over the in-progress command
buffer: `bind`, `bindVertexBuffer` / `bindIndexBuffer`, `uniform`, `texture`,
`pushConstants`, `draw`, `drawIndexed`. With the pipeline bound and the scene
renderer set, your normal loop (`pollEvents` → `drawFrame`) draws the triangle.

## Going 3D: uniforms and the Camera

For 3D you need a model-view-projection matrix. Declare a uniform buffer in the
pipeline, write it each frame, and read it in the vertex shader. The `Camera`
helper builds a **Vulkan-correct** view-projection (it handles the clip-space
Y-flip and `[0,1]` depth range so you don't have to).

Add a UBO to the shader and the spec:

```glsl
// vertex shader
layout(set = 0, binding = 0) uniform U { mat4 mvp; } u;
void main() { gl_Position = u.mvp * vec4(inPos, 1.0); /* ... */ }
```

```java
Pipeline mesh = renderer.createPipeline(PipelineSpec.builder()
        .vertexShader(vSpirv).fragmentShader(fSpirv)
        .vertexLayout(layout)
        .depthTest(true).depthWrite(true)     // depth-test a 3D mesh
        .cull(Cull.BACK)                       // cull back faces (BACK + CCW front)
        .uniformBuffer(16 * Float.BYTES, Stage.VERTEX)   // a mat4 (16 floats) at binding 0
        .label("mesh").build());

Camera camera = new Camera();
```

Then each frame, compute the matrix and write it:

```java
renderer.setSceneRenderer(frame -> {
    float aspect = renderer.renderer2D().width() / (float) renderer.renderer2D().height();
    camera.perspective(45f, aspect, 0.1f, 100f)
          .lookAt(0, 0, 4,   0, 0, 0,   0, 1, 0);   // eye, target, up

    // model * view-projection -> a float[16] for the UBO (JOML shown here)
    org.joml.Matrix4f model = new org.joml.Matrix4f().rotateY(renderer.time());
    float[] mvp = new org.joml.Matrix4f(camera.viewProjection()).mul(model).get(new float[16]);

    frame.bind(mesh);
    frame.uniform(mvp);                  // write this frame's UBO (binding 0)
    frame.bindVertexBuffer(verts);
    frame.draw(3);
});
```

`Camera.viewProjection()` returns a JOML `Matrix4f`; there are also
`viewProjection(Matrix4f dest)` and `viewProjection(float[] dest16)` overloads if
you'd rather not allocate or don't want a JOML dependency in your own code.

## Adding the rest: indices, textures, push constants

The same `PipelineSpec` / `FrameRenderer` vocabulary covers the rest of a typical
mesh:

- **Index buffers** — `renderer.createIndexBuffer(short[])`, then
  `frame.bindIndexBuffer(buf)` + `frame.drawIndexed(count)`.
- **Textures** — add `.texture(Stage.FRAGMENT)` to the spec (a
  `sampler2D` at `binding = 1`), and `frame.texture(tex)` each frame.
- **Multiple texture channels** — `.texture(...)` is *additive*: each call adds the
  next `sampler2D` channel, at `binding = 1`, `2`, `3`, … in call order. Write each
  one with the channel overload `frame.texture(channel, tex)` (channel 0 = binding
  1). This is how an effect/post-processing pass samples several inputs at once —
  e.g. blend two offscreen [render targets](render-to-texture.md):

  ```glsl
  layout(set = 0, binding = 1) uniform sampler2D ch0;   // first  .texture()
  layout(set = 0, binding = 2) uniform sampler2D ch1;   // second .texture()
  ```
  ```java
  Pipeline blend = renderer.createPipeline(PipelineSpec.builder()
          .vertexShader(vs).fragmentShader(fs).vertexLayout(layout)
          .texture(Stage.FRAGMENT)      // channel 0 -> binding 1
          .texture(Stage.FRAGMENT)      // channel 1 -> binding 2
          .label("blend").build());

  renderer.drawToTarget(out, frame -> {
      frame.bind(blend);
      frame.texture(0, a.texture());    // channel 0 = target A
      frame.texture(1, b.texture());    // channel 1 = target B
      frame.bindVertexBuffer(tri);
      frame.draw(3);
  });
  ```
  A texture channel can be a **cubemap** or a **3D volume**, not just a 2D image:
  bind a `renderer.createCubemap(...)` to a `samplerCube` channel (skyboxes,
  reflections) or a `renderer.createVolume(...)` to a `sampler3D` channel (volumes,
  3D LUTs). The pipeline spec is the same `.texture(Stage.FRAGMENT)` — only the
  shader's sampler kind and the texture you bind differ. See `jvre.demo.CubemapDemo`
  / `VolumeDemo` (`gradlew runCubemap` / `runVolume`).
- **Push constants** — add `.pushConstants(sizeBytes, Stage.FRAGMENT)`, and
  `frame.pushConstants(float[])` each frame (small, fast per-draw data).

jvre owns the descriptor pool/sets behind these — you declare *what* you need in
the spec and write the *data* through `frame`; the binding plumbing is automatic.

A complete worked example combining all of the above — an indexed, textured,
depth-tested, back-face-culled, tumbling cube driven by a `Camera` and a
fragment-stage push constant — is the demo's `setSceneRenderer` block in
[`Main.java`](../src/main/java/jvre/Main.java).

## Live-reloading shaders

You can swap new shader code into a pipeline **in place**, without recreating it or
re-wiring your scene renderer — the foundation of shader live-reload (recompile on
file save, see the change instantly):

```java
// From a file-watch callback, say. GLSL source overload (compiles for you):
pipeline.reloadShaders(newVertexGlsl, newFragmentGlsl);
// ...or hand it SPIR-V you compiled yourself:
pipeline.reloadShaders(vSpirv, fSpirv);
```

Your `Pipeline` reference stays valid — the bound lambda keeps drawing — because the
rebuild happens *inside* the same object. Two rules:

- The reloaded shader must keep the **same resource interface** as the original
  `PipelineSpec` (same UBO size, same texture channels, same push range). Only the
  shader *body* may change. Changing the interface needs a fresh `createPipeline`.
- It's **not** a hot-path call — it drains the GPU before rebuilding, so call it
  *between* frames, not inside your scene renderer.

If the new GLSL fails to compile, `reloadShaders` throws `ShaderCompileException`
and **leaves the running pipeline untouched** — a broken edit never takes down the
last good version.

## Compile errors are structured

`ShaderCompiler` (and the GLSL `reloadShaders` overload) don't just throw a string —
a failed compile throws `ShaderCompileException`, which carries shaderc's log parsed
into `ShaderDiagnostic` records so tooling can act on it (jump-to-line, an overlay):

```java
try {
    pipeline.reloadShaders(vs, editedFrag);
} catch (ShaderCompileException e) {
    for (ShaderDiagnostic d : e.errors()) {   // .diagnostics() includes warnings too
        System.out.println(d.line() + ": " + d.message());   // d.severity(), d.raw()
    }
}
```

`e.rawLog()` is always the unmodified shaderc text, so nothing is lost even if the
message shape is one the parser didn't recognise.

## Lifetime

Pipelines and buffers/textures you create are **yours to close**, before the
renderer:

```java
tri.close();
verts.close();
// ... then renderer.close(); surface.close(); instance.close(); window.close();
```

## Next steps

- Mix this with the [2D surface](2d-graphics.md): call `renderer.renderer2D()` and
  draw a UI overlay in the same frame as your custom scene.
- For a single full-screen shader instead of geometry, the
  [shader effects](shader-effects.md) altitude is simpler.
- Render your scene into an offscreen image instead of the screen — for
  post-processing or compositing — with [render to texture](render-to-texture.md)
  (`renderer.drawToTarget(target, frame -> { ... })`).
