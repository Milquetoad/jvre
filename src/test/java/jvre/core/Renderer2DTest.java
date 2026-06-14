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
        // A flat shape carries mode 0 (offset 13) -- the fragment shader's "full
        // coverage, flat color" path.
        assertEquals(0f, v[13]);
    }

    @Test
    void aFlatOnlyFrameIsOneNullTextureRun() {
        // No image() -> a single draw run starting at vertex 0 with no texture
        // (the Renderer binds the white default for it). The image() run-splitting
        // is GPU-verified (it needs real Texture objects).
        Renderer2D g = new Renderer2D();
        g.begin();
        g.fillRect(0, 0, 10, 10, Color.WHITE);
        g.fillCircle(50, 50, 5, Color.RED);
        g.end();
        assertEquals(1, g.runCount());
        assertEquals(0, g.runFirstVertex(0));
        assertNull(g.runTexture(0));
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
    void fillCircleIsAnSdfQuad() {
        Renderer2D g = new Renderer2D();
        g.begin();
        g.fillCircle(200, 200, 50, Color.GREEN);
        g.end();

        // One bounding quad = 2 triangles = 6 vertices, regardless of radius.
        assertEquals(6, g.vertexCount());

        float[] v = g.vertexData();
        int s = Renderer2D.FLOATS_PER_VERTEX;
        for (int i = 0; i < g.vertexCount(); i++) {
            // A circle is the square rounded box: half = (r, r) (offsets 8, 9) and
            // cornerRadius = r (offset 10) -> the distance-field path is active.
            assertEquals(50f, v[i * s + 8], 1e-4f);
            assertEquals(50f, v[i * s + 9], 1e-4f);
            assertEquals(50f, v[i * s + 10], 1e-4f);
            // The local coord (offsets 6, 7) is the corner's pixel offset from the
            // centre -- so position - centre == local.
            assertEquals(v[i * s] - 200f, v[i * s + 6], 1e-4f);
            assertEquals(v[i * s + 1] - 200f, v[i * s + 7], 1e-4f);
            // An SDF shape carries mode 1 (offset 13) -- the rounded-box path.
            assertEquals(1f, v[i * s + 13], 1e-4f);
        }
    }

    @Test
    void fillRoundedRectIsAnSdfBox() {
        Renderer2D g = new Renderer2D();
        g.begin();
        g.fillRoundedRect(100, 100, 200, 80, 15, Color.WHITE);
        g.end();
        assertEquals(6, g.vertexCount());   // a bounding quad

        float[] v = g.vertexData();
        int s = Renderer2D.FLOATS_PER_VERTEX;
        for (int i = 0; i < g.vertexCount(); i++) {
            assertEquals(100f, v[i * s + 8], 1e-4f);   // half-extent x = w/2
            assertEquals(40f, v[i * s + 9], 1e-4f);    // half-extent y = h/2
            assertEquals(15f, v[i * s + 10], 1e-4f);   // corner radius
        }
    }

    @Test
    void fillRoundedRectClampsRadiusToHalfTheShortSide() {
        Renderer2D g = new Renderer2D();
        g.begin();
        // A huge radius on an 80px-tall rect clamps to 40 (= a stadium end).
        g.fillRoundedRect(0, 0, 200, 80, 999, Color.WHITE);
        g.end();
        float[] v = g.vertexData();
        int s = Renderer2D.FLOATS_PER_VERTEX;
        assertEquals(40f, v[10], 1e-4f);   // clamped to min(hx, hy) = 40
    }

    @Test
    void fillRoundedRectRejectsNegatives() {
        Renderer2D g = new Renderer2D();
        g.begin();
        assertThrows(IllegalArgumentException.class,
                () -> g.fillRoundedRect(0, 0, -5, 10, 2, Color.WHITE));
        assertThrows(IllegalArgumentException.class,
                () -> g.fillRoundedRect(0, 0, 10, 10, -2, Color.WHITE));
    }

    @Test
    void biggerEllipsesGetMoreSegments() {
        // Ellipses are still tessellated fans (no closed-form SDF), so they still
        // scale their segment count with size.
        Renderer2D g = new Renderer2D();
        g.begin();
        g.fillEllipse(0, 0, 10, 10, Color.WHITE);
        int small = g.vertexCount();
        g.fillEllipse(0, 0, 500, 500, Color.WHITE);
        int both = g.vertexCount();
        g.end();
        assertTrue((both - small) > small, "a 500px ellipse needs more segments than a 10px one");
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
    void strokeRectIsAnEightTriangleFrame() {
        Renderer2D g = new Renderer2D();
        g.begin();
        g.strokeRect(100, 100, 200, 100, 10, Color.WHITE);
        g.end();
        // Four edge bands x 2 triangles = 24 vertices.
        assertEquals(24, g.vertexCount());

        // The frame's outer bounds = the rect inflated by half the thickness.
        float[] v = g.vertexData();
        int stride = Renderer2D.FLOATS_PER_VERTEX;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < g.vertexCount(); i++) {
            minX = Math.min(minX, v[i * stride]);
            maxX = Math.max(maxX, v[i * stride]);
            minY = Math.min(minY, v[i * stride + 1]);
            maxY = Math.max(maxY, v[i * stride + 1]);
        }
        assertEquals(95f, minX, 1e-4f);    // 100 - 10/2
        assertEquals(305f, maxX, 1e-4f);   // 100 + 200 + 10/2
        assertEquals(95f, minY, 1e-4f);
        assertEquals(205f, maxY, 1e-4f);
    }

    @Test
    void thickStrokeRectDropsTheDegenerateMiddleBands() {
        Renderer2D g = new Renderer2D();
        g.begin();
        // thickness > height: the left/right bands would have <=0 height, skipped.
        g.strokeRect(0, 0, 100, 8, 20, Color.WHITE);
        g.end();
        assertEquals(12, g.vertexCount());   // only top + bottom bands
    }

    @Test
    void strokeCircleIsARingBetweenTwoRadii() {
        Renderer2D g = new Renderer2D();
        g.begin();
        g.strokeCircle(0, 0, 50, 10, Color.WHITE);   // ring from radius 45 to 55
        g.end();

        int verts = g.vertexCount();
        assertTrue(verts % 6 == 0, "each ring slice is a quad = 6 verts: " + verts);

        float[] v = g.vertexData();
        int stride = Renderer2D.FLOATS_PER_VERTEX;
        float minD = Float.MAX_VALUE, maxD = -Float.MAX_VALUE;
        for (int i = 0; i < verts; i++) {
            double d = Math.hypot(v[i * stride], v[i * stride + 1]);
            minD = (float) Math.min(minD, d);
            maxD = (float) Math.max(maxD, d);
        }
        assertEquals(45f, minD, 1e-3f, "inner rim = r - thickness/2");
        assertEquals(55f, maxD, 1e-3f, "outer rim = r + thickness/2");
    }

    @Test
    void strokeCircleEqualsEqualRadiiStrokeEllipse() {
        Renderer2D a = new Renderer2D();
        a.begin();
        a.strokeCircle(0, 0, 40, 6, Color.WHITE);
        a.end();
        Renderer2D b = new Renderer2D();
        b.begin();
        b.strokeEllipse(0, 0, 40, 40, 6, Color.WHITE);
        b.end();
        assertEquals(b.vertexCount(), a.vertexCount());
    }

    @Test
    void lineIsAQuadAlongTheNormal() {
        Renderer2D g = new Renderer2D();
        g.begin();
        // Horizontal line, thickness 4 -> a 10 x 4 quad spanning y in [-2, 2].
        g.line(0, 0, 10, 0, 4, Color.BLACK);
        g.end();
        assertEquals(6, g.vertexCount());   // a quad = two triangles

        float[] v = g.vertexData();
        int stride = Renderer2D.FLOATS_PER_VERTEX;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < g.vertexCount(); i++) {
            minX = Math.min(minX, v[i * stride]);
            maxX = Math.max(maxX, v[i * stride]);
            minY = Math.min(minY, v[i * stride + 1]);
            maxY = Math.max(maxY, v[i * stride + 1]);
        }
        assertEquals(0f, minX, 1e-4f);
        assertEquals(10f, maxX, 1e-4f);
        assertEquals(-2f, minY, 1e-4f);   // +/- half the thickness about the line
        assertEquals(2f, maxY, 1e-4f);
    }

    @Test
    void zeroLengthLineDrawsNothing() {
        Renderer2D g = new Renderer2D();
        g.begin();
        g.line(5, 5, 5, 5, 3, Color.BLACK);
        g.end();
        assertEquals(0, g.vertexCount());
    }

    @Test
    void negativeThicknessIsRejected() {
        Renderer2D g = new Renderer2D();
        g.begin();
        assertThrows(IllegalArgumentException.class,
                () -> g.line(0, 0, 10, 10, -1, Color.BLACK));
    }

    @Test
    void strokeTriangleIsOneBandPerEdge() {
        Renderer2D g = new Renderer2D();
        g.begin();
        g.strokeTriangle(0, 0, 100, 0, 50, 80, 6, Color.WHITE);
        g.end();
        assertEquals(3 * 6, g.vertexCount());   // 3 edges x a 2-triangle band
    }

    @Test
    void strokeQuadOfASquareMitersToAnInflatedFrame() {
        Renderer2D g = new Renderer2D();
        g.begin();
        // A 100x100 square outline, thickness 10. Miter joins push the corners
        // out by half-thickness on each axis -> outer bounds inflate by 5.
        g.strokeQuad(0, 0, 100, 0, 100, 100, 0, 100, 10, Color.WHITE);
        g.end();
        assertEquals(4 * 6, g.vertexCount());

        float[] v = g.vertexData();
        int stride = Renderer2D.FLOATS_PER_VERTEX;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < g.vertexCount(); i++) {
            minX = Math.min(minX, v[i * stride]);
            maxX = Math.max(maxX, v[i * stride]);
            minY = Math.min(minY, v[i * stride + 1]);
            maxY = Math.max(maxY, v[i * stride + 1]);
        }
        assertEquals(-5f, minX, 1e-3f);
        assertEquals(105f, maxX, 1e-3f);
        assertEquals(-5f, minY, 1e-3f);
        assertEquals(105f, maxY, 1e-3f);
    }

    @Test
    void strokeTriangleRejectsNegativeThickness() {
        Renderer2D g = new Renderer2D();
        g.begin();
        assertThrows(IllegalArgumentException.class,
                () -> g.strokeTriangle(0, 0, 10, 0, 5, 8, -1, Color.WHITE));
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
