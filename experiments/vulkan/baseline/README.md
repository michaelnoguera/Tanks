# OpenGL baseline fixture

`run.sh` exercises the existing LWJGL window and shadow, main, and post-light
passes with fixed boxes, a translucent oval, and UI text. It runs eight
combinations of shadows, fancy lighting, and bitmap/TrueType text. The fixture
uses no random numbers or animation. It does not run the gameplay simulation,
`Panel.draw`, or extension callbacks. Draw orchestration follows `GameDrawer.draw`
without its exception handler so rendering failures terminate the fixture. Kata `jrtq` remains open for those scenarios.

The runner currently requires Linux, Bash, GNU coreutils, a JDK, a display, and
OpenGL. Build and run from the repository root:

```sh
./gradlew jar
experiments/vulkan/baseline/run.sh build/libs/Tanks-VERSION-HASH.jar experiments/vulkan/local-baseline
```

Replace the JAR filename with the file produced by Gradle. The output directory
must not exist. On Linux with Xvfb and Mesa installed:

```sh
xvfb-run -a env LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null \
  experiments/vulkan/baseline/run.sh build/libs/Tanks-VERSION-HASH.jar experiments/vulkan/local-baseline
```

Each scenario starts a separate JVM and stores its user files beneath its output
directory. No Steam connection is requested. TrueType uses the bundled Bullet
font without loading host fonts. The fixture disables vsync and requests a
640 × 480 window. PNG dimensions record the actual framebuffer size.

The root output contains the repository revision and SHA-256 hashes of the JAR
and fixture source. Each scenario contains:

| Artifact | Meaning |
| --- | --- |
| `environment.properties` | GL vendor, renderer, version, Java/OS identity, flags, screenshot semantics |
| `frames.csv` | 32 raw tick durations, JVM heap usage, cumulative update/draw/scene-pass callback counts |
| `back-30.png`, `back-31.png` | Rendered back buffers before swap, RGB with top-left origin |
| `frame-30.png`, `frame-31.png` | Front-buffer screenshots after swap; diagnostic evidence only |
| `verified-back.txt` | Written only after identical, nonuniform back-buffer images and expected callback counts |
| `front-status.txt` | Front-buffer repeat/nonuniform counts; screenshot semantics remain unverified |
| adjacent `.log` file | Startup, shader, native-library, and failure output |

`tick_ns` covers the window tick, including swap and event polling. Frames 30 and
31 also include back-buffer readback and PNG encoding; exclude those rows from
performance comparisons. Front-buffer capture and manifest I/O are outside the
measured interval. This measures CPU wall time, not GPU execution time.
Frame 0 includes lazy shader initialization. Frames 0–29 warm the fixture before
capture. This short run has no steady-state performance sample window; its
timings are diagnostic only. Performance qualification needs a longer run with
measurement frames after warm-up and before capture. Heap usage is
`totalMemory - freeMemory`, not process RSS or GPU memory. Do not use
software-renderer times as a hardware performance target.

The expected totals are 32 update callbacks, 32 draw callbacks, and 32 scene
callbacks without shadows or 64 with shadows. The fixture's update callback
only increments a counter. Its scene callback replaces `GameDrawer.drawSinglePass`;
its UI callback replaces `RenderPassUI.draw`. These counts verify pass scheduling
for this fixture, not extension or simulation behavior. The repeatability gate
requires zero changed back-buffer pixels within one process. Interior color checks
for both boxes and the oval reject text-only or missing-geometry results. Front-buffer images
are retained separately: Xvfb/Mesa may return uniform black even when back-buffer
rendering passes. Success does not establish presentation or screenshot behavior. Cross-process and cross-device
tolerances still require measurement.

## Source contract to preserve

