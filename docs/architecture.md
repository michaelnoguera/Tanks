# Architecture

The desktop launcher creates an LWJGL window. Window callbacks drive a shared
`Game` state and the active `Screen`. Gameplay, menus, and the level editor use
the same screen lifecycle.

## Source map

Paths below are relative to [src/main/java](../src/main/java).

| Package or class | Responsibility |
| --- | --- |
| `main/Tanks.java` | Argument parsing, desktop startup, and Tanks Online startup |
| `basewindow/` | Window, input, file, sound, model, and shader abstractions |
| `lwjglwindow/` | Desktop OpenGL implementations using LWJGL |
| `vulkanwindow/` | Standalone Vulkan window, renderer, draw list, and demonstration scene |
| `vulkanprobe/` | Vulkan device and surface capability query tool |
| `tanks/Game.java` | Shared state, registries, initialization, and object helpers |
| `tanks/Panel.java` | Frame timing, network event processing, screen dispatch, and interface drawing |
| `tanks/Drawing.java` | Drawing helpers and world/interface coordinate conversion |
| `tanks/gui/screen/` | Menus, gameplay screens, options, and party screens |
| `tanks/gui/screen/leveleditor/` | Editor state, overlays, and metadata selectors |
| `tanks/tank/`, `tanks/bullet/`, `tanks/obstacle/` | Combat entities and terrain behavior |
| `tanks/item/`, `tanks/hotbar/` | Items, inventory, and player equipment |
| `tanks/Level.java`, `tanks/Crusade.java` | Level loading and campaign state |
| `tanks/generator/`, `tanks/minigames/` | Level generation and alternative game rules |
| `tanks/rendering/` | Terrain rendering, shaders, lighting, and render passes |
| `tanks/registry/`, `tanks/extension/` | Content registration and extension loading |
| `tanks/tankson/` | Serialization of game content objects |
| `tanks/network/` | Party connections, Steam networking, and network events |
| `tanksonline/` | Online level sharing service and its screens |

[src/main/resources](../src/main/resources) contains images, sounds, music,
models, shaders, fonts, and built-in content. Custom Checkstyle implementations
live separately in [src/checkstyle/java](../src/checkstyle/java).

The [Vulkan renderer](vulkan-renderer.md) currently has a separate launcher;
it does not run the game lifecycle described below.

## Startup

1. [Tanks.main](../src/main/java/main/Tanks.java) parses arguments and selects
   desktop game startup or Tanks Online startup.
2. For the desktop game, `Game.initScript()` registers events and content,
   prepares user files, loads options and extensions, calls extension `setUp()`
   hooks, initializes tank selection weights, and loads input bindings.
3. The launcher creates `GameDrawer` and `LWJGLWindow`, supplying
   `GameUpdater` and `GameWindowHandler` callbacks.
4. `Game.postInitScript()` installs the user's resource override directory.
   `window.run()` starts the window loop.

Keep registration in the initialization phase. Window-dependent resources
belong in the later resource-loading phase; extensions provide a separate
`loadResources()` hook for this purpose.

## Updates and drawing

[GameUpdater](../src/main/java/tanks/GameUpdater.java) calls extension
`preUpdate()` hooks, `Panel.update()`, then extension `update()` hooks.
[Panel](../src/main/java/tanks/Panel.java) processes queued incoming events
and calls `Game.screen.update()`. During gameplay,
[ScreenGame](../src/main/java/tanks/gui/screen/ScreenGame.java) updates moving
objects and active obstacles and processes deferred removals.

`Panel.frameFrequency` is elapsed time in units of 10 milliseconds, not frames
per second. Normal updates cap it at 20; deterministic modes substitute
`100.0 / 30` or `100.0 / 60`. Movement and timers commonly scale their changes
by this value. See [BaseWindow](../src/main/java/basewindow/BaseWindow.java)
for the elapsed-time calculation.

[GameDrawer](../src/main/java/tanks/GameDrawer.java) runs the main render
passes, optional lighting, and the UI pass. Its `drawSinglePass()` wraps
`Panel.draw()` in extension drawing hooks. Rendering can involve multiple
passes, so drawing hooks must not advance simulation state.

## Shared state and object lifetime

[Game](../src/main/java/tanks/Game.java) holds the active screen, player,
level state, entity collections, registries, and incoming/outgoing event queues.
Changing `Game.screen` changes which screen receives updates and drawing calls.

Moving entities derive from [Movable](../src/main/java/tanks/Movable.java).
`Game.movables`, `Game.obstacles`, and `Game.effects` hold active objects.
Removal collections such as `Game.removeMovables` defer deletion until the
gameplay cleanup phase. Follow the existing removal path when updating an
entity; it also maintains spatial and rendering state.

`Game.addMovable()` updates spatial chunks as well as the main collection.
`Game.addTank()` additionally registers a network ID and queues a tank creation
event. It is intended for computer-controlled tanks on the authoritative side
and rejects player and remote tank classes. Use the appropriate helper instead
of appending directly to an entity list.

World coordinates use `Game.tile_size`, currently 50 units per tile. The level
loader converts a tank's tile coordinate to its center with
`(coordinate + 0.5) * Game.tile_size`. UI coordinates use separate scaling in
`Drawing`; do not assume a UI coordinate is a world coordinate.
