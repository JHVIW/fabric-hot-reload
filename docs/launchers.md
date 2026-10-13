# Full hotswap per launcher

Full hotswap needs two things for the instance you test in:

1. **Java executable:** JetBrains Runtime. `/hotreload setup` (in game) or `java -jar fabric-hot-reload-<version>.jar install-jbr`
   downloads it to `~/.hotreload/jbr/` and prints the path (`bin/javaw.exe` on Windows, `bin/java` on Linux,
   `Contents/Home/bin/java` on macOS).
2. **JVM arguments:** add `-XX:+UseG1GC -XX:+AllowEnhancedClassRedefinition` and **remove** any other collector flag
   (`-XX:+UseZGC`, `-XX:+UseShenandoahGC`, `-XX:+UseParallelGC`, `-XX:+ZGenerational`). Enhanced redefinition only
   works with G1 (or Serial); the JVM refuses to start otherwise.

Restart the game afterwards; `/hotreload` should say *Full hotswap (JetBrains Runtime)*.

## Prism Launcher / MultiMC

Right-click the instance > **Edit** > **Settings** > **Java**:

- tick **Java installation**, set the path to the JetBrains Runtime executable
- tick **Java arguments**, add the two flags

## Modrinth App

Open the instance > **⋮** > **Edit** (Options) > **Java and memory**:

- **Custom Java installation**: the JetBrains Runtime executable
- **Custom Java arguments**: add the two flags

## CurseForge

CurseForge has no per-instance Java path, only one path per Java version. The setup helper sets the *Java 25* path to
JetBrains Runtime (this applies to every instance on Minecraft 26.x; JetBrains Runtime is a normal OpenJDK build, so
the others keep working as before) and adds the flags to this instance's JVM arguments only.

1. Quit Minecraft and close CurseForge completely (tray icon > **Exit**). It rewrites its settings when it exits.
2. Run the command `/hotreload setup` printed (click it to copy), for example:

   ```shell
   "C:\Users\you\.hotreload\jbr\...\bin\java.exe" -jar "C:\...\Instances\My Instance\mods\fabric-hot-reload-<version>.jar" curseforge "C:\...\Instances\My Instance"
   ```

Both files (`%APPDATA%\CurseForge\storage.json` and the instance's `minecraftinstance.json`) are backed up into
`<instance>\hotreload-backup-<time>\` first. Undo with the same command plus `--undo`.

## Official Minecraft Launcher

**Installations** > your Fabric installation > **Edit** > **More options**:

- **Java executable**: the JetBrains Runtime executable
- **JVM arguments**: add the two flags

## Loom dev environment (`./gradlew runClient`)

Put the Fabric Hot Reload jar in `run/mods`, and run the client on JetBrains Runtime with the flags, e.g. in
`build.gradle`:

```groovy
loom {
	runs {
		client {
			vmArgs '-XX:+UseG1GC', '-XX:+AllowEnhancedClassRedefinition'
		}
	}
}
```

and point Gradle's Java at JetBrains Runtime (`JAVA_HOME`, or `org.gradle.java.home` in `gradle.properties`).
Mods loaded straight from `build/classes` work: the state at startup is remembered and later builds are diffed against it.
