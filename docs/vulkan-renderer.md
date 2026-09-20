# Vulkan renderer development

The Vulkan device and presentation implementation lives in `vulkanwindow`.
It renders ordered rectangles, ovals, boxes, and packaged Tanks images through
`VulkanDrawList` without an OpenGL context. The standalone scene exercises this
adapter; gameplay, fonts, models, lighting, and shadows are not connected yet.
The normal Tanks launcher still uses OpenGL.

Run from the repository root with JDK 17 and a Vulkan 1.1 driver:

```sh
./gradlew runVulkan
./gradlew runVulkan --args='--validation --frames=60 --exercise-window --capture=geometry.png'
```

`--validation` requires `VK_LAYER_KHRONOS_validation` from the Vulkan SDK or
the operating system's validation-layer package. Omit it for ordinary use.
The Gradle task supplies the macOS first-thread option. Use
`-PvulkanLibrary=/path/to/loader` to override LWJGL's Vulkan loader.

`--capture=path.png` saves the first rendered swapchain image before presentation.
It requires a surface with transfer-source support and an RGBA8 or BGRA8 format.

`--frames=N` exits after N presented frames; zero runs until the window closes.
`--resize-frame=N` requests an 800 × 600 window after N frames.
`--no-vsync` prefers mailbox or immediate presentation, with FIFO as the fallback.
`--exercise-window` resizes, enters and exits fullscreen, toggles VSync, and
requests minimize/restore at five-frame intervals. Use at least 30 frames to
execute the sequence. A window manager is needed to exercise minimization.

`VulkanWindow` owns GLFW and the Vulkan loader. `VulkanRenderer` owns the
instance, surface, device, swapchain, shaders, and synchronization objects.
Close the renderer before the window. Two frame slots each own a command buffer,
acquire semaphore, and fence; presentation semaphores belong to swapchain images.
Zero-sized framebuffers pause rendering. Resize and out-of-date swapchains are
recreated; surface or device loss terminates with a Vulkan error diagnostic.

Recreation and shutdown currently wait for device idle before destroying
presentation resources. Normal frames do not wait for device idle. This is the
conventional fallback, but it does not establish presentation completion on
every window system; present-fence retirement remains needed for that guarantee.
See [Khronos's presentation synchronization guidance](https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html).

Precompiled shaders are packaged with their sources in
`src/main/resources/vulkan`. Rebuild them with `glslc` and validate with
SPIRV-Tools:

```sh
glslc --target-env=vulkan1.1 src/main/resources/vulkan/geometry.vert -o src/main/resources/vulkan/geometry.vert.spv
glslc --target-env=vulkan1.1 src/main/resources/vulkan/geometry.frag -o src/main/resources/vulkan/geometry.frag.spv
spirv-val --target-env vulkan1.1 src/main/resources/vulkan/geometry.vert.spv
spirv-val --target-env vulkan1.1 src/main/resources/vulkan/geometry.frag.spv
```

`VulkanDrawList` records commands in submission order. Call `begin()` to reset
geometry, color, and transform, then use `setColor`, `setTransform`, `fillRect`,
`fillOval`, `fillBox`, or `drawImage`. Colors use 0–255 channels; image UVs use a
top-left origin. The column-major matrix maps to Vulkan clip coordinates
(x/y −w to w, z 0 to w); the default identity uses normalized device coordinates
with y increasing downward. Positions and sizes use the transform’s input space;
with identity, the visible x/y range is −1 to 1. Homogeneous w reaches the vertex shader for GPU
clipping and perspective interpolation. Boxes accept `BaseShapeRenderer.hide_*`
face masks. Image paths name packaged classpath resources.

Pass the list to `VulkanRenderer.setDrawing` and call `drawFrame` on the window
thread. The renderer retains the list; the caller can rebuild it between frames
without calling `setDrawing` again. Rectangles, ovals, and images take an explicit
`depth` boolean; boxes always test depth. Opaque shapes write depth; shapes with
color alpha below 255 and all images test depth without writing it. Commands
with depth disabled use submission order. `setColor` multiplies image RGBA,
including alpha. Textures use straight alpha, linear filtering, and clamp-to-edge
sampling. Texture alpha zero is discarded.

Each frame slot owns its vertex buffer, which is updated or replaced only after
its fence signals. Each swapchain image owns a depth attachment. Textures are
uploaded synchronously on first use, cached until renderer shutdown, and limited
to 1,024 resources including the white texture used by colored primitives.
This path has no texture eviction, mipmaps, additive blending, or MSAA.

Run CPU checks through `./gradlew check`. Run GPU pixel checks with a Vulkan
window system and validation layers:

```sh
./gradlew vulkanGeometryTest
```

The GPU checks cover depth, alpha, texture pixels, vertex-buffer growth, and
resize. They also fail on validation errors.
