# ZBuffer

A **software-rendered 3D engine written in pure Java**. ZBuffer draws triangles directly into an ARGB framebuffer, resolves visibility with a depth buffer, and presents the result through AWT/Swing—without OpenGL, Vulkan, or an external game engine.

The project includes a fixed-timestep update loop, first- and third-person cameras, player movement and collision, OBJ mesh loading, textured materials, multiple light types with shadows, keyframe animation, spatial indexing, an inventory/hotbar system, a click GUI, and WAV sound playback.

---

## Highlights

### Software rendering

- Perspective projection and near-plane clipping
- Triangle rasterization into an ARGB framebuffer
- Per-pixel depth testing with a Z-buffer
- Tiled triangle binning and parallel render workers
- Adjustable internal render scale from **25% to 100%**
- Textured and solid-colour materials
- UV wrapping and procedural textures
- Sky-gradient rendering
- Debug line drawing and overlay support
- Optional FXAA setting in the render configuration

### Lighting and shadows

- Directional, point, and spot lights
- Ambient and diffuse material controls
- Emissive materials, including optional point-light emission
- Shadow tests against scene geometry
- Configurable light colour, strength, range, attenuation, and spotlight angles

### Cameras and movement

- First-person and third-person camera modes
- Third-person follow distance, shoulder offset, and obstruction handling
- Mouse look
- Optional flight mode with vertical movement
- Gravity, jumping, grounded movement, and head bob
- Visible animated player body in third-person mode

### Collision and spatial queries

- Player capsule collision against world geometry
- AABB broad-phase collision queries
- Triangle-level collision and raycasting
- Mesh BVHs for accelerated collision queries
- Separate spatial hash indexes for solid colliders and visible renderables
- Horizontal and vertical collision resolution with floor-contact caching

### Assets and meshes

- Recursive loading of `.obj` files from `assets/models`
- OBJ parsing, index resolution, triangulation, deduplication, and normal generation
- Immutable mesh snapshots published atomically by `AssetManager`
- Parallel mesh loading when multiple processors are available
- Binary mesh reader, writer, validator, and compiler utilities
- Mesh IDs derived from paths relative to `assets/models`

For example, `assets/models/environment/tree.obj` is exposed as:

```text
environment/tree
```

A filename-only alias such as `tree` is also available when that filename is unique.

### Animation

The `engine.animation` package provides a lightweight keyframe system:

- Position, rotation, and scale channels
- Linear interpolation between keyframes
- Looping and one-shot clips
- Adjustable playback speed and time
- Absolute and relative application modes
- Automatic traversal of active objects in the scene graph

### Game loop and scene architecture

- Fixed updates at **60 Hz** through `engine.core.GameClock`
- Catch-up limiting to avoid an uncontrolled update spiral
- PRE and POST tick events
- Lightweight in-process `engine.event.EventBus`
- Two-phase scene updates
- Queued root-object mutations
- Published transform snapshots for rendering
- Worker pools for update and rendering tasks
- Graceful shutdown of the loop, renderer, sound system, input handler, and worker threads

### Inventory and interaction

- Nine-slot hotbar plus inventory storage
- Click-and-drag inventory UI
- World item drops with simple physics
- Look-at pickup targeting
- Pickup, drop, select, and use actions
- Held-item model rendered from the player hand
- Built-in blue and red cube item definitions

### In-game click GUI

Press **P** to open the settings panel. It includes:

- Debug overlay toggle
- **FOV:** 30–120°; default 70°
- **Render Distance:** 50–500; default 200
- **Render Scale:** 25–100%; default 50%

The panel can be dragged by its header.

### Sound

- WAV playback through `javax.sound.sampled`
- Sounds loaded from `src/main/java/sound/wavs`
- Master-volume control
- Jump sound effect support through `jump.wav`

---

## Controls

| Action | Key / Mouse |
|---|---|
| Move forward / backward | **W / S** |
| Strafe left / right | **A / D** |
| Jump / ascend in flight mode | **Space** |
| Descend in flight mode | **Shift** |
| Look around | **Move mouse** |
| Toggle flight mode | **G** |
| Toggle first-/third-person view | **V** |
| Open or close inventory | **E** |
| Select hotbar slot | **1–9** |
| Pick up targeted item | **F** |
| Drop selected item | **Q** |
| Use held item | **Left mouse button** |
| Open or close click GUI | **P** |

When the click GUI or inventory is open, movement input is suppressed and the mouse cursor is visible. When both interfaces are closed, the engine hides and recentres the cursor with `java.awt.Robot` for mouse look.

---

## Requirements

- **JDK 17 recommended**
- **JDK 16 minimum** because the source uses records and pattern matching for `instanceof`
- A desktop Java runtime with:
  - AWT/Swing
  - `javax.sound.sampled`
- Windows, macOS, or Linux with a graphical desktop environment

