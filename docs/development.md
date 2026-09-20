# Development

Choose the owning screen or entity from the [architecture guide](architecture.md),
then trace its update, drawing, persistence, and network paths before changing
behavior. A tank property can affect the editor, saved levels, and multiplayer
as well as local combat.

## Add a tank type

1. Start with a nearby implementation such as
   [TankBrown](../src/main/java/tanks/tank/TankBrown.java). Most AI tanks derive
   from [TankAIControlled](../src/main/java/tanks/tank/TankAIControlled.java).
2. Provide a public constructor with the signature
   `(String name, double x, double y, double angle)`.
   [RegistryTank](../src/main/java/tanks/registry/RegistryTank.java) uses that
   exact signature through reflection.
3. Register the class with `Game.registerTank(class, name, weight)` during
   initialization, or in an extension's `setUp()` method. The name identifies
   the tank in level data; the weight controls random selection. Existing
   registrations in [Game](../src/main/java/tanks/Game.java) include zero-weight
   entries that are excluded from weighted random selection.
4. Place it in the editor, save and reload the level, and play it. If it spawns
   entities or adds synchronized behavior, also verify it in a host/client party.

Keep registered names stable when saved content depends on them. Registry
weights are initialized after extension setup, so late registration requires
more work than adding another registry entry.

## Extensions

[Extension](../src/main/java/tanks/extension/Extension.java) provides these hooks:

| Hook | Use |
| --- | --- |
| `setUp()` | Register tanks, obstacles, items, or events during initialization |
| `loadResources()` | Load resources after the window is available |
| `preUpdate()`, `update()` | Run before and after `Panel.update()` |
| `preDraw()`, `draw()` | Run around `Panel.draw()` within a render pass |

To package an extension, compile against the Tanks JAR and create a subclass
with a public no-argument constructor that calls `super("Extension name")`.
Put its JAR under the game data directory's `extensions/` folder.

With the game closed, set `enable_extensions=true` in `options.txt`. Add an
entry to `extensions.txt`, with no spaces around the comma:

```text
Example.jar,example.ExampleExtension
```

Alternatively, put the fully qualified class name on the first line of an
`extension.txt` file at the root of the extension JAR and list only its JAR
filename in `extensions.txt`. Setting `auto_load_extensions=true` also enables
directory scanning for extensions with that metadata.

[ExtensionRegistry](../src/main/java/tanks/extension/ExtensionRegistry.java)
collects loaded classes in a `HashMap`; do not depend on the order of lines
in `extensions.txt` for setup ordering.

For development without exporting an extension JAR,
`Tanks.launchWithExtensions(args, extensions, order)` accepts extension
instances and optional insertion indices. These supplied instances load even
when file-based extensions are disabled. The helper suppresses macOS
relaunch, so an IDE launch on macOS needs `-XstartOnFirstThread`.

## Code style and checks

Follow [AGENTS.md](../AGENTS.md) and surrounding code. The enforced style is
defined in [checkstyle.xml](../config/checkstyle/checkstyle.xml), with custom
checks in [src/checkstyle/java/tanks/linting](../src/checkstyle/java/tanks/linting).
It includes four-space indentation, Allman braces for multiline blocks, a
180-character line limit with configured exceptions, and wildcard imports
when more than three classes come from one package.

Run the relevant build and lint checks separately:

```sh
./gradlew build
./gradlew lint
```

Checkstyle writes XML and HTML reports under `build/reports/checkstyle/`.
The custom Checkstyle classes are compiled separately and excluded from the
production JAR.

The [automated tests](../src/test/java) cover Vulkan probe reporting and draw
submission through `check`. GPU pixel and lifecycle checks run separately with
`./gradlew vulkanGeometryTest`; see [Vulkan renderer development](vulkan-renderer.md)
for prerequisites. `Tests.zip` contains manual level fixtures. Extract it outside your game data directory,
then copy the desired `.tanks` files into the test profile's `levels/` folder.

Choose manual checks to match the change:

| Changed area | Check |
| --- | --- |
| Combat or entity lifecycle | Play a small level through completion and restart it |
| Editor or serialization | Create, save, reload, and play affected content |
| Rendering or resources | Inspect the affected scene with relevant graphics settings |
| Input or options | Change a setting, restart, and verify persistence |
| Multiplayer | Follow the [local party check](networking.md) and exercise the changed event |

Record the JDK, command, result, and any manual checks when reporting a change.
Distinguish compilation success from gameplay that you actually exercised.
