package jvre.demo;

import jvre.core.Color;
import jvre.core.Diagnostics;
import jvre.core.Font;
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
 * MSDF vs single-channel SDF text, headless. Bakes the SAME font both ways from a
 * moderate 48px atlas, then renders large display text from each so the difference
 * shows where it matters: single-channel SDF ROUNDS sharp corners when a small bake
 * is magnified; MSDF's median-of-three reconstruction keeps them crisp. Both rows
 * draw the same glyphs at the same size into one offscreen target; writes a PNG to
 * compare. Run: {@code gradlew runMsdf}.
 */
public final class MsdfDemo {

    private static final int W = 760;
    private static final int H = 360;
    // Glyphs chosen for sharp corners: A/W/V/M apexes, the 4 angle, R/K junctions.
    private static final String SAMPLE = "RAWK 4AV";

    public static void main(String[] args) {
        Configuration.STACK_SIZE.set(512);
        Diagnostics.init("jvre msdf demo");
        Instance instance = new Instance("jvre msdf", true, true);   // headless
        Renderer renderer = new Renderer(instance, RendererOptions.builder()
                .clearColor(0.10f, 0.11f, 0.14f)
                .build());

        // The SAME TTF, baked both ways at the SAME moderate height -- the only
        // difference under test is single-channel vs multi-channel.
        Font sdf  = renderer.loadFont("/fonts/DejaVuSans.ttf", 48f);
        Font msdf = renderer.loadMsdfFont("/fonts/DejaVuSans.ttf", 48f);

        RenderTarget target = renderer.createRenderTarget(W, H);
        Renderer2D g = renderer.createCanvas(target);

        float big = 120f;   // render WAY above the 48px bake -> magnifies the corners
        g.begin();
        g.fillRect(0, 0, W, H, Color.rgb(26, 28, 36));
        g.text(sdf,  "SDF",  20, 16, 28, Color.rgb(150, 160, 180));
        g.text(sdf,  SAMPLE, 20, 48, big, Color.WHITE);
        g.text(msdf, "MSDF", 20, 196, 28, Color.rgb(150, 160, 180));
        g.text(msdf, SAMPLE, 20, 228, big, Color.WHITE);
        g.end();

        renderer.render();

        byte[] rgba = renderer.readPixels(target);
        writePng("jvre-msdf.png", rgba, W, H);

        msdf.close();
        sdf.close();
        target.close();
        renderer.close();
        instance.close();
        System.out.println("MSDF demo complete (top row SDF, bottom row MSDF; compare the corners).");
    }

    private static void writePng(String path, byte[] rgba, int w, int h) {
        ByteBuffer buf = MemoryUtil.memAlloc(rgba.length);
        buf.put(rgba).flip();
        boolean ok = STBImageWrite.stbi_write_png(path, w, h, 4, buf, w * 4);
        MemoryUtil.memFree(buf);
        System.out.println(ok ? "Wrote " + new File(path).getAbsolutePath() : "PNG write failed.");
    }
}
