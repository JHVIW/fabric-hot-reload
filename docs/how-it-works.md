# How it works

## Getting into the JVM

A JVM can redefine classes through `java.lang.instrument.Instrumentation`, which only Java agents get. The mod jar is
also an agent (`Agent-Class` in its manifest). At startup the mod starts a tiny child JVM that attaches the jar to the
game's own PID (a JVM may not attach to itself), so no launcher flags are needed for the basic mode. The agent runs in
the system class loader and hands its `Instrumentation` to the mod via a static field.

## Watching

A background thread polls the build folders (`build/classes/java/{main,client}`, `build/resources/{main,client}`) of
every watched project four times a second. Once nothing has changed for 600 ms (Gradle finished writing), a reload
round runs on the render thread. The Gradle task also writes a request into `~/.hotreload/projects.json`; every
request gets an answer in `~/.hotreload/events.log`, which the task prints.

## Classes

For every class file whose bytes differ from what the game runs (initially: the bytes in the mod's jar):

- **loaded** → `Instrumentation.redefineClasses`. All changed classes are redefined in one batch; if the JVM rejects
  the batch, they are retried one by one so the compatible ones still go live and the others are reported.
- **not loaded yet** → a `ClassFileTransformer` substitutes the new bytes when the class loads. Brand-new classes are
  found because the build folder is added to Fabric's class path.

A stock HotSpot only accepts changed method bodies. JetBrains Runtime with `-XX:+AllowEnhancedClassRedefinition` also
accepts added/removed methods and fields (it only supports the G1 and Serial collectors).

## Mixins

Mixin has a reload path (`IMixinTransformer.reload`, also used by its own MixinAgent), but only for changed method
bodies. Fabric Hot Reload:

1. refreshes Mixin's cached `ClassInfo` of the mixin class when its members changed (otherwise a new injector fails
   the "conform" step), and points the `MixinInfo` and its state at the new one;
2. calls `reload` with the new class node, which returns the target classes;
3. for each target that is already loaded, takes its pre-mixin bytes from Fabric's loader, runs the full mixin
   transformer again (every mod's mixins, including the new version) and redefines the class.

## New mods

A project whose mod id is not loaded and that is built (or requested) while the game runs is loaded like Fabric would
have at launch:

- build folders go on Fabric's class path;
- its mixin configs are registered with Mixin. Mixin only selects new configs before the first class has been
  transformed, so its private `MixinProcessor.select` is invoked directly. The configs are marked non-required, which
  lets them target classes that are already loaded (Mixin would otherwise refuse with "loaded too early"; it still logs
  that line). Those targets are rebuilt and redefined as above;
- `main` and `client` entrypoints run. Fabric only accepts key bindings before `Options` exists, so `Minecraft.options`
  is briefly hidden during the call, exactly like during a normal launch, and new key bindings are appended to
  `Options.keyMappings` afterwards;
- `assets/` are served as a required resource pack (an extra `RepositorySource` in the client's pack repository),
  followed by a resource reload. The same pack mechanism makes edited lang files and textures of any watched mod
  override the ones in its jar.

The mod does not appear in Fabric's mod list and dependency declarations are not checked.
