package jvre.demo;

import jvre.core.Color;
import jvre.core.Diagnostics;
import jvre.core.Instance;
import jvre.core.RenderTarget;
import jvre.core.Renderer;
import jvre.core.Renderer2D;
import jvre.core.RendererOptions;

import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * HEADLESS rendering -- no window, no swapchain, no display. Render L2 shapes into
 * an offscreen {@link RenderTarget}, read the pixels back, and write a PNG. This is
 * the engine for automated visual-regression testing (render -> read back -> diff a
 * golden PNG -- the one thing CI structurally couldn't do) and offscreen image
 * generation. Lives in {@code jvre.demo} (excluded from the published jar), like
 * {@code GuiDemo}.
 *
 * <p>The whole setup is just {@link Instance} (headless) + the headless {@link
 * Renderer} -- no {@code Window} / {@code Surface}. Run: {@code gradlew runHeadless}.
 */
public final class HeadlessDemo {

    private static final int SIZE = 512;

    public static void main(String[] args) {
        Configuration.STACK_SIZE.set(512);
        Diagnostics.init("jvre headless demo");

        // No window: a headless Instance (no GLFW surface extensions) + the headless
        // Renderer (no surface/swapchain). Validation ON, so the offscreen path is
        // checked exactly like the windowed one.
        Instance instance = new Instance("jvre headless", true, true);
        Renderer renderer = new Renderer(instance, RendererOptions.builder()
                .clearColor(0.1f, 0.12f, 0.18f)
                .build());

        RenderTarget target = renderer.createRenderTarget(SIZE, SIZE);
        Renderer2D g = renderer.createCanvas(target);

        // Draw a recognizable little scene into the offscreen canvas.
        g.begin();
        g.fillRoundedRect(0, 0, SIZE, SIZE, 36, Color.rgba(28, 32, 46, 255));
        g.fillCircle(SIZE * 0.36f, SIZE * 0.40f, SIZE * 0.12f, Color.rgb(90, 200, 255));
        g.fillRect(SIZE * 0.52f, SIZE * 0.28f, SIZE * 0.24f, SIZE * 0.24f, Color.rgb(255, 180, 60));
        g.strokeCircle(SIZE * 0.5f, SIZE * 0.5f, SIZE * 0.42f, 6, Color.rgba(120, 130, 150, 200));
        g.text("headless", SIZE * 0.12f, SIZE * 0.64f, SIZE * 0.12f, Color.WHITE);
        g.text("no window", SIZE * 0.12f, SIZE * 0.78f, SIZE * 0.07f, Color.rgb(170, 180, 200));
        g.end();

        renderer.render();   // execute the offscreen pass (records + submits + waits; no present)

        // Read the result back to CPU memory and write a PNG (file format is the
        // caller's choice -- stb_image_write at the edge).
        byte[] rgba = renderer.readPixels(target);
        writePng("jvre-headless.png", rgba, SIZE, SIZE);

        target.close();
        renderer.close();
        instance.close();
        System.out.println("Headless render complete (no window was ever opened).");
    }

    private static void writePng(String path, byte[] rgba, int w, int h) {
        ByteBuffer buf = MemoryUtil.memAlloc(rgba.length);
        buf.put(rgba).flip();
        boolean ok = STBImageWrite.stbi_write_png(path, w, h, 4, buf, w * 4);
        MemoryUtil.memFree(buf);
        System.out.println(ok
                ? "Wrote " + new File(path).getAbsolutePath()
                : "PNG write failed (stb_image_write).");
    }
}
