# Getting started

Build from the repository root with the checked-in Gradle wrapper. The `run`
task builds the game JAR and launches it with debugging enabled.

## Build and run

You need Git, a Java Development Kit (JDK), and a graphical desktop to run the
game. Make sure `java` and `javac` are on your path, or set `JAVA_HOME` to your
JDK directory. The build declares Java 8 source compatibility; it does not pin
a Java toolchain. The wrapper pins Gradle 8.10.1.

Use JDK 17 for local development, including the Vulkan tools. The
[build workflow](../.github/workflows/build.yml) still pins Liberica JDK
8.0.312. Java source compatibility is a compiler setting, separate from the JDK
used to run Gradle. Packaging a macOS app requires `jpackage`, as described below.

On Linux or macOS:

```sh
java -version
javac -version
./gradlew build
./gradlew run
```

On Windows, from PowerShell:

```powershell
java -version
javac -version
.\gradlew.bat build
.\gradlew.bat run
```

The first invocation downloads Gradle and dependencies. The build resolves
libraries from Maven Central and the local Maven repository. Import
[build.gradle.kts](../build.gradle.kts) as a Gradle project when using an IDE.

The JAR is written to `build/libs/Tanks-<version>-<commit>.jar`. The version comes
from [version.txt](../src/main/resources/version.txt), and the commit suffix
comes from `git rev-parse --short HEAD`. The JAR includes runtime dependencies
and native libraries. Gradle configuration also writes the commit hash to the
ignored file `src/main/resources/hash.txt`.

## Tasks

| Command | Purpose |
| --- | --- |
| `./gradlew build` | Compile, package, and run the configured verification lifecycle |
| `./gradlew run` | Build the JAR and launch `main.Tanks` with `debug` |
| `./gradlew runVulkan` | Launch the standalone Vulkan geometry scene; gameplay still uses OpenGL |
| `./gradlew vulkanGeometryTest` | Run Vulkan GPU checks; requires a window system and validation layers |
| `./gradlew lint` | Run Checkstyle on the main source set |
| `./gradlew clean` | Remove build outputs |
| `./gradlew BuildMacApp` | Package `build/distributions/Tanks.app` using the current JDK's `jpackage` |

Lint is separate from `build`. The build runs CPU checks for the Vulkan probe
and draw list. GPU checks run separately; a successful build alone does not
exercise gameplay. See [Vulkan renderer development](vulkan-renderer.md) and the
[development checks](development.md) for verification commands.

`BuildMacApp` requires macOS and a JDK containing `jpackage`. Its configuration
sets `-XstartOnFirstThread`, headless AWT, and the game argument `mac`.

## Launch arguments

For custom arguments, launch the built JAR directly. Replace the placeholder
filename below with the actual output filename:

```sh
java -jar "build/libs/Tanks-<version>-<commit>.jar" debug no_steam
```

[main.Tanks](../src/main/java/main/Tanks.java) recognizes these arguments:

| Argument | Behavior |
| --- | --- |
| `debug` | Enable debug behavior, including network event registration checks |
| `no_steam` | Disable Steam integration |
| `userdir=/absolute/path` | Use an existing directory for game settings and content |
| `mac` or `no_relaunch` | Disable the automatic macOS JAR relaunch |
| `online_server` | Run the separate Tanks Online service |
| `port=8080` | Set the Tanks Online service port; default is 8080 |
| `+connect_lobby <id>` | Supply a Steam lobby invitation ID |

Quote the whole `userdir` argument with double quotes if the path contains
spaces. Create the directory before launching. The parser rejects apostrophes
in this argument and splits it at `=`, so avoid `=` in directory names.

On macOS, the automatic JAR relaunch forwards only `mac`, dropping other
arguments. To preserve custom arguments, launch explicitly:

```sh
java -XstartOnFirstThread -Djava.awt.headless=true -jar "build/libs/Tanks-<version>-<commit>.jar" mac debug no_steam
```

## First gameplay check

Launch the game, enter a level, move, shoot, place a mine, and pause. The default
bindings come from [InputBindings](../src/main/java/tanks/gui/input/InputBindings.java):

| Action | Default input |
| --- | --- |
| Move | WASD or arrow keys |
| Shoot | Left mouse button or Space |
| Place a mine | Right mouse button or Enter |
| Pause | Escape |
| Open the editor object menu | Space while editing |
| Play the edited level | Enter while editing |
| Open the editor pause menu | Escape while editing |

## Troubleshooting

For startup failures, inspect terminal output, `logfile.txt`, and `crashes/`
in the [game data directory](content-and-files.md). Graphics and audio startup
run through LWJGL and require working native libraries and drivers.

If Steam initialization prevents local development, retry with `no_steam`.
On macOS, retain the first-thread JVM option when bypassing automatic relaunch.
For Gradle failures, rerun the failing task with `--stacktrace` and check the
JDK selected by `JAVA_HOME` and the IDE.
