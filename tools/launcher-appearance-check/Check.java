package dev.nexoclient.nexomod.screen;

/**
 * NexoLauncherAppearance.parse against the launcher's appearance.txt format
 * (Client/crates/nexo-app/src/screens/settings.rs serialize/parse). Run with run.sh.
 */
public class Check {
	public static void main(String[] args) {
		var custom = NexoLauncherAppearance.parse("""
				theme=custom:1
				background=Nebula lightning
				particle_amount=150
				particle_speed=999
				reduce_motion=false
				custom=Off|#000000|#ffffff|#ff0000|#111111|#222222|#333333|#444444|First
				custom=Nebula lightning|#0a0a0d|#f2f2f5|#b14cff|#ff3cac|#9a6bff|#22b8ff|#3cffb0|My theme 1
				""");
		check(custom.customColors() != null && custom.customColors()[2] == 0xFFB14CFF, "custom:1 picks the second custom theme");
		check(custom.effect() == NexoBackdrop.Effect.LIGHTNING, "background by label");
		check(custom.amount() == 150 && custom.speed() == 200, "percent clamped to 25-200");

		var builtIn = NexoLauncherAppearance.parse("theme=Nebula Grid\nbackground=Liquid chrome\n");
		check(builtIn.customColors() == null && builtIn.theme() == NexoStyle.Theme.NEBULA_GRID, "built-in theme by name");
		check(builtIn.effect() == NexoBackdrop.Effect.CHROME, "chrome label");
		check(builtIn.amount() == 100 && builtIn.speed() == 100, "defaults");

		var damaged = NexoLauncherAppearance.parse("theme=custom:9\nbackground=Gone\ncustom=Snow|#nope\n\u0000\n");
		check(damaged.customColors() == null && damaged.theme() == NexoStyle.Theme.NEXO, "a missing custom falls back to Nexo");
		check(damaged.effect() == null, "unknown background is no background");
		System.out.println("launcher-appearance-check: ok");
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
	}
}
