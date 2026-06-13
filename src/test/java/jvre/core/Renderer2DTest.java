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
}
