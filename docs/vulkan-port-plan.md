# Vulkan port plan

Finish a playable desktop Vulkan renderer while keeping OpenGL available.
The normal Tanks launcher still uses OpenGL. `./gradlew runVulkan` starts a
standalone Vulkan scene with colored geometry and Tanks textures; it does not
run gameplay yet. See [Vulkan renderer development](vulkan-renderer.md) for
commands, implemented behavior, and current resource limits.

## Implemented

[VulkanWindow](../src/main/java/vulkanwindow/VulkanWindow.java) and
[VulkanRenderer](../src/main/java/vulkanwindow/VulkanRenderer.java) create the
window, device, swapchain, and frame synchronization. They handle resize,
fullscreen, VSync changes, and shutdown.
[VulkanDrawList](../src/main/java/vulkanwindow/VulkanDrawList.java) records
ordered rectangles, ovals, boxes, and textured images with transforms, depth,
and alpha blending. Precompiled shaders are packaged in the JAR.

Kata tasks `z0fr` (presentation lifecycle) and `ge7q` (geometry and textures)
are complete. Local validation and pixel tests cover these paths on Mesa
lavapipe, a CPU Vulkan driver. This does not establish complete game rendering
or support across physical GPUs and operating systems.

## Route to playable gameplay

Connect the existing game drawing calls to Vulkan as their dependencies become
available. Keep gameplay, saved content, networking, input, and audio behavior
unchanged. Use the existing device and resource implementation rather than
building another presentation harness.

| Task | Implementation | Completion check |
| --- | --- | --- |
| `0x3q`: Text | Port bitmap and TrueType glyph rendering and dynamic atlas uploads | Menus and in-game text render with matching layout and clipping |
| `hq0y`: Models and terrain | Port model buffers, posed models, terrain, tracks, and dynamic batches | Actual tanks and terrain render in a playable level |
| `72bp`: Render passes | Connect scene, shadow, lighting, composition, and UI drawing to Vulkan | Gameplay renders with shadows and fancy lights both enabled and disabled |
| `rj8k`: Runtime integration | Wire renderer selection into game startup, settings, screenshots, reload, and supported extension paths | Launch, play, edit, change settings, capture, and exit through the Vulkan game path |
| `gbyc`: Regression checks | Compare gameplay and editor behavior, rendering, lifecycle, and performance with OpenGL | Required game features work without unexplained differences or validation errors |
| `r4nk`: Packaging | Verify packaged Vulkan startup and native dependencies on desktop targets | Qualified distributions launch without a Vulkan SDK |
| `7gyk`: Release | Document supported systems and enable Vulkan opt-in | Users can select the completed backend and return to OpenGL |

Text and model work can proceed independently. Implement and test each path in
running code; resolve backend interface changes within the work that needs them.
Kata epic `ch9g` tracks completion. Kata is the source of current task status and
dependencies; use `kata show <ref> --agent` to inspect a task.

Earlier separate baseline, capability, backend-boundary, shader, resource, and
performance tasks were consolidated into these implementation and qualification
tasks. They are not additional preparatory gates.

## Integration constraints

- Keep game-facing drawing helpers and the screen lifecycle. Shared framebuffer
  and font factories already serve
  [RenderPassGroupShadowDraw](../src/main/java/basewindow/RenderPassGroupShadowDraw.java)
  and [Panel](../src/main/java/tanks/Panel.java). Implement or extend these
  factories for the Vulkan backend.
- Preserve logical update and draw callback order. Some effects advance during
  drawing; swapchain recreation must not replay game or extension callbacks.
- Record transforms, uniforms, colors, and geometry at draw submission time.
  Preserve transparency order, depth behavior, clipping, and layer order.
- Port the shader variants used by the game. Existing OpenGL shaders compose
  GLSL sources and headers; their built-in attributes and matrices need explicit
  Vulkan interfaces. Keep precompiled SPIR-V available to users without an SDK.
- Retire buffers, textures, descriptors, and attachments only after GPU use
  completes. Add reload and level-exit cleanup as those paths are connected.
  The renderer guide records the current swapchain retirement limitation.
- Keep the Tanks Online server independent of graphics initialization. Keep
  extensions that require OpenGL on OpenGL until their drawing paths are ported.

The game render order is optional shadow depth, scene color/glow/lighting-mask
and depth, optional point-light accumulation and composition, then UI. Preserve
both the non-shadow path and the path without fancy lights. With fancy lights
enabled, composition must still run when there are no point lights.

## Verification and distribution

Run `./gradlew build lint` for compilation and CPU checks. Run
`./gradlew vulkanGeometryTest` with a Vulkan window system and validation layers
for the existing GPU checks. Extend tests when connecting text, models, passes,
and the game loop. Use actual gameplay and editor scenes to check movement,
combat, level transitions, text, effects, resource reload, and screenshots.

The [OpenGL fixture](../experiments/vulkan/baseline/README.md) and
[capability probe](vulkan-capability-probe.md) remain available as diagnostic
tools. The fixture does not cover complete gameplay. Compare the connected
Vulkan game path against OpenGL directly as features become usable.

Retain Java 8 source compatibility and the existing LWJGL dependency version
unless implementation requires a documented change. Windows and Linux use
system Vulkan loaders; macOS uses the packaged MoltenVK library. Verify actual
packages on Windows x64, Linux x64/X11, and macOS Intel and Apple Silicon before
claiming support. Other native classifiers in the build do not establish Vulkan
support. Keep OpenGL as the default until the completed game path is qualified;
a default change requires a separate release decision.
