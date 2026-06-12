# 3D and the Depth Buffer

The milestone where the renderer goes genuinely 3D: a tumbling, **solid**, depth-tested cube with six colored faces. Three beats -- perspective math, the depth buffer, the cube -- building on everything before. The substrate was already 3D-capable (a `mat4` pushed per frame for the orbiting quad); this milestone makes that real. Verified on the RTX 4090; validation clean but for the known VMA advisory.

## Beat 1 -- perspective (JOML arrives)

Replaces the hand-rolled 2D affine with a real **model-view-projection**, `proj * view * model`:
- **MODEL** places/orients the object (here a two-axis tumble).
- **VIEW** is the camera (push the world down -z; camera at origin looking down -z, OpenGL/JOML convention).
- **PROJECTION** is the perspective frustum -- what makes distant things smaller (the "3D look"). A flat quad *spinning* under perspective already foreshortens, which is how beat 1 showed depth before a depth buffer existed.

[[Game-Engine Capabilities (planned)|JOML]] (`org.joml`, pure Java) is the matrix library the vault always said would "arrive with 3D." Column-major, matching GLSL/std140, so `matrix.get(float[])` is upload-ready for the UBO.

**Two Vulkan-isms JOML must be told (it defaults to OpenGL) -- the classic first-3D bugs:**
- `zZeroToOne = true`: Vulkan clip-space depth is `[0,1]`, not GL's `[-1,1]`. Wrong = depth testing misbehaves.
- **flip Y** (negate `proj.m11`): Vulkan clip/NDC Y points DOWN vs GL's UP. Wrong = everything upside down. (This flip also reverses apparent triangle winding -- it's why culling needs care; see beat 3.)

## Beat 2 -- the depth buffer

A full-screen image holding, per pixel, the depth of the nearest thing drawn so far. **Depth test** (`compareOp = LESS`) keeps a fragment only if it's nearer than the stored value; **depth write** then stores its depth. Cleared to `1.0` (far) each frame.

- **Lives in [[Swapchain]]**: sized to the swapchain extent, so it's recreated *with* the chain -- resize correctness for free. Same image+memory shape as a [[Textures - Images, Views and Samplers|Texture]] minus the upload and sampler: it's a render target the GPU writes, with a DEPTH-aspect view and `DEPTH_STENCIL_ATTACHMENT` usage. Format is **queried** (`D32_SFLOAT` preferred, effectively universal on desktop).
- **[[Graphics Pipeline|Pipeline]]**: a depth-stencil state (test + write, `LESS`); the depth attachment FORMAT baked into the dynamic-rendering create-info.
- **Recording**: a depth attachment (`CLEAR` on load, `DONT_CARE` on store -- never presented or read back), a depth clear value of `1.0`, and a depth layout barrier (`UNDEFINED -> DEPTH_STENCIL_ATTACHMENT_OPTIMAL`).

**The lesson -- a shared resource ([[Pipeline Barriers]] / [[Synchronization2]]):** the depth buffer is ONE image shared by *both* [[Frames in Flight|frames in flight]] -- unlike the per-frame UBOs, or the swapchain's *rotating* color images (ordered by acquire + the per-image semaphore). So its per-frame layout transition (itself a write) races the **previous frame's** depth writes unless synchronized. With `src = NONE` the sync-validation layer flagged a real `WRITE_AFTER_WRITE` hazard; the fix is to gate the barrier's source scope on the depth-test stages + `DEPTH_STENCIL_ATTACHMENT_WRITE`, so the transition waits for the prior frame's depth use. The most instructive moment of the milestone -- exactly why sync-validation is left on. (Alternative: one depth image *per* frame; one shared + a correct barrier is the standard choice.)

## Beat 3 -- the cube

- **Geometry**: 24 vertices (`[x y z | r g b | u v]`, stride 32) + 36 indices. Four-per-face x6, **not** 8 shared corners: a textured, per-face-colored cube can't share corners, because each face needs its own full UVs (`0..1`) and its own color.
- **Texture swapped to opaque grayscale checker**; the fragment shader multiplies it by the per-face vertex color, so the six faces read as six distinct colors. (The alpha-blend pipeline state stays on -- opaque alpha just makes it a no-op.)
- **Depth makes it solid**: near faces cleanly occlude far ones. WITHOUT depth, faces rasterize in submission order and pop through each other -- depth is precisely what fixes that. This is the milestone made visible.
- **Culling deferred (cull NONE)**: depth alone gives correctness (back faces lose the test), so culling is an *optimization*, not required. Skipped this beat because winding interacts with the beat-1 Y-flip -- best added with a visual check. Done as a follow-up; see below.

## Follow-up -- back-face culling (and the two-mirror winding lesson)

For a closed opaque mesh you can never see a face whose outside points away; the rasterizer drops those triangles by their on-screen **winding** before any fragment work -- half the cube's faces skipped per frame. Depth made the image *correct*; culling makes it *cheap*.

**The lesson came from getting it wrong first.** The armchair derivation said: "the Y-flip is a mirror, mirrors reverse winding, so the authored CCW-from-outside faces arrive CW -> `frontFace = CLOCKWISE`." Result: an **inside-out cube** -- the near faces culled, looking into a hollow shell. The unmissable symptom of culling the front faces.

The correct account has **two mirrors, which cancel**:
1. Vulkan's y-DOWN NDC/framebuffer is itself a mirror relative to the GL conventions the projection math comes from -- alone it would flip CCW-from-outside to CW on screen.
2. The negated `m11` (our Y-flip) is a *second* mirror on top.

Mirror x mirror = no net flip: the authored CCW winding survives to the screen, and `frontFace = COUNTER_CLOCKWISE` is right after all. (The famous "flipping `proj[1][1]` forces a frontFace change" gotcha is real but *relative to not flipping* -- it does not make CW Vulkan's natural front.)

**Verification asymmetry worth remembering:** winding bugs produce no validation errors, no crashes -- only a wrong picture. Correct culling looks *identical* to no culling (the culled faces are exactly the ones depth already hid); wrong culling is instantly obvious (inside-out). Sequencing culling AFTER the cube visibly worked turned "the least debuggable bug in graphics" into a ten-second observation and a one-word fix.

## Where this sits

L1 is now genuinely **3D-capable in practice**, matching [[Game-Engine Capabilities (planned)|the capabilities decision]] ("L1 stays 3D-capable; L2 stays flat by design; a friendly 3D API is the deferred third altitude"). And the own-`VkImage` + (now) depth-buffer machinery is exactly the prerequisite MSAA needs.

## Next

The **VMA milestone** (every image/buffer is another best-practices-flagged single allocation -- the cube + depth buffer keep growing the count), then **MSAA**.

#vulkan #3d #depth #perspective #joml
