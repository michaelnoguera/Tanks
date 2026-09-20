# Tanks codebase documentation

Tanks: The Crusades is a Java tank combat game with a level editor, campaigns
(called crusades), and multiplayer. This documentation covers the desktop code
and Gradle build in this repository.

| Task | Guide |
| --- | --- |
| Build and launch a development copy | [Getting started](getting-started.md) |
| Find the code responsible for a feature | [Architecture](architecture.md) |
| Locate settings, saved content, and level parsing | [Content and files](content-and-files.md) |
| Add gameplay behavior or an extension | [Development](development.md) |
| Change network events or run Tanks Online | [Networking](networking.md) |
| Run and develop the current Vulkan renderer | [Vulkan renderer development](vulkan-renderer.md) |
| Follow the remaining desktop Vulkan port | [Vulkan port plan](vulkan-port-plan.md) |
| Publish local Kata tasks to GitHub | [Task sync](task-sync.md) |
| Query Vulkan devices and surface support | [Vulkan capability probe](vulkan-capability-probe.md) |
| Capture a repeatable OpenGL fixture | [OpenGL baseline tooling](../experiments/vulkan/baseline/README.md) |

Start with the build guide, then follow the startup and frame flow in the
architecture guide. Each guide links to the implementation it describes.

The [project README](../README.md) contains screenshots and credits.
[BUILD.md](../BUILD.md) contains the existing IDE setup notes, and
[LICENSE.md](../LICENSE.md) contains the project license.
