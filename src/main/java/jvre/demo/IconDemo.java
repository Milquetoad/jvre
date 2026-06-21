package jvre.demo;

import jvre.core.Diagnostics;
import jvre.core.Instance;
import jvre.core.Renderer;
import jvre.core.RendererOptions;
import jvre.core.Surface;
import jvre.core.Window;

import org.lwjgl.system.Configuration;

/**
 * Window-icon API demo ({@link Window#setIcon}). Exercises BOTH overloads -- a
 * generated raw-RGBA icon and a decoded classpath PNG -- then runs a few frames so
 * the icon is visible in the taskbar / title bar.
 *
 * <p>Windowed (an icon is a window-chrome thing), frame-capped so it self-exits for a
 * validation check; the icon's APPEARANCE is an owner eyeball. Run: {@code gradlew
 * runIcon} (or {@code --args="<frames>"}).
 */
public final class IconDemo {

    private static final int W = 480;
    private static final int H = 320;

    public static void main(String[] args) {
        int cap = args.length > 0 ? Integer.parseInt(args[0]) : 240;

        Configuration.STACK_SIZE.set(512);
        Diagnostics.init("jvre icon demo");

        Window window = new Window(W, H, "jvre - window icon");
        Instance instance = new Instance("jvre icon", true);
        Surface surface = new Surface(instance, window);
        Renderer renderer = new Renderer(instance, surface, window,
                RendererOptions.builder().clearColor(0.10f, 0.12f, 0.16f).build());

        // 1. Raw-bytes overload: a generated 32x32 icon (a magenta/teal checker).
        window.setIcon(checker(32), 32, 32);

        // 2. Resource overload: decode + set a PNG from the classpath (last set wins,
        //    so this is what you'll actually SEE -- both paths are exercised though).
        window.setIcon("/demo/test-image.png");

        System.out.println("icon demo: window icon set from raw bytes, then from a PNG resource.");
        int frames = 0;
        while (!window.shouldClose() && frames++ < cap) {
            window.pollEvents();
            renderer.drawFrame();
        }
        renderer.waitIdle();

        renderer.close();
        surface.close();
        instance.close();
        window.close();
        System.out.println("icon demo done (" + frames + " frames).");
    }

    /** A simple size x size RGBA checkerboard (so the generated-icon path has
     *  something recognizable, not a flat color). */
    private static byte[] checker(int size) {
        byte[] px = new byte[size * size * 4];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean on = ((x / 4) + (y / 4)) % 2 == 0;
                int i = (y * size + x) * 4;
                px[i]     = (byte) (on ? 230 : 20);    // R
                px[i + 1] = (byte) (on ? 30 : 180);    // G
                px[i + 2] = (byte) (on ? 200 : 170);   // B
                px[i + 3] = (byte) 255;                // A
            }
        }
        return px;
    }
}
