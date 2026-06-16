# Getting started

This guide takes you from nothing to a window with a rectangle in it.

## 1. Add the dependency

jvre is published via [JitPack](https://jitpack.io) while it is pre-1.0 (Maven
Central is planned at 1.0). In your `build.gradle`:

```gradle
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.Milquetoad:jvre:v0.1.0'

    // jvre does NOT bundle platform natives -- you choose them for your OS.
    // Add the matching natives classifier for the LWJGL modules jvre uses.
    def lwjgl = '3.3.4'
    def natives = 'natives-windows' // or natives-linux / natives-macos / -arm64
    runtimeOnly("org.lwjgl:lwjgl:$lwjgl:$natives")
    runtimeOnly("org.lwjgl:lwjgl-glfw:$lwjgl:$natives")
    runtimeOnly("org.lwjgl:lwjgl-vma:$lwjgl:$natives")
    runtimeOnly("org.lwjgl:lwjgl-shaderc:$lwjgl:$natives")
    runtimeOnly("org.lwjgl:lwjgl-spvc:$lwjgl:$natives")
    runtimeOnly("org.lwjgl:lwjgl-stb:$lwjgl:$natives")
}
```

The LWJGL libraries themselves arrive transitively through jvre's POM; only the
per-OS **natives** are yours to pick (the standard LWJGL consumer pattern).

> **Why you pick the natives:** a library can't know which OS you'll run on, so it
> would be wrong to bake in, say, Windows `.dll`s. You declare the classifier for
> your platform; on a CI matrix or a cross-platform build you'd select it per host.

## 2. Hello, rectangle

A complete jvre program has three parts: **set up** the object stack, **loop**
while the window is open, and **tear down** in reverse order.

```java
import jvre.core.Color;
import jvre.core.Instance;
import jvre.core.Renderer;
import jvre.core.Renderer2D;
import jvre.core.RendererOptions;
import jvre.core.Surface;
import jvre.core.Window;
import org.lwjgl.system.Configuration;

public class Hello {
    public static void main(String[] args) {
        // Some drivers expose enough extensions to overflow LWJGL's default 64 KB
        // per-thread stack during setup; bump it before any Vulkan call. (Harmless
        // if you never hit the limit.)
        Configuration.STACK_SIZE.set(512);

        // --- set up: Window -> Instance -> Surface -> Renderer ---
        Window window = new Window(800, 600, "Hello jvre");
        Instance instance = new Instance("hello", /* validation */ true);
        Surface surface = new Surface(instance, window);
        Renderer renderer = new Renderer(instance, surface, window,
                RendererOptions.builder()
                        .clearColor(0.10f, 0.11f, 0.14f)   // the per-frame background
                        .build());

        // Ask the renderer for its 2D drawing surface.
        Renderer2D g = renderer.renderer2D();

        // --- loop: poll input, draw a frame, present ---
        while (!window.shouldClose()) {
            window.pollEvents();

            g.begin();                                       // start the frame's drawing
            g.fillRect(300, 250, 200, 100, Color.rgb(80, 160, 240));
            g.end();                                         // finish drawing

            renderer.drawFrame();                            // record + submit + present
        }

        // --- tear down: wait for the GPU, then close in REVERSE creation order ---
        renderer.waitIdle();
        renderer.close();
        surface.close();
        instance.close();
        window.close();
    }
}
```

Run it and you get an 800×600 window on a dark background with a blue rectangle at
`(300, 250)`, 200 wide and 100 tall. Close the window to exit.

## 3. What just happened

- **`Window`** opens an OS window (via GLFW) and owns input. It is resizable out of
  the box.
- **`Instance`** is the Vulkan instance. Passing `true` turns on the validation
  layers — invaluable while developing (they catch API misuse and print exactly
  what's wrong). Pass `false` for a release run with no validation overhead.
- **`Surface`** is the bridge that lets Vulkan present into your window.
- **`Renderer`** is the workhorse: it selects a GPU, builds the swapchain, and
  drives per-frame command recording. `RendererOptions` carries creation-time
  knobs (clear color, vsync, MSAA, GPU preference) — see below.
- **`Renderer2D`** (from `renderer.renderer2D()`) is the high-level surface. Each
  frame you bracket your drawing with `begin()` / `end()`, then call
  `renderer.drawFrame()` to put it on screen.

The drawing happens in **pixel coordinates with a top-left origin**: `(0, 0)` is
the top-left corner, `x` grows right, `y` grows down — the same convention as most
2D canvases. `Color.rgb(r, g, b)` takes the usual 0–255 channels.

## 4. Configuring the renderer

`RendererOptions` is a builder of creation-time settings (they're baked into the
swapchain/pipelines, so they're fixed for the renderer's life):

```java
RendererOptions.builder()
    .clearColor(0.10f, 0.11f, 0.14f) // background RGB in [0,1]; default black
    .vsync(true)                     // true = capped to the display (no tearing); false = uncapped
    .msaa(4)                         // anti-aliasing samples: 1, 2, 4 (default), 8
    .preferGpu("RTX")                // name substring to prefer; null = auto-pick best
    .build();
```

`RendererOptions.defaults()` is the zero-config option (black clear, vsync on,
MSAA 4, auto GPU).

## Next steps

- **[2D graphics guide](2d-graphics.md)** — all the shapes, text, images, the
  transform stack, and how to read input and time so your scene can move and react.
- The [API overview](README.md) shows the other two altitudes (shader effects and
  custom pipelines) when you're ready to go lower.
