import dev.nexoclient.nexomod.title.NexoWindowTitle;

public class WindowTitleCheck {
	static void same(String got, String want) {
		if (!java.util.Objects.equals(want, got)) throw new AssertionError("got [" + got + "], want [" + want + "]");
	}

	public static void main(String[] args) {
		same(NexoWindowTitle.title("26.3", null), "Nexo Client 26.3");
		same(NexoWindowTitle.title("26.3", "  "), "Nexo Client 26.3");
		same(NexoWindowTitle.title("26.3", "Hugosmp"), "Nexo Client 26.3 Hugosmp");
		same(NexoWindowTitle.title("26.3", null), "Nexo Client 26.3");
		same(NexoWindowTitle.title("26.3", " a\nb\u202e\u00a7cd "), "Nexo Client 26.3 abd");
		same(NexoWindowTitle.title("26.3", "\u00a7a"), "Nexo Client 26.3");
		same(NexoWindowTitle.title("26.3", "x".repeat(70000)), "Nexo Client 26.3 " + "x".repeat(64));
		same(NexoWindowTitle.title("26.3", "\ud83d\ude00".repeat(70)), "Nexo Client 26.3 " + "\ud83d\ude00".repeat(64));
		same(NexoWindowTitle.plainTitle("26.3"), "Minecraft 26.3");
		same(NexoWindowTitle.serverPlace("Hugosmp", "play.example", "Minecraft Server"), "Hugosmp");
		// A server without a name of its own stays out: its address could be someone's home.
		same(NexoWindowTitle.serverPlace("Minecraft Server", "1.2.3.4", "Minecraft Server"), null);
		same(NexoWindowTitle.serverPlace("", "1.2.3.4", "Minecraft Server"), null);
		same(NexoWindowTitle.serverPlace(null, "1.2.3.4", "Minecraft Server"), null);
		same(NexoWindowTitle.serverPlace("1.2.3.4", "1.2.3.4", "Minecraft Server"), null);
		System.out.println("window title: ok");
	}
}
