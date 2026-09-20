# Vulkan capability probe

`./gradlew vulkanProbe` enumerates the Vulkan loader and physical devices without
starting Tanks, GLFW, OpenGL, or audio. It writes one JSON object to stdout.
Gradle also writes task progress to stdout; use `--output` for a JSON file:

```sh
./gradlew vulkanProbe --args='--output=build/vulkan/headless.json'
./gradlew vulkanProbe --args='--window --output=build/vulkan/window.json'
```

The `--window` mode creates a hidden 64 × 64 GLFW window and Vulkan surface,
queries per-queue presentation support, surface formats, capabilities, and
present modes, then destroys them. It requires a working desktop/display.
Neither mode creates a logical device, swapchain, shaders, or rendered frame.
A successful probe identifies a candidate for the renderer prototype; it does
not qualify a GPU for release.

## Report and failures

The JSON schema version is `1`. `status` is `headless-candidate`,
`presentation-candidate`, `unsupported`, or `error`. `presentationSupportQueried` means
surface support was queried; `presentationTested` remains false because no frame
was presented. Each physical device has
its own `candidate` and `rejectionReasons`. Headless queues have `present: null`.
The process exits 0 when at least one candidate passes the queried requirements,
2 when devices were queried but none passes, or 1 on initialization/query errors.
Gradle reports any nonzero child exit as a failed task and may use its own exit
code. Invalid arguments fail before producing a report.

A missing loader produces `errorStage: "loader"`. No accessible driver/device
may fail at instance creation (`VK_ERROR_INCOMPATIBLE_DRIVER`, numeric -9) or
produce an explicit no-device error. Window/surface failures preserve the JSON
error and do not silently fall back to headless mode. Vulkan calls that return
an error, including an enumeration that changes size (`VK_INCOMPLETE`), fail the
run; retry the command after a driver/device change.

The provisional gate requires a Vulkan 1.1 loader and device, a graphics queue,
`VK_KHR_swapchain`, at least three color attachments, one sampled and blendable
RGBA color attachment format, and one sampled depth attachment format. Window
mode also requires a presentation queue, nonempty surface-format/present-mode
lists, and surface color-attachment usage. Graphics and present queues may differ.
Float/HDR color availability is recorded per format but is not a prerequisite
for this preliminary gate. Complete game-format requirements remain to be
settled by the rendering baseline and shader work.
These requirements implement the starting point in the
[port plan](vulkan-port-plan.md); later rendering work may add requirements.

Reports include raw Vulkan format/feature/queue bitfields, driver version,
limits, sample-count masks, and all advertised instance/device extensions.
Driver versions are vendor-specific unsigned integers. Sample-count mask `5`
means 1× and 4×; it is not five samples. Image sample counts are queried for the
combined sampled + attachment usage. Actual MSAA selection must intersect the
chosen color/depth format masks and framebuffer limits. Optional features such
as independent blending are recorded but not required by this preliminary gate.
Portability-subset features are queried when the extension and Vulkan 1.1 are
available; absent feature objects mean unqueried, not false.

## Dependencies and loader selection

The build pins `lwjgl-vulkan` through the existing LWJGL 3.4.1 BOM and adds only
its `natives-macos` and `natives-macos-arm64` runtime artifacts. Both artifacts
resolve from Maven Central and contain MoltenVK. Windows and Linux use the
system Vulkan loader and installed ICD (driver); they do not use a Vulkan
native classifier from the game's general native list. Other existing native
classifiers do not imply Vulkan support.

LWJGL's default macOS path loads bundled MoltenVK directly. This bypasses SDK
validation layers. To use an installed Vulkan SDK loader, select it explicitly:

```sh
./gradlew vulkanProbe -PvulkanLibrary=/absolute/path/libvulkan.1.dylib \
  --args='--window --output=build/vulkan/macos-sdk.json'
```

The task passes `-XstartOnFirstThread` on macOS. Before GLFW initialization, the
probe passes LWJGL's `vkGetInstanceProcAddr` to `glfwInitVulkanLoader`, so both
libraries use the same loader in direct-MoltenVK and SDK-loader configurations.
When advertised, the probe enables `VK_KHR_portability_enumeration` and its
instance flag. `portabilitySubsetRequired` records whether a future logical
device must enable `VK_KHR_portability_subset`.

These behaviors follow the [LWJGL 3.4.1 loader source](https://github.com/LWJGL/lwjgl3/blob/3.4.1/modules/lwjgl/vulkan/src/main/java/org/lwjgl/vulkan/VK.java)
and [GLFW loader guidance](https://www.glfw.org/docs/latest/vulkan_guide.html).
Both macOS loader paths still require execution on actual macOS hardware.

## Local checks and remaining qualification

Run `./gradlew vulkanProbeTest` for GPU-independent JSON escaping and capability
policy checks. `check` also runs these checks. On Linux with Xvfb, Mesa lavapipe,
and Khronos validation layers installed:

```sh
xvfb-run -a env \
  VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.x86_64.json \
  VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation \
  ./gradlew vulkanProbe --args='--window --output=build/vulkan/lavapipe.json'
```

Use the ICD path installed by your distribution. The checked-in
[local reports](../experiments/vulkan/capabilities/linux-lavapipe/README.md)
record software-renderer evidence and failure checks.

| Target | Qualification status |
| --- | --- |
| Linux x64, Debian 12, llvmpipe (Mesa 22.3.6), Xvfb | Loader/device and surface query harness tested; no rendered frame |
| Linux integrated/discrete GPUs, physical X11 display | Unavailable; untested |
| Windows x64 integrated/discrete GPUs | Unavailable; untested |
| macOS Intel, direct MoltenVK and SDK loader | Unavailable; untested |
| macOS Apple Silicon, direct MoltenVK and SDK loader | Unavailable; untested |
| Other OS/architecture combinations | Unqualified |

No minimum production OS/GPU combination is established. Kata `vww8` stays open
for the unavailable platform matrix and renderer-dependent requirements. The
probe has no reference from the game entry point, including `online_server`.
The existing OpenGL default and server startup path are unchanged.

The local shader tools report shaderc `2023.2-1` (glslc), bundled SPIRV-Tools
`2022.4+1.3.236.0-1`, glslang `11.13.0-1`, and standalone spirv-val SPIRV-Tools
`v2023.1`. These are observed development tools, not a pinned project toolchain.
Selecting reproducible compiler/validator artifacts remains shader task `jrfg`.
