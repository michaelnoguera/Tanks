# Vulkan renderer development

The Vulkan device and presentation implementation lives in `vulkanwindow`.
It draws a triangle through a GLFW window without creating an OpenGL context.
Game drawing commands are not connected yet; the normal Tanks launcher still
uses OpenGL.

Run from the repository root with JDK 17 and a Vulkan 1.1 driver:

```sh
./gradlew runVulkan
./gradlew runVulkan --args='--validation --frames=60 --exercise-window'
```

`--validation` requires `VK_LAYER_KHRONOS_validation` from the Vulkan SDK or
the operating system's validation-layer package. Omit it for ordinary use.
The Gradle task supplies the macOS first-thread option. Use
`-PvulkanLibrary=/path/to/loader` to override LWJGL's Vulkan loader.

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
glslc --target-env=vulkan1.1 src/main/resources/vulkan/triangle.vert -o src/main/resources/vulkan/triangle.vert.spv
glslc --target-env=vulkan1.1 src/main/resources/vulkan/triangle.frag -o src/main/resources/vulkan/triangle.frag.spv
spirv-val --target-env vulkan1.1 src/main/resources/vulkan/triangle.vert.spv
spirv-val --target-env vulkan1.1 src/main/resources/vulkan/triangle.frag.spv
```
