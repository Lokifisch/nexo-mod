package kaptainwutax.seedcrackerX.api;

/**
 * Compile-time stand-in for SeedcrackerX's own entrypoint interface (MIT,
 * https://github.com/19MisterX98/SeedcrackerX), matching the 2.16.0 jar.
 * Compiled into {@code seedcrackerApi} only, never packaged: at runtime the
 * real class comes from SeedcrackerX, and shipping this one would put two
 * copies of the interface on the classpath.
 */
public interface SeedCrackerAPI {
	void pushWorldSeed(long seed);
}
