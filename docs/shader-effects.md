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
silently with validation off). An effect that compiles but, say, binds a texture
sampler or declares an oversized push-constant block is **rejected with a clear
explanation**. The contract is deliberately tiny in v1 (just resolution / mouse /
time); richer inputs are a future extension.

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
