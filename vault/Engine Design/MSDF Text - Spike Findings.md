# MSDF Text — Spike Findings (2026-06-18)

**Status: spike COMPLETE → IMPLEMENTED the same day.** This resolved the catalogued "MSDF — STILL A SPIKE (L + a decision)" item from [[Roadmap|Batch 3]]; the recommended design then shipped (`Renderer.loadMsdfFont`, mode 6 in `shape2d.frag`, `Font.loadMsdf` via `lwjgl-msdfgen` at the unchanged 3.3.4). See the [[Progress Log]] entry for the build, including the two bugs (heap corruption from a position-advancing `ByteBuffer.get` before `stbtt_FreeSDF`; msdfgen's y-up bitmap needing a vertical flip). The headline below stands: **the spike's founding assumption was stale, and the conclusion flipped because of it.** The "open risk" (the Shape-construction API surface) was resolved by `javap` on the resolved jar + the `MsdfProbe` spike — the manual path is fully exposed, no FreeType needed.

## The question
jvre's L2 `text` renders [[l2-text-sdf-glyphs|single-channel SDF glyphs]] (one R8 atlas, `stbtt_GetCodepointSDF`, shader smoothstep over `fwidth`). Single-channel SDF has one known weakness: it **rounds off sharp corners** — a single distance field is the *median* of distances to all edges, so a true corner (where two edges meet at an angle) can't be represented; it smooths to an arc. Visible on glyph tips/serifs (`A M W v` apexes), and worst when a small-baked atlas is rendered **large** (you magnify a low-resolution field). MSDF (multi-channel SDF) fixes it: three distance fields in RGB, each tracking a differently-*coloured* subset of edges; the shader takes the **median of the three**, which reconstructs the exact corner intersection. The question was *how* to get MSDF into jvre, and whether it's worth it.

## The stale premise (now corrected)
The roadmap recorded: *"stb_truetype bakes only single-channel SDF; true MSDF needs an external generator (msdf-atlas-gen, offline) or hand-rolling, with no LWJGL binding → likely 'bundle pre-generated MSDF atlases,' not runtime baking."*

That was true when written. **It is no longer true.** As of this spike:

- **`org.lwjgl:lwjgl-msdfgen` EXISTS** — an LWJGL binding to Chlumský's `msdfgen` (the canonical MSDF generator). Package `org.lwjgl.util.msdfgen`: `MSDFGen` / `MSDFGenExt` entry points, `MSDFGenBitmap` (float output), `MSDFGenConfig` / `MSDFGenMultichannelConfig`, `MSDFGenTransform` (scale / translate / distance range), `MSDFGenBounds`, and an `MSDFGenFTLoadCallback` (FreeType glyph-load hook).
- It is a **standard LWJGL module** — same versioning, native libraries for Windows/Linux/macOS (x64 + arm64) — published from **3.3.4 through 3.4.1** (latest 3.4.1, Feb 2026). **jvre is on 3.3.4**, so the binding is reachable with **at most a tiny in-3.3.x bump** (3.3.4 → 3.3.6 to be safe), possibly with no bump at all.

So the "bundle offline atlases" plan is **off the table** — it was a workaround for a binding that didn't exist, and it carried real costs (an external toolchain in the build, a PNG+CSV/JSON layout to ship and parse — and jvre bundles **no JSON parser** — and the loss of jvre's runtime "bake any TTF" capability). None of that is necessary.

## Decision: runtime MSDF via `lwjgl-msdfgen`, OPT-IN, parallel to SDF
Add MSDF as a **second, opt-in glyph path** that mirrors the existing SDF path, leaving single-channel SDF as the default. Rationale:

