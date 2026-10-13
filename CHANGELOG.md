## 1.0.0

First release, for Minecraft 26.3 (Fabric Loader 0.19.5+, Fabric API).

- Hot-swaps changed classes and mixins into the running game; with JetBrains Runtime also structural changes (new methods, fields, injectors).
- Loads brand-new mods without a restart: classes, mixins (also into already-loaded classes), entrypoints, key bindings, assets.
- Reloads edited lang files and textures.
- `./gradlew hotReload` via a one-line `apply from:`, with the game's result printed in the terminal.
- `/hotreload setup`: downloads JetBrains Runtime and gives launcher-specific instructions (Prism/MultiMC, Modrinth App, CurseForge, official launcher); automatic setup for CurseForge.
