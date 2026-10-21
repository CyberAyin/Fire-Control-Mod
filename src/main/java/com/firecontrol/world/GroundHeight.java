package com.firecontrol.world;

import com.firecontrol.FireControl;
import com.firecontrol.compat.Compat;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Ground height at an X Z: the real blocks when loaded, otherwise the terrain generator (the singleplayer
// world's, or vanilla's with the server seed). Worked out on a background thread and cached. No answer in the Nether.
public final class GroundHeight {
	private static final Logger LOG = LoggerFactory.getLogger("firecontrol");
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "FireControl ground height");
		t.setDaemon(true);
		return t;
	});
	public static final List<String> PRESETS = List.of("overworld", "large_biomes", "amplified");
	private static final int MATCH_BLOCKS = 1;

	private static final Map<String, Terrain> terrains = new ConcurrentHashMap<>();
	private static final Map<Key, Answer> answers = new ConcurrentHashMap<>();
	private static final Map<Key, List<Consumer<Answer>>> waiting = new ConcurrentHashMap<>();
	private static volatile HolderLookup.Provider worldgen;
	// test hook: use the typed seed even in singleplayer
	public static boolean seedEvenInSingleplayer;

	private GroundHeight() {
	}

	public enum Source {
		LOADED("top block"),
		WORLD("ground from the world"),
		SEED("ground from the seed");

		public final String label;

		Source(String label) {
			this.label = label;
		}
	}

	public enum Status {
		READY, WORKING, NONE
	}

	// y = first free block above the ground
	public record Answer(Status status, int y, Source source, String why) {
		static final Answer WORKING = new Answer(Status.WORKING, 0, null, null);

		static Answer none(String why) {
			return new Answer(Status.NONE, 0, null, why);
		}

		public boolean ready() {
			return status == Status.READY;
		}
	}

	private record Key(String terrain, int x, int z) {
	}

	private record Terrain(Source source, ChunkGenerator generator, RandomState random, LevelHeightAccessor heights) {
		int height(int x, int z, Heightmap.Types type) {
			return generator.getBaseHeight(x, z, type, heights, random);
		}
	}

	private record Spec(String id, Source source, java.util.function.Supplier<Terrain> build) {
	}

	public static Answer at(String dim, int x, int z) {
		if (Pairing.NETHER.equals(dim)) return Answer.none("the Nether has several floors");
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null && dim.equals(FireControl.dimensionId(level)) && BlockScan.loaded(level, x >> 4, z >> 4)) {
			return new Answer(Status.READY, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), Source.LOADED, null);
		}
		Object spec = spec(dim);
		if (spec instanceof String why) return Answer.none(why);
		Spec s = (Spec) spec;
		Key key = new Key(s.id(), x, z);
		Answer known = answers.get(key);
		if (known != null) return known;
		if (waiting.putIfAbsent(key, Collections.synchronizedList(new ArrayList<>())) == null) {
			WORKER.execute(() -> {
				Answer a;
				try {
					Terrain t = terrain(s);
					int y = t.height(x, z, Heightmap.Types.WORLD_SURFACE_WG);
					a = y <= t.heights().getMinY() ? Answer.none("no ground there, only void") : new Answer(Status.READY, y, t.source(), null);
				} catch (Throwable e) {
					LOG.error("[FireControl] Couldn't work out the ground at {} {}", x, z, e);
					a = Answer.none("couldn't work it out (see the log)");
				}
				answers.put(key, a);
				Answer done = a;
				Minecraft.getInstance().execute(() -> {
					List<Consumer<Answer>> callbacks = waiting.remove(key);
					if (callbacks != null) callbacks.forEach(c -> c.accept(done));
				});
			});
		}
		return Answer.WORKING;
	}

	public static void whenReady(String dim, int x, int z, Consumer<Answer> then) {
		Answer a = at(dim, x, z);
		if (a.status() != Status.WORKING) {
			then.accept(a);
			return;
		}
		Object spec = spec(dim);
		List<Consumer<Answer>> list = spec instanceof Spec s ? waiting.get(new Key(s.id(), x, z)) : null;
		if (list != null) list.add(then);
		else then.accept(at(dim, x, z)); // finished in the meantime
	}

	public static void warmUp() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		if (spec(FireControl.dimensionId(level)) instanceof Spec s) {
			WORKER.execute(() -> {
				try {
					terrain(s);
				} catch (Throwable e) {
					LOG.error("[FireControl] Couldn't set up the terrain generator", e);
				}
			});
		}
	}

	public static void forget() {
		answers.clear();
	}

	public static void onLevelChange() {
		terrains.keySet().removeIf(id -> id.startsWith("world:"));
		answers.clear();
	}

	private static Object spec(String dim) {
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer server = mc.getSingleplayerServer();
		if (server != null && !seedEvenInSingleplayer) {
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
			if (level == null) return "unknown dimension";
			ChunkGenerator gen = level.getChunkSource().getGenerator();
			RandomState random = level.getChunkSource().randomState();
			return new Spec("world:" + Store.worldKey() + ":" + dim, Source.WORLD, () -> new Terrain(Source.WORLD, gen, random, level));
		}
		Store.Seed seed = Store.seed();
		OptionalLong value = seed == null ? OptionalLong.empty() : parseSeed(seed.text);
		if (value.isEmpty()) return "no world seed set in More…";
		String preset = switch (dim) {
			case "minecraft:overworld" -> PRESETS.contains(seed.terrain) ? seed.terrain : "overworld";
			case "minecraft:the_end" -> "end";
			default -> null;
		};
		if (preset == null) return "not worked out in " + dim;
		return seedSpec(value.getAsLong(), preset);
	}

	private static Spec seedSpec(long seed, String preset) {
		return new Spec("seed:" + seed + ":" + preset, Source.SEED, () -> seedTerrain(seed, preset));
	}

	private static Terrain terrain(Spec s) {
		return terrains.computeIfAbsent(s.id(), k -> {
			long start = System.nanoTime();
			Terrain t = s.build().get();
			LOG.info("[FireControl] Terrain generator {} ready in {} ms", s.id(), (System.nanoTime() - start) / 1_000_000);
			return t;
		});
	}

	// numbers as is, text hashed like the Create World screen does
	public static OptionalLong parseSeed(String text) {
		return text == null ? OptionalLong.empty() : WorldOptions.parseSeed(text);
	}

	private static Terrain seedTerrain(long seed, String preset) {
		HolderLookup.Provider lookup = worldgen();
		ResourceKey<NoiseGeneratorSettings> key = ResourceKey.create(Registries.NOISE_SETTINGS, Identifier.withDefaultNamespace(preset));
		Holder<NoiseGeneratorSettings> settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key);
		NoiseSettings noise = settings.value().noiseSettings();
		return new Terrain(Source.SEED, Compat.noiseGenerator(settings), Compat.randomState(lookup, key, seed), LevelHeightAccessor.create(noise.minY(), noise.height()));
	}

	private static HolderLookup.Provider worldgen() {
		if (worldgen == null) worldgen = Compat.worldgenRegistries();
		return worldgen;
	}

	public record Check(Boolean match, String preset, String message) {
	}

	// Compares the bare ground the seed gives with the loaded blocks at up to 96 spots, for each overworld preset.
	public static void checkSeed(String seedText, Consumer<Check> done) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		OptionalLong seed = parseSeed(seedText);
		if (level == null || mc.player == null || seed.isEmpty()) {
			done.accept(new Check(null, null, ""));
			return;
		}
		String dim = FireControl.dimensionId(level);
		List<String> presets = switch (dim) {
			case "minecraft:overworld" -> PRESETS;
			case "minecraft:the_end" -> List.of("end");
			default -> List.of();
		};
		if (presets.isEmpty()) {
			done.accept(new Check(null, null, "Saved. It's checked in the Overworld."));
			return;
		}
		List<int[]> samples = sampleGround(level, mc.player.blockPosition(), Math.min(12, mc.options.getEffectiveRenderDistance()));
		if (samples.size() < 16) {
			done.accept(new Check(null, null, "Saved, but too little ground is loaded to check it."));
			return;
		}
		long value = seed.getAsLong();
		WORKER.execute(() -> {
			Check result;
			try {
				result = compare(value, presets, samples);
			} catch (Throwable e) {
				LOG.error("[FireControl] Seed check failed", e);
				result = new Check(null, null, "Couldn't check the seed (see the log).");
			}
			Check r = result;
			mc.execute(() -> done.accept(r));
		});
	}

	private static Check compare(long seed, List<String> presets, List<int[]> samples) {
		int bestPercent = -1;
		String best = null;
		for (String preset : presets) {
			Terrain t = terrain(seedSpec(seed, preset));
			int matches = 0, tried = 0;
			for (int[] s : samples) {
				tried++;
				if (Math.abs(t.height(s[0], s[1], Heightmap.Types.OCEAN_FLOOR_WG) - s[2]) <= MATCH_BLOCKS) matches++;
				// a wrong preset shows after a few dozen spots, don't spend seconds on it
				if (tried == 32 && matches < 8) break;
			}
			int percent = Math.round(100f * matches / tried);
			LOG.info("[FireControl] Seed check {} {}: {}/{} spots match", seed, preset, matches, tried);
			if (percent > bestPercent) {
				bestPercent = percent;
				best = preset;
			}
			if (percent >= 50) {
				String kind = preset.equals("large_biomes") ? " (Large Biomes world)" : preset.equals("amplified") ? " (Amplified world)" : "";
				return new Check(true, preset, "✔ Seed matches the ground around you" + kind + ".");
			}
		}
		if (bestPercent >= 25) {
			// not enough matches to tell either way
			return new Check(null, best, "Only " + bestPercent + "% of the ground fits: retry away from builds.");
		}
		return new Check(false, null, "✖ Doesn't fit the ground here: a typo, or custom terrain?");
	}

	private static List<int[]> sampleGround(ClientLevel level, BlockPos around, int radius) {
		List<long[]> chunks = new ArrayList<>();
		int pcx = around.getX() >> 4, pcz = around.getZ() >> 4;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (BlockScan.loaded(level, pcx + dx, pcz + dz)) chunks.add(new long[] {pcx + dx, pcz + dz});
			}
		}
		Collections.shuffle(chunks, new Random(around.asLong()));
		List<int[]> out = new ArrayList<>();
		for (long[] c : chunks) {
			if (out.size() >= 96) break;
			int x = (int) c[0] * 16 + (int) Math.floorMod(c[0] * 7 + c[1] * 3, 16L);
			int z = (int) c[1] * 16 + (int) Math.floorMod(c[0] * 5 + c[1] * 11, 16L);
			int y = bareGround(level, x, z);
			if (y != Integer.MIN_VALUE) out.add(new int[] {x, z, y});
		}
		return out;
	}

	// MIN_VALUE when there's too much on top (a build)
	private static int bareGround(Level level, int x, int z) {
		Predicate<BlockState> solid = Heightmap.Types.OCEAN_FLOOR.isOpaque();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
		for (int i = 0; i < 64 && p.getY() >= level.getMinY(); i++, p.setY(p.getY() - 1)) {
			BlockState s = level.getBlockState(p);
			if (solid.test(s) && !grownOrPlaced(s)) return p.getY() + 1;
		}
		return Integer.MIN_VALUE;
	}

	private static boolean grownOrPlaced(BlockState s) {
		return s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(Blocks.ICE) || s.is(Blocks.CACTUS) || s.is(Blocks.BAMBOO)
				|| s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.MUSHROOM_STEM)
				|| s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON);
	}

	// for tests, runs on the calling thread
	public static int seedHeight(long seed, String preset, int x, int z) {
		return terrain(seedSpec(seed, preset)).height(x, z, Heightmap.Types.WORLD_SURFACE_WG);
	}
}