- **Stay in the LWJGL family** jvre already uses ([[learning-scope-safety-for-tangential]]: lean on the proven library for the tangential mechanism — font-field generation — rather than hand-rolling msdfgen's edge-colouring + median algorithm, which is thousands of lines and genuinely error-prone). Hand-rolling is **rejected**.
- **Keep runtime baking.** The current `Font.load(ttf, pixelHeight)` bakes live; MSDF should too (`Font.loadMsdf(...)`). No offline step, no bundled atlases, no new third-party dependency, no JSON parser.
- **SDF stays the default** because it's lighter (R8 vs RGB, ~3× atlas memory) and already correct for the common case (UI-size text from the 48px bake). MSDF is the upgrade you choose when you render **large display text** and want crisp corners. Same ship-v1-refine-later shape as miter→bevel.

## Integration plan (sized; the implementation increment)
Mirrors the SDF path beat-for-beat — the existing machinery does most of the work:

1. **Dependency.** Add `implementation "org.lwjgl:lwjgl-msdfgen"` + the host-natives line in the `nativesRuntime` config. Decide the version bump (3.3.4→3.3.6 recommended; 3.4.1 is a larger, optional jump). *This touches the public POM of a 1.0 library — an owner decision, not a silent add.*
2. **Glyph outline source** — the one real open question (a small code spike against the actual 3.3.x API):
   - **Preferred:** extract the outline with `stb_truetype` (`stbtt_GetCodepointShape` returns the contour as move/line/quadratic-bezier vertices — already a dep) and feed it into an msdfgen `Shape` (contours + edges). No new font loader.
   - **Fallback:** use msdfgen's FreeType path (`MSDFGenExt` + `MSDFGenFTLoadCallback`), which means adding `lwjgl-freetype`. Robust, canonical, but a second font library alongside stb.
   - The javadoc surfaced the *structs* but not the Shape-building functions, so which path is ergonomic needs ~an hour against the real binding. **This is the only genuine implementation risk.**
3. **Generate per glyph:** `shape.normalize()` → `edgeColoringSimple()` (the ≥2-channels-per-edge colouring that makes corners work) → `generateMSDF()` with a `MSDFGenTransform` (scale to bake px, translate, distance **range** ≈ the SDF PADDING analogue). Output is a float bitmap → quantise to 8-bit RGB.
4. **Atlas:** reuse the existing **shelf packer** unchanged; pack RGB instead of R8. New `Texture.createMsdfAtlas` (RGB8 **UNORM** — *not* sRGB; MSDF channels are linear distances, like the alpha channel — LINEAR filtering, like the SDF atlas).
5. **Shader:** add one mode to `shape2d.frag` (a "mode 6 MSDF text" alongside the mode-3 SDF text). The canonical reconstruction:
   ```glsl
   float median(float r, float g, float b) { return max(min(r, g), min(max(r, g), b)); }
   // ...
   vec3 msd = texture(uTex, vUv).rgb;
   float sd  = median(msd.r, msd.g, msd.b);
   float screenPxDistance = screenPxRange() * (sd - 0.5);
   float coverage = clamp(screenPxDistance + 0.5, 0.0, 1.0);
   ```
   `screenPxRange()` is the distance-field range expressed in **screen** pixels. jvre already derives a screen-space gradient ramp via `fwidth` for its other SDF modes (`coverageFromSdf`), so the same `fwidth(sd)`-based width slots in naturally — no need to thread the precomputed range through a uniform.
6. **API:** `Renderer.loadFont(..., FontType.MSDF)` or a distinct `loadMsdfFont` — caller-owned `Font` exactly like today; `Renderer2D.text` selects the atlas + mode from the font.

## Verdict
**Do it — as a committed follow-up increment, runtime + opt-in, via `lwjgl-msdfgen`.** It's no longer an "L + a decision" research gamble; the binding's existence makes it a **medium**, well-understood feature that reuses jvre's atlas/packer/text machinery. The single open risk is the Shape-construction API surface (step 2), worth a one-hour code spike before committing the full beat. Per [[jvre-fully-fledged-not-pull-based]] it stays committed work, not "someday."

#design #L2 #text #msdf #spike
