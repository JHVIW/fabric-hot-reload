package io.github.jhviw.hotreload.agent;

import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;

/**
 * Java agent half of Fabric Hot Reload. A JVM refuses to attach an agent to itself unless started with
 * {@code -Djdk.attach.allowAttachSelf}, so the mod spawns a short-lived child JVM ({@link #main}) that attaches this
 * jar to the game's PID. {@link #agentmain} then runs inside the game, loaded by the SYSTEM class loader (not
 * Fabric's), and parks the Instrumentation in a static field that the mod reads back via reflection.
 * <p>
 * Must not reference any Minecraft or Fabric class.
 */
public final class HotReloadAgent {
	public static volatile Instrumentation instrumentation;

	private HotReloadAgent() {
	}

	public static void agentmain(String args, Instrumentation inst) {
		instrumentation = inst;
	}

	/** Child-process entry point: {@code HotReloadAgent <pid> <agentJar>}. */
	public static void main(String[] args) throws Exception {
		VirtualMachine vm = VirtualMachine.attach(args[0]);
		try {
			vm.loadAgent(args[1]);
		} finally {
			vm.detach();
		}
	}
}
