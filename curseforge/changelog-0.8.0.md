## 0.8.0 — first CurseForge release

**Added**

- Insert a point without reshaping the line: hold **Ctrl** and click on the route; the new point snaps onto the curve, so the alignment stays exactly where it was.
- Point names and six point types (plain, station, curve control point, tunnel portal, bridge, signal). Each type gets its own glyph on the map and in the 3D world; names are drawn next to the vertex on the map and beside the line in the world.
- Rename and recolour a route: double-click the route name on the panel. 12-swatch palette, hue bar, or raw RGB.

**Fixed**

- Points defaulting to Y = -64. The client only receives the `WORLD_SURFACE` and `MOTION_BLOCKING` heightmaps, and asking for the one it does not have returns the bottom of the world. Rail Planner now searches the top block itself, and existing saves with -64 anchors are repaired the next time the route is loaded.

**Internal**

- Save format v3 (point names), sync protocol v3 (names and type bits).

---

Earlier versions were published on GitHub only. What came before, briefly:

- **0.7.x** — sampled points carry a Y; double-click to edit it, and the vertical profile stays pinned. One switch to disable every hotkey. MCDR plugin for server-side auto-update.
- **0.6.x** — optional server-side sync per UUID with per-line conflict resolution; auto-download, sha256 check and in-place replacement; old saves migrate to the new world identity.
- **0.5.x** — tunnels and bridges as translucent shells, every sampled point drawn while editing, `L` to lock points, 3D toggles moved into the settings page.
- **0.4.x** — the 3D line: design level, ground level, fill/cut pillars, `V` and `X`.
- **0.3.x** — collapsible panel (`H`), over-limit values in red.
- **0.2.x** — remappable keys, settings page, update check.
