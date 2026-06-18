# Shader effects (`ShaderEffect`)

The effects altitude is for full-screen, procedural visuals — the Shadertoy
experience. You write **one GLSL fragment shader**; jvre runs it over a fullscreen
triangle and feeds it the resolution, mouse, and time each frame. No vertex
shader, no geometry, no descriptors — just a function from pixel to color.

```java
renderer.setEffect(ShaderEffect.fromFragment("/effects/ripple.frag"));
```

After that your normal frame loop (`pollEvents` → `drawFrame`) drives it; you don't
call `renderer2D()` at all for a pure-effect program.

## The contract

Every effect shader follows the same small contract:

```glsl
#version 450

layout(location = 0) out vec4 outColor;          // write your final color here

layout(push_constant) uniform Push {
    vec2  uResolution;   // framebuffer size in pixels
    vec2  uMouse;        // cursor position in pixels, top-left origin
    float uTime;         // seconds since the renderer started
} pc;                    // jvre fills this in for you every frame

void main() {
    // gl_FragCoord.xy is the pixel coordinate, TOP-LEFT origin (Vulkan y-down --
    // the same direction uMouse uses, so they line up).
    vec2 uv = gl_FragCoord.xy / pc.uResolution;   // 0..1 across the screen
    outColor = vec4(uv, 0.5 + 0.5 * sin(pc.uTime), 1.0);
}
```

That's the whole interface:

- **Declare the push-constant block exactly as shown** (you can name the struct
  variable whatever you like). jvre writes `uResolution`, `uMouse`, and `uTime`
  into it before each frame.
- **Read pixel position from `gl_FragCoord.xy`**, top-left origin.
- **Write the result to `outColor`.**

A common first move is to make aspect-correct, centred coordinates:

```glsl
vec2 p = (gl_FragCoord.xy * 2.0 - pc.uResolution) / pc.uResolution.y; // y in [-1,1]
```

## Input channels (textures)

An effect can also **sample textures** — the Shadertoy `iChannel0..3` convention.
Declare a `sampler2D` at set 0, binding *N* for `iChannelN` (up to 4 channels), and
feed each one a texture from Java:

```glsl
layout(set = 0, binding = 0) uniform sampler2D iChannel0;   // binding N = iChannelN
// ... iChannel1 at binding 1, etc.

void main() {
    vec2 uv = gl_FragCoord.xy / pc.uResolution;
    outColor = texture(iChannel0, uv);
}
```

```java
ShaderEffect e = ShaderEffect.fromFragment("/effects/feedback.frag");
renderer.setEffect(e);
renderer.setEffectChannel(0, myImage);              // bind iChannel0
renderer.setEffectChannel(1, target.texture());     // or a RenderTarget's output
```

`setEffectChannel` is re-pointable any frame, so feeding a channel a
[render target](render-to-texture.md)'s `texture()` makes a **post-processing
pass** — the effect reads last pass's output. Channels you leave unset sample a 1×1
default. `e.channelCount()` reports how many the shader declared (0 for a classic
no-input effect). The contract guard allows a sampler at set 0, binding 0..3;
any other bound resource — a UBO, a sampler out of range — is still rejected.

### Cubemap and 3D channels

A channel can also be a **`samplerCube`** or a **`sampler3D`**, not just a
`sampler2D` — for skyboxes, environment reflections, 3D LUTs, or raymarched volumes:

```glsl
layout(set = 0, binding = 0) uniform samplerCube iChannel0;   // sample with a direction
layout(set = 0, binding = 1) uniform sampler3D   iChannel1;   // sample with vec3 (u,v,w)
```

```java
Texture sky = renderer.createCubemap(sixFaces, faceSize);  // +X,-X,+Y,-Y,+Z,-Z
Texture lut = renderer.createVolume(voxels, w, h, d);
renderer.setEffectChannel(0, sky);
renderer.setEffectChannel(1, lut);
```

jvre reflects each channel's declared kind, so binding a texture of the wrong kind
(e.g. a 2D texture to a `samplerCube` channel) fails with a clear jvre-level error
rather than a cryptic validation message. See `jvre.demo.EffectCubeDemo`
(`gradlew runEffectCube`) for a samplerCube skybox effect.

