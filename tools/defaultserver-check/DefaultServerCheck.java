import dev.nexoclient.nexomod.servers.NexoDefaultServer;

public class DefaultServerCheck {
	public static void main(String[] args) {
		for (String same : new String[] {"smp.nexomc.dev", "SMP.NexoMC.dev", " smp.nexomc.dev ", "smp.nexomc.dev:25565", "smp.nexomc.dev."}) {
			if (!NexoDefaultServer.isSameServer(same)) throw new AssertionError("should match: " + same);
		}
		for (String other : new String[] {null, "", "nexomc.dev", "smp.nexomc.dev:25566", "smp.nexomc.dev.evil.example"}) {
			if (NexoDefaultServer.isSameServer(other)) throw new AssertionError("should not match: " + other);
		}
		System.out.println("default server: ok");
	}
}
