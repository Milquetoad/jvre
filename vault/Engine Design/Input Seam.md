# Input Seam

**Shipped 2026-06-14 (mouse + keyboard + text).** The seam that turns L2 from *draw-only* into *interactive* -- [[Roadmap]] Phase 1a. Before this, the only input on the public surface was `Window.cursorPos` (raw LWJGL buffers, window coords). Now: `Input`, owned by `Window`, read via `window.input()`.

## The shape
```java
Input in = window.input();
in.mouseX(); in.mouseY();                 // framebuffer pixels (L2's space)
in.keyDown(Key.W);                        // key held / went down / went up
in.keyPressed(Key.BACKSPACE);
String text = in.typedChars();            // characters typed this frame (text fields)
in.mouseDown(MouseButton.LEFT);           // held now (LEVEL)
in.mousePressed(MouseButton.LEFT);        // went down THIS frame (EDGE)
in.mouseReleased(MouseButton.LEFT);       // went up THIS frame (EDGE)
in.scrollX(); in.scrollY();               // accumulated this frame (delta)
```

## Decisions

**Position in framebuffer pixels.** L2 draws in framebuffer pixels (top-left origin); GLFW reports the cursor in *window* coordinates, which differ on high-DPI displays. We convert by `framebufferSize / windowSize`, so `input.mouseX()` lines up exactly with `g.fillRect(...)`. (Identical on a 1:1 display; correct on scaled ones -- this closes the DPI caveat the old `cursorPos` left open.)

**Level vs edge -- both, because a click is an edge.** Immediate-mode interaction needs more than "is it held": a click is the *down-edge*, a release is the *up-edge*. So we expose `mouseDown` (level) AND `mousePressed`/`mouseReleased` (edges). This is exactly the state a button widget reads -- and the foundation the hot/active-ID idea would sit on if the [[Self-Built GUI (planned)|GUI demo]] is built.

**Callbacks, not polling -- so fast taps survive.** Edges are latched in the GLFW mouse-button callback (`PRESS` sets `held` + `pressed`; `RELEASE` clears `held`, sets `released`), then cleared at `newFrame`. Polling `glfwGetMouseButton` once per frame would *miss* a press+release that both happen within one frame (a quick tap); the callback catches it. Scroll likewise accumulates in its callback (there is no "current scroll" to poll -- it only exists as events).

**Zero ceremony.** `Window.pollEvents()` brackets `glfwPollEvents()`: `input.newFrame()` (clear this frame's edges + scroll) BEFORE dispatch so the callbacks fill fresh state, then `input.snapshot()` (capture cursor -> framebuffer px) AFTER, so the position getters are plain field reads. The user just calls `pollEvents()` as before.

**`MouseButton` enum, not raw ints.** The API speaks `MouseButton.LEFT`, never the GLFW integer -- consistent with [[Design North Star]] rule that the surface stays in the user's vocabulary.

## Not unit-tested -- hardware-verified
`Input` is GLFW-coupled (native callbacks + cursor queries), so per the [[Definition of Done]] it is verified on hardware (a cursor-tracking demo box: follows the pointer, reddens on left-button, resizes on scroll), not mocked into a unit test -- the same call as [[Windowing - GLFW and the Surface|Window]].

## Keyboard + typed text (beat 2, shipped)
- **Key state** mirrors the mouse: `keyDown` (level) + `keyPressed`/`keyReleased` (edges), via `GLFWKeyCallback`, addressed by a `Key` enum (letters, digits, named/navigation keys, modifiers, F1-F12 -- no raw GLFW codes leak).
- **Typed text is SEPARATE** -- `typedChars()` returns this frame's text from `GLFWCharCallback`, which already applies layout + shift and auto-repeats held character keys. That's the right source for a text field; raw key codes are not (they don't know `a` vs `A`, or symbols). Editing keys (Backspace/arrows) come from `keyPressed`.
- **Known v1 limit:** non-character keys don't auto-repeat (only the char stream does), so a held Backspace deletes once -- a catalogued later refinement.

## Next
- Pairs with **1b** (time/delta) -- interactive, animated L2 programs are now first-class. Phase 1 complete.
- Possible later: gamepad, raw key-repeat, IME/clipboard -- only if a consumer needs them.

#design #input #L2
