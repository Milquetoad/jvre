# Ray Tracing & Path Tracing (future)

**Yes, jvre can get here.** Two routes, both reachable from the architecture we're building. Advanced milestone — well past clear-to-color / 2D / basic 3D — but nothing in our path forecloses it.

## Route 1 — "software" path tracing in a compute shader
Write the path tracer *as a shader* (ray gen, bounces, material sampling, accumulation) running over an image via a **compute pipeline**, writing into a **storage image**.
- Needs only: compute support (universal) + storage images. No special hardware.
- A direct extension of the Shadertoy-style shader path in [[API Vision - Layered Altitudes]] — a fullscreen fragment/compute shader *is* the seed of a path tracer.
- **The gentler entry point.**

## Route 2 — hardware-accelerated ray tracing
Vulkan extensions (device-level):
- `VK_KHR_acceleration_structure` — build BVH over geometry.
- `VK_KHR_ray_tracing_pipeline` — ray-gen / closest-hit / miss shaders.
- `VK_KHR_ray_query` — fire rays from any shader (incl. compute).

The machine's **RTX 4090 has dedicated RT cores** → this is what it's best at. Bigger lift: manage acceleration structures, a shader binding table, and the RT pipeline. Enabled on the **device** (instance-level vs device-level scope, see [[Vulkan Instance]]).

## Prerequisites in jvre
Compute pipelines, storage images, buffers (both routes); plus the RT extensions + acceleration-structure management (route 2). All built on the clean L1 core from [[API Vision - Layered Altitudes]] — which is exactly the foundation a path tracer needs.

## Suggested order when we get there
Compute path tracing first (gentler, builds on shader-illustration), hardware RT as the ambitious follow-up.

#vulkan #raytracing #future
