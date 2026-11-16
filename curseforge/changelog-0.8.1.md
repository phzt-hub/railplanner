## 0.8.1 — English interface

**Changed**

- The whole in-game interface is bilingual now (Chinese / English), picked from your game language: panel, settings page, world HUD, the point and route edit dialogs, and the sync / update status lines. English clients no longer get a wall of Chinese; Chinese clients look exactly as before.
- Mod metadata: the description shown in Mod Menu is Chinese and English, and the jar now carries the 128×128 icon and the project links.

**Fixed**

- Route-properties dialog: the panel is now sized from its title and hint instead of a fixed 330 px, so the longer English header no longer runs off the right edge.

**Unchanged**

- Save format and sync protocol are the same as 0.8.0 (protocol 3), so a 0.8.0 server and this client still talk to each other. This release only changes text and one panel width — no behaviour changes to the alignment maths or to the world rendering.
