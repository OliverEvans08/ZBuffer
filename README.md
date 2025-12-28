# ZBuffer

A **software-rendered 3D engine in pure Java**. It draws triangles directly into a pixel buffer using a Z-buffer (depth buffer), runs a fixed-timestep loop, supports first/third-person cameras with optional flight, basic AABB collisions, a keyframe animation system, a minimal click GUI, and simple sound playback.

---

## Features

- **Software rasterizer with Z-buffer**
  - Perspective projection, near/far clipping
  - Triangle rasterization to an ARGB framebuffer
  - Simple face shading based on triangle orientation
- **Camera**
  - First-person and third-person modes (shoulder offset & follow distance)
  - Optional **Flight mode** (free vertical movement)
  - Gravity & jump when not in flight
- **Movement & collisions**
  - WASD + mouse-look
  - AABB collision against objects flagged as “full”
- **Keyframe Animation** (`engine.animation`)
  - Channels for position/rotation/scale, loop or one-shot
  - Absolute or relative application per target
- **Fixed timestep game loop**
  - Deterministic updates via `engine.core.GameClock` (default 60 Hz)
  - Lightweight in-process `engine.event.EventBus`
- **In-game Click GUI**
  - Toggle Debug overlay
  - Sliders for **FOV (30–120°, default 70°)** and **Render Distance (50–500, default 200)**
- **Sound**
  - Loads `.wav` files from `src/main/java/sound/wavs`
  - Used for jump SFX (`jump.wav`)

**Built-in objects**
- `objects.fixed.Cube` – unit cube (scaled/placed via transform)
- `objects.fixed.GameCube` – falling/rotating cubes that respawn
- `objects.dynamic.Body` – player body AABB (hidden in first-person)

---

## Controls

| Action                              | Key / Mouse        |
|-------------------------------------|--------------------|
| Move forward / back / left / right  | **W / S / A / D**  |
| Jump (ground) / Ascend (flight)     | **Space**          |
| Descend (flight)                    | **Shift**          |
| Mouse look                          | **Move mouse**     |
| Toggle Flight mode                  | **G**              |
| Toggle First/Third person           | **V**              |
| Open/Close Click GUI                | **P**              |

> When the GUI is open the mouse cursor is visible and interactive. When closed, the cursor is hidden and re-centered (via `java.awt.Robot`) for mouse-look.

---

## Requirements

- **JDK 17** recommended (**minimum JDK 14**) — uses modern `switch` syntax.
- Desktop Java runtime (AWT/Swing & `javax.sound.sampled`).
- Windows/macOS/Linux.  
  - On macOS you may need to allow input control for AWT `Robot` (System Settings → Privacy & Security → Accessibility).

---

## Getting Started

### 1) Clone
```bash
git clone <your-repo-url> ZBuffer
cd ZBuffer
