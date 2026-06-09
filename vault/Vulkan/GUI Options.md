# GUI Options (in a Vulkan app)

[[Windowing - GLFW and the Surface|GLFW]] gives **no widgets**. So how do you get a GUI?

## Key idea: GUI is rendered through your own pipeline
In a GPU app, a GUI is just more geometry — buttons = colored rects + text. There's no separate "GUI window system"; the GUI lives *inside* your rendered frame. The standard approach is **immediate-mode GUI**: each frame you call e.g. `if (gui.button("Play")) {...}`, and the library returns **vertices + draw commands** that *you* feed into your renderer. So GUI libs are **clients of your renderer**, not a parallel stack.

## Options for Java + Vulkan
| Library | What | Java access |
|---|---|---|
| **Dear ImGui** | Industry-standard debug/tool UI (the panels you see in game engines) | **imgui-java** (3rd-party JNI bindings, GLFW + Vulkan backends) |
| **Nuklear** | Lightweight immediate-mode GUI, pure C | **bundled in LWJGL** (`lwjgl-nuklear`) — lowest friction for us |

> **Decision:** we're rolling our **own** immediate-mode GUI instead of using these — see [[Self-Built GUI (planned)]]. (ImGui/Nuklear kept here as reference + fallback.)

## Relevance to jvre
- Our future **2D layer** (textured quads, lines, text) is exactly what a GUI needs → once we can draw 2D + text, we can render simple GUI ourselves (sliders/buttons/labels for algorithm visualizations).
- For *developer* tooling (tweak the engine live), integrating **Dear ImGui** as a debug overlay is the conventional milestone.

## Not used: Swing / JavaFX
Java's own toolkits are separate **retained-mode** stacks with their own windowing/rendering — they don't compose with a Vulkan-rendered GLFW window.

#vulkan #concept #gui #future