### Live CPU data: dynamic channels

For data that changes every frame — an audio spectrum, procedural noise, or
keyboard state — bind a **`DynamicTexture`** instead of a static one. You rewrite
its pixels each frame and the channel samples the fresh data:

```java
DynamicTexture data = renderer.createDynamicTexture(256, 1);
renderer.setEffectChannel(0, data);   // bind once
while (open) {
    data.update(buildPixels());       // new pixels each frame (R8G8B8A8)
    renderer.drawFrame();
}
```

It's double-buffered per frame-in-flight internally, so updating never races the
GPU. `jvre.demo.KeyboardDemo` (`gradlew runKeyboard`) builds the Shadertoy
keyboard-channel convention (a 256×3 state texture) on top of this — note that the
key→column map is the *app's* to define, not jvre's.

## Loading the shader

Two ways to create an effect:

```java
// (a) From a classpath resource -- a .frag file in src/main/resources.
ShaderEffect e = ShaderEffect.fromFragment("/effects/ripple.frag");

// (b) From a source string built at runtime.
ShaderEffect e = ShaderEffect.fromFragmentSource(myGlslString, "ripple");
```

In **both** cases the GLSL is compiled to SPIR-V **at runtime** (via the bundled
shaderc — no Vulkan SDK required on the user's machine). If the shader has a syntax
error, creation fails immediately with the compiler's message and the shader's own
line numbers — *before* any frame is drawn.

## The contract guard

jvre validates the compiled shader against the contract at creation time, so a
mistake fails loudly and early instead of detonating deep inside Vulkan (or, worse,
silently with validation off). An effect that compiles but, say, binds a UBO, a
storage image, or a sampler outside the `iChannel0..3` range — or declares an
oversized push-constant block — is **rejected with a clear explanation**. The
allowed interface is the built-in push block plus up to four `sampler2D` input
channels (above).

## Live-reload

`setEffect` is **safe to call again while an effect is running** — that's live
reload. Re-load the shader and set it again, e.g. from a key press or a file watch:

```java
if (window.input().keyPressed(Key.F5)) {
    try {
        renderer.setEffect(ShaderEffect.fromFragment("/effects/ripple.frag"));
    } catch (RuntimeException e) {
        System.out.println(e.getMessage());   // broken edit -> keep the running shader
    }
}
```

The new `ShaderEffect` re-compiles and re-checks the contract *at creation*, so a
broken edit throws there — catch it and the **running effect keeps going** (the bad
one is never installed). A good edit is swapped into the live pipeline **in place**
(it drains the GPU first, so it's safe mid-loop) — no restart, no flicker. The
bundled demo wires exactly this to **F5**.

A failed compile throws `ShaderCompileException` (a `RuntimeException`), which
carries the parsed compiler output as `ShaderDiagnostic` records (name, line,
severity, message) in addition to the formatted `getMessage()` — handy if you want
to surface errors in a tool rather than just print them.

## A complete effect program

```java
import jvre.core.*;
import org.lwjgl.system.Configuration;

public class Effect {
    public static void main(String[] args) {
        Configuration.STACK_SIZE.set(512);

        Window window = new Window(800, 600, "jvre effect");
        Instance instance = new Instance("effect", true);
        Surface surface = new Surface(instance, window);
        Renderer renderer = new Renderer(instance, surface, window,
                RendererOptions.defaults());

        renderer.setEffect(ShaderEffect.fromFragment("/effects/ripple.frag"));

        while (!window.shouldClose()) {
            window.pollEvents();
            renderer.drawFrame();
        }

        renderer.waitIdle();
        renderer.close();
        surface.close();
        instance.close();
        window.close();
    }
}
```

Put `ripple.frag` in `src/main/resources/effects/` and it loads from the classpath
as `/effects/ripple.frag`. Edit the shader, re-run, and iterate — no build-time
shader step is involved.

## Next steps

- For 2D drawing with shapes/text instead of a single shader, see the
  [2D graphics guide](2d-graphics.md).
- For your own geometry and 3D (your own vertex *and* fragment shaders, meshes,
  uniforms, textures), see the [custom pipelines guide](custom-pipelines.md).
