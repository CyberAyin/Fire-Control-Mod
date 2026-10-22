package com.firecontrol.world;

import com.firecontrol.FireControl;
import com.firecontrol.compat.Compat;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntFunction;
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
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Ground height at an X Z: the real blocks when loaded, otherwise the terrain generator (the singleplayer
// world's, or vanilla's with the server seed, the same seed in every dimension). Worked out on a background
// thread and cached. The Nether has several floors under its bedrock roof; the one nearest a given height is used.
public final class GroundHeight {
	private static final Logger LOG = LoggerFactory.getLogger("firecontrol");
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "FireControl ground height");
		t.setDaemon(true);
		return t;
	});
	public static final List<String> PRESETS = List.of("overworld", "large_biomes", "amplified");
	// a shot coming down from the sky: the top floor
	public static final int SKY = Integer.MAX_VALUE;
	private static final int MATCH_BLOCKS = 1;
	// between the lava sea and where the roof slopes in, the Nether's shape depends on the seed alone
	private static final int NETHER_CHECK_MIN = 32, NETHER_CHECK_MAX = 100;
	// blocks you can stand on
	private static final Predicate<BlockState> SOLID = Heightmap.Types.OCEAN_FLOOR.isOpaque();

	private static final Map<String, Terrain> terrains = new ConcurrentHashMap<>();
	private static final Map<Key, Column> columns = new ConcurrentHashMap<>();
	private static final Map<Key, List<Consumer<Column>>> waiting = new ConcurrentHashMap<>();
	private static volatile HolderLookup.Provider worldgen;
	// test hook: use the typed seed even in singleplayer
	public static boolean seedEvenInSingleplayer;

	private GroundHeight() {
	}

	public enum Source {
		LOADED("top block", "Nether floor"),
		WORLD("ground from the world", "Nether floor from the world"),
		SEED("ground from the seed", "Nether floor from the seed");

		public final String label, netherLabel;

		Source(String label, String netherLabel) {
			this.label = label;
			this.netherLabel = netherLabel;
		}
	}

	public enum Status {
		READY, WORKING, NONE
	}

	// y = first free block above the ground
	public record Answer(Status status, int y, Source source, String label, String why) {
		static final Answer WORKING = new Answer(Status.WORKING, 0, null, null, null);

		static Answer none(String why) {
			return new Answer(Status.NONE, 0, null, null, why);
		}

		public boolean ready() {
			return status == Status.READY;
		}
	}

	private record Key(String terrain, int x, int z) {
	}

	// floors of one column, top down (first free block above each). roof: top of the Nether's bedrock roof, else SKY.
	private record Column(int[] floors, Source source, int roof, String why) {
		static Column none(String why) {
			return new Column(new int[0], null, SKY, why);
		}

		Answer pick(int nearY) {
			if (floors.length == 0) return Answer.none(why);
			int best = floors[0];
			for (int f : floors) if (Math.abs((long) f - nearY) < Math.abs((long) best - nearY)) best = f;
			String label = roof == SKY ? source.label : best >= roof ? "Nether roof" : source.netherLabel;
			return new Answer(Status.READY, best, source, label, null);
		}
	}

	private record Terrain(Source source, ChunkGenerator generator, RandomState random, LevelHeightAccessor heights, boolean nether) {
		int height(int x, int z, Heightmap.Types type) {
			return generator.getBaseHeight(x, z, type, heights, random);
		}

		// top of the generated terrain; the bedrock roof is its last block
		int roof() {
			if (generator instanceof NoiseBasedChunkGenerator g) {
				NoiseSettings n = g.generatorSettings().value().noiseSettings().clampToHeightAccessor(heights);
				return n.minY() + n.height();
			}
			return heights.getMinY() + heights.getHeight();
		}
	}

	private record Spec(String id, Source source, java.util.function.Supplier<Terrain> build) {
	}

	// where a cannon's shot comes down: a pearl cannon in the Nether lands near its own height
	// (overworld coordinates divided by 8), anything else comes from the sky
	private record Spot(int x, int z, int nearY) {
		static Spot of(Pairing p, int x, int z, String coordsDimension) {
			boolean convert = p.isPearl() && p.converts(coordsDimension);
			return new Spot(convert ? Math.floorDiv(x, 8) : x, convert ? Math.floorDiv(z, 8) : z, p.isPearl() ? p.originY : SKY);
		}
	}

	public static Answer forTarget(Pairing p, int x, int z, String coordsDimension) {
		Spot s = Spot.of(p, x, z, coordsDimension);
		return at(p.dimension, s.x(), s.z(), s.nearY());
	}

	public static void whenReady(Pairing p, int x, int z, String coordsDimension, Consumer<Answer> then) {
		Spot s = Spot.of(p, x, z, coordsDimension);
		whenReady(p.dimension, s.x(), s.z(), s.nearY(), then);
	}

	public static Answer at(String dim, int x, int z, int nearY) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null && dim.equals(FireControl.dimensionId(level)) && BlockScan.loaded(level, x >> 4, z >> 4)) {
			return loadedColumn(level, x, z, Pairing.NETHER.equals(dim)).pick(nearY);
		}
		Object spec = spec(dim);
		if (spec instanceof String why) return Answer.none(why);
		Spec s = (Spec) spec;
		Key key = new Key(s.id(), x, z);
		Column known = columns.get(key);
		if (known != null) return known.pick(nearY);
		if (waiting.putIfAbsent(key, Collections.synchronizedList(new ArrayList<>())) == null) {
			WORKER.execute(() -> {
				Column c;
				try {
					c = generatedColumn(terrain(s), x, z);
				} catch (Throwable e) {
					LOG.error("[FireControl] Couldn't work out the ground at {} {}", x, z, e);
					c = Column.none("couldn't work it out (see the log)");
				}
				columns.put(key, c);
				Column done = c;
				Minecraft.getInstance().execute(() -> {
					List<Consumer<Column>> callbacks = waiting.remove(key);
					if (callbacks != null) callbacks.forEach(cb -> cb.accept(done));
				});
			});
		}
		return Answer.WORKING;
	}

	public static void whenReady(String dim, int x, int z, int nearY, Consumer<Answer> then) {
		Answer a = at(dim, x, z, nearY);
		if (a.status() != Status.WORKING) {
			then.accept(a);
			return;
		}
		Object spec = spec(dim);
		List<Consumer<Column>> list = spec instanceof Spec s ? waiting.get(new Key(s.id(), x, z)) : null;
		if (list != null) list.add(c -> then.accept(c.pick(nearY)));
		else then.accept(at(dim, x, z, nearY)); // finished in the meantime
	}

	private static Column loadedColumn(ClientLevel level, int x, int z, boolean nether) {
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		if (!nether) return new Column(new int[] {top}, Source.LOADED, SKY, null);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		return floors(y -> level.getBlockState(p.set(x, y, z)), level.getMinY(), top, Source.LOADED, level.getMinY() + level.dimensionType().logicalHeight());
	}

	private static Column generatedColumn(Terrain t, int x, int z) {
		if (!t.nether()) {
			int y = t.height(x, z, Heightmap.Types.WORLD_SURFACE_WG);
			return y <= t.heights().getMinY() ? Column.none("no ground there, only void") : new Column(new int[] {y}, t.source(), SKY, null);
		}
		NoiseColumn column = t.generator().getBaseColumn(x, z, t.heights(), t.random());
		int roof = t.roof();
		BlockState bedrock = Blocks.BEDROCK.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		return floors(y -> y == roof - 1 ? bedrock : y >= roof ? air : column.getBlock(y), t.heights().getMinY(), roof, t.source(), roof);
	}

	// first free block above each floor, top down: something solid underneath, two free blocks (no lava) on top
	private static Column floors(IntFunction<BlockState> block, int bottom, int top, Source source, int roof) {
		int n = top + 2 - bottom;
		boolean[] solid = new boolean[Math.max(n, 0)], free = new boolean[Math.max(n, 0)];
		for (int i = 0; i < n; i++) {
			BlockState s = block.apply(bottom + i);
			solid[i] = SOLID.test(s);
			free[i] = !solid[i] && s.getFluidState().isEmpty();
		}
		List<Integer> out = new ArrayList<>();
		for (int i = n - 2; i >= 1; i--) if (solid[i - 1] && free[i] && free[i + 1]) out.add(bottom + i);
		if (out.isEmpty()) return Column.none("no floor there");
		return new Column(out.stream().mapToInt(Integer::intValue).toArray(), source, roof, null);
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
		columns.clear();
	}

	public static void onLevelChange() {
		terrains.keySet().removeIf(id -> id.startsWith("world:"));
		columns.clear();
	}

	private static Object spec(String dim) {
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer server = mc.getSingleplayerServer();
		boolean nether = Pairing.NETHER.equals(dim);
		if (server != null && !seedEvenInSingleplayer) {
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
			if (level == null) return "unknown dimension";
			ChunkGenerator gen = level.getChunkSource().getGenerator();
			RandomState random = level.getChunkSource().randomState();
			return new Spec("world:" + Store.worldKey() + ":" + dim, Source.WORLD, () -> new Terrain(Source.WORLD, gen, random, level, nether));
		}
		Store.Seed seed = Store.seed();
		OptionalLong value = seed == null ? OptionalLong.empty() : parseSeed(seed.text);
		if (value.isEmpty()) return "no world seed set in More…";
		String preset = switch (dim) {
			case "minecraft:overworld" -> seed.terrain != null && PRESETS.contains(seed.terrain) ? seed.terrain : "overworld";
			case "minecraft:the_nether" -> "nether";
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
		return new Terrain(Source.SEED, Compat.noiseGenerator(settings), Compat.randomState(lookup, key, seed),
				LevelHeightAccessor.create(noise.minY(), noise.height()), preset.equals("nether"));
	}

	private static HolderLookup.Provider worldgen() {
		if (worldgen == null) worldgen = Compat.worldgenRegistries();
		return worldgen;
	}

	// preset: the overworld terrain it matched, null for the other dimensions
	public record Check(Boolean match, String preset, String message) {
	}

	// Compares the seed's terrain with the blocks loaded around you, in whichever dimension you're in
	// (each overworld preset in turn).
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
		boolean nether = Pairing.NETHER.equals(dim);
		if (presets.isEmpty() && !nether) {
			done.accept(new Check(null, null, "Saved. It's checked in the Overworld, the Nether or the End."));
			return;
		}
		int radius = Math.min(12, mc.options.getEffectiveRenderDistance());
		List<int[]> samples = nether ? sampleNether(level, mc.player.blockPosition(), radius) : sampleGround(level, mc.player.blockPosition(), radius);
		if (samples.size() < 16) {
			done.accept(new Check(null, null, "Saved, but too little " + (nether ? "of the Nether" : "ground") + " is loaded to check it."));
			return;
		}
		long value = seed.getAsLong();
		WORKER.execute(() -> {
			Check result;
			try {
				result = nether ? compareNether(value, samples) : compare(value, presets, samples);
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
				return new Check(true, PRESETS.contains(preset) ? preset : null, "✔ Seed matches the ground around you" + kind + ".");
			}
		}
		if (bestPercent >= 25) {
			// not enough matches to tell either way
			return new Check(null, null, "Only " + bestPercent + "% of the ground fits: retry away from builds.");
		}
		return new Check(false, null, "✖ Doesn't fit the ground here: a typo, or custom terrain?");
	}

	// Solid or open, block by block. Cohen's kappa is about 0 for a wrong seed however much of the Nether is solid.
	private static Check compareNether(long seed, List<int[]> samples) {
		Terrain t = terrain(seedSpec(seed, "nether"));
		long both = 0, realOnly = 0, seedOnly = 0, neither = 0;
		for (int[] s : samples) {
			NoiseColumn column = t.generator().getBaseColumn(s[0], s[1], t.heights(), t.random());
			for (int i = 2; i < s.length; i++) {
				boolean real = s[i] != 0, fromSeed = SOLID.test(column.getBlock(NETHER_CHECK_MIN + i - 2));
				if (real && fromSeed) both++;
				else if (real) realOnly++;
				else if (fromSeed) seedOnly++;
				else neither++;
			}
		}
		double n = both + realOnly + seedOnly + neither;
		double agree = (both + neither) / n, real = (both + realOnly) / n, fromSeed = (both + seedOnly) / n;
		double chance = real * fromSeed + (1 - real) * (1 - fromSeed);
		double kappa = chance >= 1 ? 0 : (agree - chance) / (1 - chance);
		int percent = (int) Math.round(100 * agree);
		LOG.info("[FireControl] Seed check {} nether: {}% of {} blocks agree, kappa {}", seed, percent, (long) n, String.format(Locale.ROOT, "%.2f", kappa));
		if (kappa >= 0.5) return new Check(true, null, "✔ Seed matches the Nether around you.");
		if (kappa >= 0.25) return new Check(null, null, "Only " + percent + "% of the Nether here fits: retry away from builds.");
		return new Check(false, null, "✖ Doesn't fit the Nether here: a typo, or custom terrain?");
	}

	// one spot per loaded chunk, up to 96
	private static List<int[]> spots(ClientLevel level, BlockPos around, int radius) {
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
			out.add(new int[] {x, z});
		}
		return out;
	}

	private static List<int[]> sampleGround(ClientLevel level, BlockPos around, int radius) {
		List<int[]> out = new ArrayList<>();
		for (int[] s : spots(level, around, radius)) {
			int y = bareGround(level, s[0], s[1]);
			if (y != Integer.MIN_VALUE) out.add(new int[] {s[0], s[1], y});
		}
		return out;
	}

	// x, z, then 1 (solid) or 0 for each block from NETHER_CHECK_MIN up
	private static List<int[]> sampleNether(ClientLevel level, BlockPos around, int radius) {
		List<int[]> out = new ArrayList<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int[] s : spots(level, around, radius)) {
			int[] sample = new int[2 + NETHER_CHECK_MAX - NETHER_CHECK_MIN + 1];
			sample[0] = s[0];
			sample[1] = s[1];
			for (int i = 2; i < sample.length; i++) sample[i] = SOLID.test(level.getBlockState(p.set(s[0], NETHER_CHECK_MIN + i - 2, s[1]))) ? 1 : 0;
			out.add(sample);
		}
		return out;
	}

	// MIN_VALUE when there's too much on top (a build)
	private static int bareGround(Level level, int x, int z) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
		for (int i = 0; i < 64 && p.getY() >= level.getMinY(); i++, p.setY(p.getY() - 1)) {
			BlockState s = level.getBlockState(p);
			if (SOLID.test(s) && !grownOrPlaced(s)) return p.getY() + 1;
		}
		return Integer.MIN_VALUE;
	}

	private static boolean grownOrPlaced(BlockState s) {
		return s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(Blocks.ICE) || s.is(Blocks.CACTUS) || s.is(Blocks.BAMBOO)
				|| s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.MUSHROOM_STEM)
				|| s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON);
	}

	// for tests, run on the calling thread
	public static int seedHeight(long seed, String preset, int x, int z) {
		return terrain(seedSpec(seed, preset)).height(x, z, Heightmap.Types.WORLD_SURFACE_WG);
	}

	public static int[] seedNetherFloors(long seed, int x, int z) {
		return generatedColumn(terrain(seedSpec(seed, "nether")), x, z).floors();
	}

	public static int[] worldNetherFloors(ServerLevel level, int x, int z) {
		return generatedColumn(new Terrain(Source.WORLD, level.getChunkSource().getGenerator(), level.getChunkSource().randomState(), level, true), x, z).floors();
	}

	public static int[] loadedNetherFloors(ClientLevel level, int x, int z) {
		return loadedColumn(level, x, z, true).floors();
	}
}
