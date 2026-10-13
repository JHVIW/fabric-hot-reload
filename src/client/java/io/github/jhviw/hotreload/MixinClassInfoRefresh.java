package io.github.jhviw.hotreload;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

/**
 * Mixin's reload path (also used by its own MixinAgent) only supports changed method bodies: the member list of a
 * mixin class lives in a cached {@link ClassInfo} that the reload reuses as-is ({@code MixinInfo.Reloaded} passes the
 * previous state's ClassInfo on), so a reloaded mixin with a new injector fails its conform step ("Conform injector
 * ..."). When the members changed, this builds a fresh ClassInfo from the new bytes and installs it in the cache, in
 * the MixinInfo and in its current state before the reload runs.
 * <p>
 * Only done when members changed: a fresh ClassInfo forgets the unique names Mixin gave existing handlers, so they get
 * new names in the target, which only an enhanced-redefinition JVM accepts (and a member change needs that anyway).
 */
final class MixinClassInfoRefresh {
	private static final String MIXIN_INFO = "org.spongepowered.asm.mixin.transformer.MixinInfo";

	private MixinClassInfoRefresh() {
	}

	static void beforeReload(byte @Nullable [] previousBytes, ClassNode node) {
		if (previousBytes != null && members(previousBytes).equals(members(node))) {
			return;
		}
		try {
			ClassInfo old = ClassInfo.fromCache(node.name);
			if (old == null) {
				return;
			}
			Object mixinInfo = field(ClassInfo.class, "mixin").get(old);
			if (mixinInfo == null) {
				return;
			}
			ClassLoader mixinLoader = ClassInfo.class.getClassLoader();
			Class<?> mixinInfoClass = Class.forName(MIXIN_INFO, false, mixinLoader);
			Class<?> stateClass = Class.forName(MIXIN_INFO + "$State", false, mixinLoader);
			Class<?> classNodeClass = Class.forName(MIXIN_INFO + "$MixinClassNode", false, mixinLoader);

			// A MixinClassNode makes ClassInfo treat it as a mixin (and wraps methods as MixinMethodNodes).
			Constructor<?> ctor = classNodeClass.getDeclaredConstructor(mixinInfoClass, mixinInfoClass);
			ctor.setAccessible(true);
			ClassNode mixinNode = (ClassNode) ctor.newInstance(mixinInfo, mixinInfo);
			node.accept(mixinNode);

			cache().remove(node.name);
			Method fromClassNode = ClassInfo.class.getDeclaredMethod("fromClassNode", ClassNode.class);
			fromClassNode.setAccessible(true);
			ClassInfo fresh = (ClassInfo) fromClassNode.invoke(null, mixinNode);

			field(mixinInfoClass, "info").set(mixinInfo, fresh);
			Object state = field(mixinInfoClass, "state").get(mixinInfo);
			if (state != null) {
				field(stateClass, "classInfo").set(state, fresh);
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			Messages.LOG.warn("could not refresh Mixin's ClassInfo for {}; new injectors may not reload", node.name, e);
		}
	}

	private static Set<String> members(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
		return members(node);
	}

	private static Set<String> members(ClassNode node) {
		Set<String> result = new HashSet<>();
		for (MethodNode m : node.methods) {
			result.add(m.name + m.desc);
		}
		for (FieldNode f : node.fields) {
			result.add(f.name + ":" + f.desc);
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, ClassInfo> cache() throws ReflectiveOperationException {
		return (Map<String, ClassInfo>) field(ClassInfo.class, "cache").get(null);
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}
}