| Source | Current behavior and port implication |
| --- | --- |
| `LWJGLWindow.tick` | Update, draw, swap, then poll events. A screenshot requested during update observes the previously presented front buffer. |
| `LWJGLWindow.screenshot` | Reads `GL_FRONT` synchronously; optional asynchronous work only encodes/writes the image. Drops alpha and flips Y. |
| `RenderPassGroupShadowDraw.draw` | Optional shadow pass followed by main draw. `currentPassNumber` starts at zero and becomes -1 after the group. |
| `GameDrawer.draw` | Main group, optional fancy-light pass, then UI. Fancy lights require drawing the main pass to an offscreen framebuffer. |
| `RenderPassDraw` | Main framebuffer has three color attachments and depth; shadow depth texture is sampled on unit 1. |
| `RenderPassPostLights` | Accumulates point lights in another framebuffer, then mixes main color, light, glow, and shadow textures. |
| `GameDrawer.drawSinglePass` | Calls each extension's `preDraw`, `Panel.draw`, then each extension's `draw` on every scene pass. |
| `GameUpdater.update` | Calls extension `preUpdate`, `Panel.update`, extension `update` once per window tick. |
| `Panel.draw` | Advances selected age counters, prunes track history, resets renderers on screen changes, updates screen age on the nonshadow pass, and may yield terrain-loading continuations. Draw is not side-effect free. |
| `Panel.drawUI` | Mutates notification ages/removal, clears tooltips, and draws mouse/debug overlays. |
| `ShaderBones.renderPosedVBO` | Changes `vbo`/`bonesEnabled`, original color, and attribute buffers around a posed draw. |
| `ImmediateModeShapeRenderer`, `LWJGLWindow` | Shape draws change blend, depth test/write, texture, and batch state; capture those choices in Vulkan draw records. |

For Vulkan, retain a completed presentation image (or a readback copy associated
with that presentation) for screenshots requested during update. Select the last
successfully presented frame, wait for its GPU work, copy to host-visible memory,
and preserve RGB/top-left output. Do not silently capture a newly recorded frame.
The fixture stores both a back-buffer image before swap and a front-buffer
screenshot after swap. A separate input-triggered screenshot test must cover
update timing and verify which presented image is returned.

## Shader inventory

Regenerate the setup-site inventory with:

```sh
uv run experiments/vulkan/baseline/inventory.py > experiments/vulkan/baseline/shader-setups.csv
```

The script excludes Java comments, preserves source line numbers, and verifies
that every literal shader resource it reports exists. `setup_expression` retains
the stage filenames and ordered header arrays. `ShaderUtil.createShader` prepends
header text to the stage body. Compile that composed text when translating shaders.
The CSV lists literal setup sites, not runtime reachability: inherited initializers,
dynamic paths, extensions, and option-dependent selection need runtime tracing.
Commented legacy `ShaderBones` setup calls are excluded. Posed-model coverage must
verify the active group actually selects bone-capable stages before accepting it.

## Remaining V01 acceptance work

V01 is the OpenGL rendering baseline task, Kata `jrtq`, in the
[Vulkan port plan](../../../docs/vulkan-port-plan.md).

| Scenario | Required evidence |
| --- | --- |
| Gameplay graphics matrix | Fixed level with shadows/fancy lights on and off; simulation/extension callback counts |
| Cameras and resizing | Orthographic, angled, following camera; framebuffer scale, resize, minimize, restore |
| Terrain and tracks | Fixed terrain edits, obstacle variants, track creation/aging and loading continuation |
| Models | Textured static and posed/skinned models with recorded poses and active shader stages |
| Effects and UI | Seeded explosions/fireworks, translucent overlap, glow, menus/chat/tooltips, both font modes |
| Screenshot input | Known alternating frame IDs and screenshot request during update proving previous presentation |
| Repeatability | Repeated process runs, frozen simulation timestep/seeds, measured pixel error and timing distributions |
| Native hardware | Linux/Windows drivers and macOS baseline where supported; device/driver manifest and raw results |

Record the level/pose/seed, input sequence, fixed timestep, option file, fixture and
binary hashes with every added scenario. Compare repeated OpenGL runs before
choosing image tolerances for Vulkan. Preserve failures and logs alongside results;
a missing `verified-back.txt` is a failed or incomplete renderer run. The runner
stops after the first failure and limits each scenario to 120 seconds.
