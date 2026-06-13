package jvre.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The CPU half of Renderer2D: begin/end discipline and the vertex stream a
 * fillRect produces. No GPU -- this is exactly the logic the spec calls out as
 * the testable-everywhere half ("separate engine logic from raw rendering").
 */
class Renderer2DTest {

    @Test
    void fillRectEmitsTwoTriangles() {
        Renderer2D g = new Renderer2D();
        g.begin();
        g.fillRect(10, 20, 30, 40, Color.RED);
        g.end();
        // One rectangle = 2 triangles = 6 vertices.
        assertEquals(6, g.vertexCount());
        assertEquals(6 * Renderer2D.FLOATS_PER_VERTEX, g.floatCount());

        // First vertex is the top-left corner (x, y) carrying RED's linear rgba.
        float[] v = g.vertexData();
        assertEquals(10f, v[0]);
        assertEquals(20f, v[1]);
        assertEquals(Color.RED.r, v[2]);
        assertEquals(Color.RED.a, v[5]);
    }

    @Test
    void beginResetsThePreviousFrame() {
        Renderer2D g = new Renderer2D();
        g.begin();
        g.fillRect(0, 0, 1, 1, Color.WHITE);
        g.end();
        g.begin();   // a fresh frame starts empty
        assertEquals(0, g.vertexCount());
        g.fillRect(0, 0, 1, 1, Color.WHITE);
        g.fillRect(0, 0, 1, 1, Color.WHITE);
        g.end();
        assertEquals(12, g.vertexCount());
    }

    @Test
    void growsPastTheInitialCapacity() {
        Renderer2D g = new Renderer2D();
        g.begin();
        for (int i = 0; i < 500; i++) {   // well past the initial ~64-rect arena
            g.fillRect(i, i, 1, 1, Color.BLUE);
        }
        g.end();
        assertEquals(500 * 6, g.vertexCount());
    }

    @Test
    void drawingOutsideAFrameThrowsInL2Terms() {
        Renderer2D g = new Renderer2D();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> g.fillRect(0, 0, 1, 1, Color.WHITE));
        assertTrue(e.getMessage().contains("fillRect"), e.getMessage());
        assertFalse(e.getMessage().toLowerCase().contains("vulkan"), "errors speak L2, not Vulkan");
    }

    @Test
    void beginEndDisciplineIsEnforced() {
        Renderer2D g = new Renderer2D();
        assertThrows(IllegalStateException.class, g::end);   // end before begin
        g.begin();
        assertThrows(IllegalStateException.class, g::begin); // begin twice
    }

    @Test
    void negativeSizeIsRejected() {
        Renderer2D g = new Renderer2D();
        g.begin();
        assertThrows(IllegalArgumentException.class,
                () -> g.fillRect(0, 0, -5, 10, Color.WHITE));
    }

    @Test
    void fillCircleTessellatesIntoAFan() {
        Renderer2D g = new Renderer2D();
        g.begin();
        g.fillCircle(200, 200, 50, Color.GREEN);
        g.end();

        int verts = g.vertexCount();
        assertTrue(verts % 3 == 0, "a triangle list must be a multiple of 3: " + verts);
        assertTrue(verts >= 8 * 3, "even a small circle gets the minimum segment count");

        float[] v = g.vertexData();
        int stride = Renderer2D.FLOATS_PER_VERTEX;
        for (int i = 0; i < verts; i += 3) {
            // Every fan triangle starts at the centre...
            assertEquals(200f, v[i * stride], 1e-4f);
            assertEquals(200f, v[i * stride + 1], 1e-4f);
            // ...and all three corners lie within the radius (+ a hair).
            for (int t = 0; t < 3; t++) {
                float x = v[(i + t) * stride];
                float y = v[(i + t) * stride + 1];
                double d = Math.hypot(x - 200, y - 200);
                assertTrue(d <= 50 + 1e-3, "vertex outside the radius: " + d);
            }
        }
    }

    @Test
    void biggerCirclesGetMoreSegments() {
        Renderer2D g = new Renderer2D();
        g.begin();
        g.fillCircle(0, 0, 10, Color.WHITE);
        int small = g.vertexCount();
        g.fillCircle(0, 0, 500, Color.WHITE);
        int both = g.vertexCount();
        g.end();
        assertTrue((both - small) > small, "a 500px circle needs more segments than a 10px one");
    }

    @Test
    void negativeRadiusIsRejected() {
        Renderer2D g = new Renderer2D();
        g.begin();
        assertThrows(IllegalArgumentException.class,
                () -> g.fillCircle(0, 0, -1, Color.WHITE));
        assertThrows(IllegalArgumentException.class,
                () -> g.fillEllipse(0, 0, 10, -1, Color.WHITE));
    }

    @Test
    void ellipseRespectsBothRadii() {
        Renderer2D g = new Renderer2D();
        g.begin();
        g.fillEllipse(0, 0, 100, 20, Color.WHITE);
        g.end();
        float[] v = g.vertexData();
        int stride = Renderer2D.FLOATS_PER_VERTEX;
        float maxX = 0, maxY = 0;
        for (int i = 0; i < g.vertexCount(); i++) {
            maxX = Math.max(maxX, Math.abs(v[i * stride]));
            maxY = Math.max(maxY, Math.abs(v[i * stride + 1]));
        }
        // Tolerance covers the tessellation undershoot (sampled angles don't
        // land exactly on the axes) while still proving rx and ry are distinct.
        assertEquals(100f, maxX, 0.5f, "x extent = rx");
        assertEquals(20f, maxY, 0.5f, "y extent = ry");
    }

    @Test
    void circleIsTheEqualRadiiEllipse() {
        // fillCircle delegates to fillEllipse -- same vertex count for r == rx == ry.
        Renderer2D a = new Renderer2D();
        a.begin();
        a.fillCircle(0, 0, 60, Color.WHITE);
        a.end();
        Renderer2D b = new Renderer2D();
        b.begin();
        b.fillEllipse(0, 0, 60, 60, Color.WHITE);
        b.end();
        assertEquals(b.vertexCount(), a.vertexCount());
    }

    @Test
    void triangleIsThreeVertices() {
        Renderer2D g = new Renderer2D();
        g.begin();
        g.fillTriangle(0, 0, 10, 0, 5, 8, Color.RED);
        g.end();
        assertEquals(3, g.vertexCount());
        float[] v = g.vertexData();
        assertEquals(5f, v[2 * Renderer2D.FLOATS_PER_VERTEX]);     // third vertex x
        assertEquals(8f, v[2 * Renderer2D.FLOATS_PER_VERTEX + 1]); // third vertex y
    }

    @Test
    void quadSplitsOnTheZeroTwoDiagonal() {
        Renderer2D g = new Renderer2D();
        g.begin();
        g.fillQuad(0, 0, 10, 0, 10, 10, 0, 10, Color.BLUE);
        g.end();
        assertEquals(6, g.vertexCount());   // two triangles
        float[] v = g.vertexData();
        int s = Renderer2D.FLOATS_PER_VERTEX;
        // Triangle 2 starts at v0 (0,0) and continues from v2 (10,10) -- the 0-2 diagonal.
        assertEquals(0f, v[3 * s]);
        assertEquals(0f, v[3 * s + 1]);
        assertEquals(10f, v[4 * s]);
        assertEquals(10f, v[4 * s + 1]);
    }
}
