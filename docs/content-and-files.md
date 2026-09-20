# Content and files

The desktop game stores settings and user content under `~/.tanks`, where `~`
is Java's `user.home`. An explicit `userdir` selects an existing directory
instead. [Game.initScript()](../src/main/java/tanks/Game.java) defines these paths.

## User data

| Relative path | Contents |
| --- | --- |
| `options.txt` | Game options, including extension settings |
| `controls.txt` | Saved input bindings |
| `tutorial.txt` | Tutorial state |
| `uuid` | Persistent computer identifier |
| `logfile.txt`, `crashes/` | Runtime log and crash reports |
| `levels/` | User levels |
| `crusades/`, `crusades/progress/` | User campaigns and campaign progress |
| `tanks/`, `builds/` | Saved custom tanks and player builds |
| `items/`, `bullet_effects/` | Saved items and bullet effects |
| `extensions/`, `extensions.txt` | Extension JARs and registry entries |
| `screenshots/` | Captured screenshots |
| `resources/` | Resource overrides, including `languages/` |

Use a separate data directory when experimenting with settings or saved
content. The launcher syntax is documented in [Getting started](getting-started.md).
Tanks Online uses separate working-directory files described in
[Networking](networking.md).

## Levels and campaigns

[Level](../src/main/java/tanks/Level.java) parses level strings and creates
obstacles, tank instances, teams, and player spawn points. A basic layout has
the form `{dimensions|obstacles|tanks|teams}`; the team section is optional.

For example, this layout defines an 8 by 6 tile level with no obstacles, a
player spawn, and a brown tank:

```text
{8,6||1-1-player-0,6-4-brown-2}
```

Tank entries contain `x-y-type-angle` with an optional team name. Positions
are tile coordinates. Angles are quarter-turn counts, converted to radians
by multiplying by `Math.PI / 2`. Registered tank names such as `brown` are
resolved through `Game.registryTank`.

Full level files can contain named blocks beginning with `level`, `items`,
`shop`, `coins`, `tanks`, and `builds` on their own lines. These hold the layout,
starting inventory, shop, initial coins, custom tanks, and player builds.
Use the editor to generate complete files; the compact layout example above
does not describe every metadata field or content block.

The editor's save path is implemented in
[ScreenLevelEditor](../src/main/java/tanks/gui/screen/leveleditor/ScreenLevelEditor.java).
[Serializer](../src/main/java/tanks/tankson/Serializer.java) handles structured
content objects. Check both the reader and writer before changing a saved
format, and verify that existing files still load.

[Crusade](../src/main/java/tanks/Crusade.java) handles campaigns. Built-in
campaign files in [resources/crusades](../src/main/resources/crusades) provide
examples. [Tests.zip](../Tests.zip) contains manual `.tanks` test levels for
features such as custom tanks, items, lava, and teleporters.

## Packaged resources and overrides

Bundled assets live in [src/main/resources](../src/main/resources) and are
included in the JAR. `Game.postInitScript()` configures the user `resources/`
directory as an override location for window resource loading. Preserve the
relative resource path when supplying an override.

When changing a visual or audio asset, check both its resource path and its
Java caller. For shaders, inspect the corresponding class in
[tanks/rendering](../src/main/java/tanks/rendering) as well as the source in
[resources/shaders](../src/main/resources/shaders).
