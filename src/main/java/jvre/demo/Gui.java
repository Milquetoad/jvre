package jvre.demo;

import jvre.core.Color;
import jvre.core.Input;
import jvre.core.MouseButton;
import jvre.core.Renderer2D;

/**
 * A deliberately tiny IMMEDIATE-MODE GUI, built entirely ON jvre's L2 surface
 * ({@link Renderer2D} for drawing + {@link Input} for the mouse). It is a WORKED
 * EXAMPLE and an L2 stress test -- NOT part of jvre's public API (hence
 * {@code jvre.demo}, excluded from the published jar). If a real GUI were ever
 * needed for tooling, the call is to drop in Dear ImGui / Nuklear, not to grow
 * this. See the roadmap's phase 3b and the "Self-Built GUI (planned)" vault note.
 *
 * <h2>Why immediate mode</h2>
 * There are no widget OBJECTS retained between frames. Each frame you CALL the
 * widget and it acts now:
 * <pre>{@code
 *   gui.begin(20, 20);
 *   gui.label("Controls");
 *   if (gui.button("Step")) sorter.step();
 *   speed = gui.slider("Speed", speed, 0, 100);
 *   gui.end();
 * }</pre>
 * The UI is rebuilt every frame (cheap -- the GPU is already redrawing). The same
 * paradigm as Dear ImGui / Nuklear.
 *
 * <h2>The one clever bit: hot + active IDs</h2>
 * "Was this button clicked?" needs memory across frames, because a click is
 * mouse-DOWN on a widget followed by mouse-UP on the SAME widget. So the GUI
 * keeps exactly two scraps of persistent state:
 * <ul>
 *   <li><b>{@link #hot}</b> -- the widget the cursor is over THIS frame
 *       (recomputed every frame; drives the hover highlight).</li>
 *   <li><b>{@link #active}</b> -- the widget the mouse pressed down on and is
 *       still holding (persists across frames until release). This is what makes
 *       dragging work: a slider stays active even when the cursor wanders off its
 *       track, so it keeps following the mouse.</li>
 * </ul>
 * A button fires when the mouse is released while the widget is both hot AND
 * active. Press on it, drag off, release -> no fire. That single rule is what
 * makes clicks and drags feel right.
 *
 * <p>Widgets are identified by their label STRING (immediate mode has no object
 * to point at). Two widgets with the same label therefore share an ID; append a
 * hidden suffix with {@code "##"} to disambiguate (e.g. {@code "Reset##left"} and
 * {@code "Reset##right"} both display "Reset"). The classic Dear ImGui idiom.
 */
public final class Gui {

    private final Renderer2D g;
    private final Input input;

    // The two persistent scraps of state. Null = none. The widget's full label
    // string is its ID (compared with equals); a fresh frame recomputes hot,
    // while active survives across frames until the mouse releases.
    private String hot;
    private String active;

    // Vertical layout cursor: where the NEXT widget's top-left goes. begin() sets
    // it; each widget advances it down by one row + gap.
    private float cursorX;
    private float cursorY;

    // Fixed metrics -- a tiny GUI doesn't need a layout engine.
    private static final float PANEL_W = 220f;   // every widget spans this width
    private static final float ROW_H   = 30f;    // one widget row
    private static final float GAP     = 8f;     // vertical space between rows
    private static final float TEXT    = 16f;    // label text size (px)
    private static final float PAD     = 10f;    // text inset from a widget's left

    // Palette (a calm dark theme). Distinct shades make hot/active visible.
    private static final Color FG        = Color.rgb(235, 235, 240);
    private static final Color BTN        = Color.rgb(58, 62, 78);
    private static final Color BTN_HOT    = Color.rgb(74, 80, 102);
    private static final Color BTN_ACTIVE = Color.rgb(96, 130, 200);
    private static final Color TRACK      = Color.rgb(40, 43, 54);
    private static final Color FILL       = Color.rgb(96, 130, 200);
    private static final Color KNOB       = Color.rgb(225, 228, 236);

    public Gui(Renderer2D g, Input input) {
        this.g = g;
        this.input = input;
    }

    /**
     * Start a panel whose first widget's top-left is at {@code (x, y)} in L2
     * pixels. Resets {@link #hot} for the new frame (it is recomputed as widgets
     * are visited); {@link #active} deliberately survives so an in-progress drag
     * continues. Call inside the Renderer2D begin()/end() bracket.
     */
    public void begin(float x, float y) {
        cursorX = x;
        cursorY = y;
        hot = null;
    }

