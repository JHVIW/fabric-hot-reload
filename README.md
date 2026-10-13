<p align="center"><img src="src/main/resources/assets/hotreload/icon.png" width="96" alt=""></p>

<h1 align="center">Fabric Hot Reload</h1>

<p align="center">
Build your mod while Minecraft is running, see the change in-game within a second.<br>
Code, mixins, lang files, textures, and even <b>brand-new mods</b> load without restarting the game.
</p>

<p align="center">
<a href="https://github.com/JHVIW/fabric-hot-reload/actions/workflows/build.yml"><img src="https://github.com/JHVIW/fabric-hot-reload/actions/workflows/build.yml/badge.svg" alt="build"></a>
<img src="https://img.shields.io/badge/Minecraft-26.3-62B47A" alt="Minecraft 26.3">
<img src="https://img.shields.io/badge/loader-Fabric-DBD0B4" alt="Fabric">
<img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT">
</p>

```text
$ ./gradlew hotReload
> Task :compileClientJava
> Task :hotReload
Hot Reload: Loaded new mod: My Mod (1 mixin config (1 already-loaded targets rebuilt), 1 entrypoint, resources)

# edit some code, run it again
$ ./gradlew hotReload
Hot Reload: Reloaded: MinecraftMixin (mixin), MyModHud
```

## Why

Restarting Minecraft for every change takes a minute. Fabric Hot Reload is a small client-side mod that watches
your mod project's build output and swaps the new code into the **running** game: your normal launcher, your normal
modpack, your world stays loaded.

| You change | What happens | Needs |
|---|---|---|
| Code inside a method | swapped in, runs from the next call | nothing |
| Mixin injector body | mixin re-applied to the target, live | nothing |
| New/removed methods, fields, lambdas | swapped in | full hotswap* |
| New `@Inject` / mixin method | mixin re-applied, live | full hotswap* |
| New class | loaded when first used | nothing |
| Lang file, texture, model | resources reloaded (like F3+T) | nothing |
| **A whole new mod** | loaded: classes, mixins, entrypoints, key bindings, assets | full hotswap* for mixins into already-loaded classes |

\* *Full hotswap* = the game runs on [JetBrains Runtime](https://github.com/JetBrains/JetBrainsRuntime), a regular
OpenJDK build that can redefine classes structurally. `/hotreload setup` downloads it and tells you exactly what to set
in your launcher. Without it, everything marked "nothing" still works and anything else gets a clear message.

## Quick start

**1. Install the mod** in the game you test with: drop `fabric-hot-reload-<version>.jar` (from
[Releases](https://github.com/JHVIW/fabric-hot-reload/releases)) into the instance's `mods` folder, next to Fabric
API. Works with Prism Launcher, MultiMC, Modrinth App, CurseForge and the official Minecraft Launcher.

**2. Add one line** to your mod's `build.gradle`:

```groovy
apply from: 'https://raw.githubusercontent.com/JHVIW/fabric-hot-reload/v1.0.0/gradle/hotreload.gradle'
```

<sub>`build.gradle.kts`: `apply(from = "https://raw.githubusercontent.com/JHVIW/fabric-hot-reload/v1.0.0/gradle/hotreload.gradle")`</sub>

**3. Start Minecraft, then run** in your mod project:

```shell
./gradlew hotReload        # compile + load/update in the running game
./gradlew hotReload -t     # ... on every save
```

The result shows in your terminal and in the game chat. Your mod does not need to be installed in the game: the
first `hotReload` loads it.

**4. (Recommended) Full hotswap:** type `/hotreload setup` in game. It downloads JetBrains Runtime (~100 MB, once,
SHA-512 verified) and shows click-to-copy values for your launcher. For CurseForge it prints one command that does it
for you. Details per launcher: [docs/launchers.md](docs/launchers.md).

Try it without your own mod: `./gradlew -p examples/hello-hotreload hotReload` in a clone of this repo.

## Commands

| | |
|---|---|
| `/hotreload` | status: basic or full hotswap, watched projects, mods loaded live |
| `/hotreload setup` | download JetBrains Runtime and show launcher instructions |
| `java -jar fabric-hot-reload-<version>.jar` | setup helper for when the game is closed (`install-jbr`, `curseforge <instance folder> [--undo]`) |

## Configuration

`config/hotreload.json`:

```json
{
  "projects": ["C:/dev/my-mods"],
  "chatMessages": true
}
```

`projects` lists extra folders to watch: a mod project, or a folder containing mod projects. Not needed for projects
you used `./gradlew hotReload` on; those are remembered in `~/.hotreload/projects.json`.

## Good to know

- **Static initializers and constructors don't re-run.** Existing objects keep their field values; a changed
  `static final` value only shows up after a restart. Code that registers things (key bindings, events, items) runs
  when a mod is first loaded, not when it is edited.
- **New mods aren't in Fabric's mod list**: `FabricLoader.getModContainer("yourmod")` stays empty until the next
  restart. Dependencies between hot-loaded mods are not checked.
- **Client side only.** Registries (new blocks/items) are frozen after startup and server code isn't covered.
- **It's a dev tool.** It attaches a Java agent to the game and changes classes at runtime. Don't ship it in
  modpacks or use it on servers you don't own.
- For a permanent install, build the jar (`./gradlew build`) and put it in `mods` as usual.

How it works under the hood: [docs/how-it-works.md](docs/how-it-works.md).

## Building

Requires JDK 25.

```shell
./gradlew build        # jar in build/libs
```

## License

[MIT](LICENSE). Not affiliated with FabricMC, Mojang or Microsoft. JetBrains Runtime is downloaded from JetBrains
and licensed under GPLv2 with Classpath Exception.
