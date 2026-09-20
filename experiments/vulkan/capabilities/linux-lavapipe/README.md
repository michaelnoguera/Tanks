# Linux software Vulkan probe evidence

The reports in this directory were captured on 2026-09-20 with the standalone
[capability probe](../../../../docs/vulkan-capability-probe.md). The environment
was Debian 12 x64, kernel `6.1.0-53-amd64`, OpenJDK `17.0.20.1`, LWJGL `3.4.1`,
Vulkan loader `1.3.239`, and llvmpipe/Mesa `22.3.6` (LLVM `15.0.6`). The physical
device reports API `1.3.230` and device type `4` (CPU).

| Artifact | Command/environment | Result |
| --- | --- | --- |
| [headless.json](headless.json) | `./gradlew vulkanProbe --args='--output=experiments/vulkan/capabilities/linux-lavapipe/headless.json'` | Exit 0; headless candidate, presentation unqueried |
| [window.json](window.json) | `./gradlew vulkanProbe --args='--window --output=experiments/vulkan/capabilities/linux-lavapipe/window.json'`, under `xvfb-run -a`; `VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.x86_64.json`, `VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation` | Exit 0; surface support candidate; no validation errors emitted |
| [missing-loader.json](missing-loader.json) | `-PvulkanLibrary=/nonexistent/tanks-vulkan-loader.so` | Child exit 1; loader error |
| [missing-icd.json](missing-icd.json) | `VK_ICD_FILENAMES=/nonexistent/tanks-icd.json` | Child exit 1; instance creation returns -9 (`VK_ERROR_INCOMPATIBLE_DRIVER`) |
| [no-display.json](no-display.json) | `--window` with empty `DISPLAY` and `WAYLAND_DISPLAY` | Child exit 1; GLFW initialization error |

Use the respective report path with `--output` to repeat each failure check.
The [window log](window.log) records the validation-enabled Gradle invocation's
output. Validation covered instance, surface, and physical-device queries only;
there was no logical device, swapchain, render submission, or present call.

The device exposes eight color attachments, sampled color/depth candidate
formats, and independent blending. Both `limits.framebufferColorSampleCounts`
and `limits.framebufferDepthSampleCounts` report mask `5` (1× and 4×). Consult
each format's `sampleCounts` before selecting a format/sample combination. Queue
family 0 reports both graphics and presentation support for the Xvfb surface.
These observations qualify this query harness on a CPU driver. They do not
qualify a physical GPU, a real desktop session, or either macOS loader path.

The GPU-independent policy/JSON checks passed. The probe and test sources also
compiled with `javac --release 8` using the resolved LWJGL jars; execution used
JDK 17, so Java 8 runtime behavior remains untested. Both macOS Vulkan native
artifacts resolved at version `3.4.1`; neither was executed on this Linux host.

Shader tools available on the host reported:

```text
glslc: shaderc 2023.2-1
       spirv-tools 2022.4+1.3.236.0-1
       glslang 11.13.0-1
spirv-val: SPIRV-Tools v2023.1, 2023-02-15T14:03:51
```

The port's reproducible shader-toolchain selection remains open in Kata `jrfg`.