    /** End the panel. A safety net: if the mouse came up anywhere (e.g. outside
     *  every widget), drop the active widget so nothing stays stuck "grabbed". */
    public void end() {
        if (input.mouseReleased(MouseButton.LEFT)) {
            active = null;
        }
    }

    /** A non-interactive line of text (a heading or caption). */
    public void label(String text) {
        float y = cursorY;
        advance();
        g.text(text, cursorX, textBaselineTop(y), TEXT, FG);
    }

    /**
     * A push button. Returns true on the frame the click COMPLETES (mouse released
     * while still over the button) -- the natural "if (button) do it" shape.
     */
    public boolean button(String label) {
        float x = cursorX, y = cursorY;
        advance();

        boolean over = hit(x, y, PANEL_W, ROW_H);
        if (over) {
            hot = label;
        }

        boolean clicked = false;
        if (label.equals(active)) {
            // We own the interaction. The click lands only if the release happens
            // while still over us (drag-off-then-release cancels).
            if (input.mouseReleased(MouseButton.LEFT)) {
                if (over) {
                    clicked = true;
                }
                active = null;
            }
        } else if (over && input.mousePressed(MouseButton.LEFT)) {
            active = label;   // grab it on press
        }

        Color fill = label.equals(active) && over ? BTN_ACTIVE
                   : label.equals(hot)            ? BTN_HOT
                   :                                 BTN;
        g.fillRoundedRect(x, y, PANEL_W, ROW_H, 6f, fill);
        drawCenteredLabel(display(label), x, y, PANEL_W, ROW_H);
        return clicked;
    }

    /**
     * A horizontal slider over {@code [min, max]}. Returns the (possibly updated)
     * value -- immediate-mode style: {@code v = gui.slider("Speed", v, 0, 100)}.
     * While active it tracks the mouse even off the bar, the point of the active ID.
     */
    public float slider(String label, float value, float min, float max) {
        float x = cursorX, y = cursorY;
        advance();

        boolean over = hit(x, y, PANEL_W, ROW_H);
        if (over) {
            hot = label;
        }

        if (label.equals(active)) {
            value = valueAt(input.mouseX(), x, min, max);   // follow the mouse
            if (input.mouseReleased(MouseButton.LEFT)) {
                active = null;
            }
        } else if (over && input.mousePressed(MouseButton.LEFT)) {
            active = label;
            value = valueAt(input.mouseX(), x, min, max);    // jump to the click
        }

        float t = (max > min) ? (value - min) / (max - min) : 0f;
        float midY = y + ROW_H * 0.5f;
        // A thin track, the filled-so-far portion, and the knob on top.
        g.fillRoundedRect(x, midY - 3f, PANEL_W, 6f, 3f, TRACK);
        g.fillRoundedRect(x, midY - 3f, PANEL_W * t, 6f, 3f, FILL);
        g.fillCircle(x + PANEL_W * t, midY, ROW_H * 0.32f,
                label.equals(hot) || label.equals(active) ? FILL : KNOB);
        // Label on the left, current value on the right -- both vertically centred.
        g.text(display(label), x + PAD, textBaselineTop(y), TEXT, FG);
        String num = String.format("%.1f", value);
        g.text(num, x + PANEL_W - PAD - g.textWidth(num, TEXT), textBaselineTop(y), TEXT, FG);
        return value;
    }

    // ---- internals ----------------------------------------------------------

    /** Map a mouse X to a value, clamped into [min, max] across the panel width. */
    private float valueAt(float mouseX, float x, float min, float max) {
        float t = clamp01((mouseX - x) / PANEL_W);
        return min + t * (max - min);
    }

    /** Move the layout cursor past the row just emitted. */
    private void advance() {
        cursorY += ROW_H + GAP;
    }

    /** Is the mouse inside this rectangle right now? */
    private boolean hit(float x, float y, float w, float h) {
        float mx = input.mouseX(), my = input.mouseY();
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /** Top Y to pass to text() so a single TEXT-tall line sits centred in a row. */
    private float textBaselineTop(float rowY) {
        return rowY + (ROW_H - TEXT) * 0.5f;
    }

    /** Draw a label horizontally + vertically centred in a widget rectangle. */
    private void drawCenteredLabel(String text, float x, float y, float w, float h) {
        float tw = g.textWidth(text, TEXT);
        g.text(text, x + (w - tw) * 0.5f, y + (h - TEXT) * 0.5f, TEXT, FG);
    }

    /** The visible part of a label: everything before a "##" disambiguation tag. */
    private static String display(String label) {
        int tag = label.indexOf("##");
        return tag < 0 ? label : label.substring(0, tag);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