On macOS, mouse recentering may require permission under:

```text
System Settings → Privacy & Security → Accessibility
```

If `java.awt.Robot` cannot be created, the engine logs a warning and disables pointer recentering rather than terminating.

---

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/OliverEvans08/ZBuffer ZBuffer
cd ZBuffer
```

### 2. Check the asset directories

The engine expects these paths relative to the project root:

```text
assets/models/                 # OBJ models
src/main/java/sound/wavs/      # WAV sound effects
```

The model directory is optional. If it is missing, the engine logs a warning and continues with an empty mesh collection.

### 3. Compile

No build-tool configuration was present in the supplied source snapshot, so the project can be compiled directly with `javac`.

#### macOS / Linux

```bash
mkdir -p out
find src/main/java -name "*.java" -print0 | xargs -0 javac -d out
```

#### Windows PowerShell

```powershell
New-Item -ItemType Directory -Force out | Out-Null
$Sources = Get-ChildItem -Recurse src/main/java -Filter *.java | ForEach-Object FullName
javac -d out $Sources
```

### 4. Run

Run from the repository root so relative asset and sound paths resolve correctly:

```bash
java -cp out engine.GameApplication
```

The default Swing window is created at **1920 × 1080** and is titled `GameEngine`.

---

## Project Structure

```text
assets/models/                   OBJ model assets
src/main/java/
├── engine/
│   ├── animation/               Keyframe clips and animators
│   ├── assets/                  OBJ and binary mesh pipeline
│   ├── camera/                  First-/third-person camera system
│   ├── collision/               AABB, capsule, triangle, BVH, and ray queries
│   ├── core/                    Fixed-step clock
│   ├── event/                   Event bus and engine events
│   ├── inventory/               Hotbar, storage, items, and inventory UI
│   ├── lighting/                Light definitions and types
│   ├── loop/                    Game loop, timing, and update scheduling
│   ├── movement/                Player movement and collision resolution
│   ├── render/                  Renderer, materials, textures, and frame data
│   │   ├── pipeline/            Clipping, projection, mesh compilation
│   │   ├── raster/              Frame/depth buffers and tiled rasterization
│   │   ├── shading/             Lighting, materials, and shadows
│   │   └── worker/              Render-worker state and caches
│   ├── scene/                   Scene graph and mutation/update pipeline
│   ├── spatial/                 Spatial hash indexes and queries
│   ├── systems/                 Player controller
│   └── ui/                      Swing panel, window, cursor, and debug overlay
├── gui/                         Click GUI and controls
├── objects/                     Base, mesh, fixed, dynamic, and light objects
├── sound/                       Sound engine and WAV files
└── util/                        Maths, vectors, matrices, transforms, and AABB
```

---

## Built-in Objects

- `objects.fixed.Cube` — configurable cube primitive
- `objects.fixed.GameCube` — animated falling/rotating cube
- `objects.fixed.Ground` — textured ground object
- `objects.dynamic.Body` — articulated player body used in third-person mode
- `objects.MeshObject` — scene object backed by loaded mesh data
- `objects.LightObject` — scene object that owns a light

The dynamic body is hidden in first-person mode and published as part of the scene in third-person mode.

---

## Using OBJ Models

Place OBJ files anywhere below `assets/models` and restart or reload the asset manager:

```text
assets/models/props/crate.obj
assets/models/characters/robot.obj
```

Load them by relative ID:

```java
MeshData crate = engine.assetManager.getMesh("props/crate");
MeshData robot = engine.assetManager.getMesh("characters/robot");
```

When a base filename is unique, its short alias also works:

```java
MeshData crate = engine.assetManager.getMesh("crate");
```

Malformed OBJ files are logged and skipped, allowing other assets to continue loading.

---

## Minimal Animation Example

```java
import engine.animation.AnimationClip;
import engine.animation.Animator;
import objects.GameObject;

GameObject object = /* create or obtain an object */;

AnimationClip bob = AnimationClip.builder(1.0)
        .loop(true)
        .pos(0.0, 0.0, 0.0, 0.0)
        .pos(0.5, 0.0, 0.5, 0.0)
        .pos(1.0, 0.0, 0.0, 0.0)
        .build();

object.getAnimator()
        .setMode(Animator.Mode.RELATIVE)
        .play(bob);
```

Animation values are interpolated linearly. In relative mode, position and rotation are added to the captured base transform while scale values multiply it.

---

## Notes

- ZBuffer is CPU-rendered, so resolution, render scale, scene complexity, shadows, and processor count strongly affect performance.
- The default internal render scale is 50%, which reduces raster workload before the image is scaled to the Swing panel.
- The engine uses relative filesystem paths; launch it from the repository root unless you update the configured asset paths.
- FXAA is represented in the render settings, but the supplied `FxaaProcessor` is currently a placeholder.

---
