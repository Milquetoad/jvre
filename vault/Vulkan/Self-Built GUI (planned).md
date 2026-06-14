# Self-Built GUI (planned)

> **Revised scope (2026-06-14): a GUI is a CAPABILITY jvre enables, not a FEATURE it ships.** Re-examined once L2 v1 was complete (and against the [[Design North Star]]: jvre is a *rendering framework* between Processing and raw Vulkan -- and Processing, its upper reference, isn't a GUI toolkit either; people reach for the ControlP5 *add-on*). A full GUI is off jvre's core axis (its remaining learning payoff is application-layer UI logic, not the graphics/native core) and a classic scope sink. So the only sanctioned form is a **deliberately tiny immediate-mode demo built ON L2** -- a worked example + L2 stress test, NOT a jvre pillar (see [[Roadmap]] phase 3b). If a real GUI is ever needed for tooling, drop in [[GUI Options|Dear ImGui / Nuklear]]. The original 2026-06-09 design below stands as the blueprint for that bounded demo.

**Decision (2026-06-09):** jvre will ship its own **immediate-mode GUI**, built by us (not [[GUI Options|ImGui/Nuklear]]). Fits the learning goal and the algorithm-visualization use case. Scoped + sequenced so it's *not* scope creep.

## Why immediate-mode (not retained-mode)
- **Retained** (Swing/JavaFX/DOM): construct widget objects in a tree, callbacks, layout/invalidation passes. Heavy.
- **Immediate**: no widget objects — call the widget each frame, it acts now:
  ```java
  gui.label("Sort speed");
  if (gui.slider("speed", speed, 0, 100)) sorter.setSpeed(speed);
  if (gui.button("Step"))                 sorter.step();
  ```
  GUI rebuilt every frame (cheap on a GPU already redrawing). Same paradigm as Dear ImGui/Nuklear. Core is a few hundred lines.

## Prerequisite chain (why it comes AFTER 2D + text)
```
2D shapes (rects, lines)
   + text rendering (font atlas, glyphs)   ← biggest sub-project; GUIs are ~80% text
   + input snapshot (mouse/keys from GLFW)
   → IMGUI core (layout cursor + hot/active IDs)
   → widgets
```
Slots into the roadmap **after the 2D layer**, not beside the current Vulkan bootstrap.

## The two real work items
1. **Text rendering** — rasterize a `.ttf` to a glyph atlas, draw glyph quads. Pragmatic call: use LWJGL's bundled **`stb_truetype`** for raw glyph bitmaps; build atlas/layout/batching/GUI ourselves. (Writing a TTF rasterizer from scratch = off-path rabbit hole. Revisit if we want to go deeper.)
2. **Hot/active ID system** — the one clever IMGUI bit: track which widget is *hot* (hovered) and *active* (being interacted with) across frames. Tiny state, makes click/drag feel right.

## MVP widget set
label, button, checkbox, slider, text input, collapsible panel.

See [[Roadmap - Clear to Color]] (this is a later milestone) and [[GUI Options]] (the off-the-shelf alternatives we chose not to use).

#vulkan #gui #design #future
