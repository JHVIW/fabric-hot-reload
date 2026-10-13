<!-- Description for the Modrinth project page. -->

**Build your mod while Minecraft is running and see the change in-game within a second.**

Fabric Hot Reload is a developer tool. Install it in the instance you test with, add one line to your mod's
`build.gradle`, and run `./gradlew hotReload`: your code, mixins, lang files and textures are swapped into the running
game. Even a mod that wasn't installed yet gets loaded, with its mixins, entrypoints, key bindings and assets.

- Works with your normal launcher: Prism Launcher, MultiMC, Modrinth App, CurseForge, official launcher
- Basic mode out of the box: changed method bodies, mixin bodies, new classes, resources
- Full hotswap (new methods, fields, injectors) after `/hotreload setup`, which installs JetBrains Runtime and tells you what to set in your launcher
- Results in the game chat and in your terminal

Client side only. Not meant for modpacks or servers.

Source, setup guide and how it works: https://github.com/JHVIW/fabric-hot-reload
