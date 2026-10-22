package com.firecontrol.e2e;

import com.firecontrol.Actions;
import com.firecontrol.FireControl;
import com.firecontrol.calc.CannonType;
import com.firecontrol.calc.FireMode;
import com.firecontrol.compat.Compat;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import com.firecontrol.gui.FireControlScreen;
import com.firecontrol.gui.MoreScreen;
import com.firecontrol.world.BlockScan;
import com.firecontrol.world.GroundHeight;
import com.firecontrol.world.PearlLayout;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.FileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Test for targets without a Y, dev only. Run with -Dfirecontrol.ground=true
public class GroundE2E implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("firecontrol-ground-e2e");
	private static final long SEED = 8675309L;

	private final List<Step> steps = new ArrayList<>();
	private int stepIndex = -1, ticksInStep, passes, fails;
	private Pairing preset;
	private GroundHeight.Check check;
	private GroundHeight.Answer answer;
	private int expected;

	private record Step(String name, BiFunction<Minecraft, Integer, Boolean> run) {
	}

	@Override
	public void onInitializeClient() {
		if (!Boolean.getBoolean("firecontrol.ground")) return;
		build();
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(Minecraft mc) {
		if (stepIndex == -1) {
			if (Compat.screen(mc) instanceof TitleScreen || Compat.screen(mc) instanceof AccessibilityOnboardingScreen) {
				mc.options.onboardAccessibility = false;
				mc.options.pauseOnLostFocus = false;
				mc.options.save();
				createWorld(mc);
				stepIndex = 0;
			}
			return;
		}
		if (stepIndex >= steps.size()) return;
		Step step = steps.get(stepIndex);
		boolean done;
		try {
			done = step.run().apply(mc, ticksInStep++);
		} catch (Throwable t) {
			LOG.error("[GROUND] step '{}' crashed", step.name(), t);
			fail(step.name() + " crashed: " + t);
			done = true;
		}
		if (done) {
			stepIndex++;
			ticksInStep = 0;
			if (stepIndex < steps.size()) LOG.info("[GROUND] >> {}", steps.get(stepIndex).name());
		}
	}

	private void step(String name, BiFunction<Minecraft, Integer, Boolean> run) {
		steps.add(new Step(name, run));
	}

	private void build() {
		step("wait for world and chunks", (mc, t) -> mc.level != null && mc.player != null && mc.getSingleplayerServer() != null && t > 300);

		step("seed alone gives the world's own heights", (mc, t) -> {
			ServerLevel level = mc.getSingleplayerServer().overworld();
			Random r = new Random(7);
			int same = 0, n = 300;
			String firstDiff = "";
			long start = System.nanoTime();
			GroundHeight.seedHeight(SEED, "overworld", 0, 0);
			long built = System.nanoTime();
			for (int i = 0; i < n; i++) {
				int x = r.nextInt(60000) - 30000, z = r.nextInt(60000) - 30000;
				int fromSeed = GroundHeight.seedHeight(SEED, "overworld", x, z);
				int real = worldHeight(level, x, z);
				if (fromSeed == real) same++;
				else if (firstDiff.isEmpty()) firstDiff = x + " " + z + ": seed " + fromSeed + ", world " + real;
			}
			long end = System.nanoTime();
			LOG.info("[GROUND] generator from the seed built in {} ms; {} columns in {} ms", (built - start) / 1_000_000, n, (end - built) / 1_000_000);
			check("seed heights equal the world's (" + same + "/" + n + ")", same == n, firstDiff);
			check("large biomes differ", GroundHeight.seedHeight(SEED, "large_biomes", 1234, 5678) != worldHeight(level, 1234, 5678)
					|| GroundHeight.seedHeight(SEED, "large_biomes", -20000, 9000) != worldHeight(level, -20000, 9000), "");
			return true;
		});

		step("seed check accepts the right seed", (mc, t) -> {
			if (t == 0) {
				check = null;
				GroundHeight.checkSeed(String.valueOf(SEED), c -> check = c);
			}
			if (check == null) return t > 600 && fail("no answer from the seed check");
			check("right seed matches as a normal world", Boolean.TRUE.equals(check.match()) && "overworld".equals(check.preset()), check.toString());
			return true;
		});

		step("seed check rejects a wrong seed", (mc, t) -> {
			if (t == 0) {
				check = null;
				GroundHeight.checkSeed("not the seed", c -> check = c);
			}
			if (check == null) return t > 600 && fail("no answer from the seed check");
			check("wrong seed doesn't match", !Boolean.TRUE.equals(check.match()), check.toString());
			return true;
		});

		step("a cannon preset", (mc, t) -> {
			Store.pairings().clear();
			preset = new Pairing();
			preset.name = "ground-test";
			preset.dimension = "minecraft:overworld";
			preset.setCannon(CannonType.MS, new BlockPos(0, 160, 0), 940, "manual");
			Store.pairings().add(preset);
			Actions.select(preset);
			return true;
		});

		step("target x z nearby: the top block there", (mc, t) -> {
			BlockPos p = mc.player.blockPosition().offset(24, 0, -18);
			if (t == 0) {
				expected = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ());
				client(mc, "firecontrol target " + p.getX() + " " + p.getZ());
				return false;
			}
			if (t < 5) return false;
			check("nearby Y is the top block (" + expected + ")", preset.targetX == p.getX() && preset.targetZ == p.getZ() && preset.targetY == expected && preset.autoY,
					preset.targetX + " " + preset.targetY + " " + preset.targetZ + " auto " + preset.autoY);
			return true;
		});

		step("target x z far away: the world's terrain", (mc, t) -> {
			if (t == 0) {
				expected = worldHeight(mc.getSingleplayerServer().overworld(), 14000, -9000);
				client(mc, "firecontrol target 14000 -9000 stab 40");
				return false;
			}
			if (preset.targetX != 14000 && t < 200) return false;
			check("far Y is the world's ground (" + expected + ")", preset.targetX == 14000 && preset.targetZ == -9000 && preset.targetY == expected && preset.autoY,
					preset.targetX + " " + preset.targetY + " " + preset.targetZ);
			check("payload after x z", FireMode.STAB.id.equals(preset.mode) && preset.stabDepth == 40, preset.mode + " " + preset.stabDepth);
			return true;
		});

		step("as on a server, without a seed: asks for the Y", (mc, t) -> {
			if (t == 0) {
				GroundHeight.seedEvenInSingleplayer = true;
				Store.setSeed("");
				GroundHeight.forget();
				client(mc, "firecontrol target 15000 -9100");
				return false;
			}
			if (t < 60) return false;
			check("no seed: target unchanged", preset.targetX == 14000, preset.targetX + " " + preset.targetZ);
			return true;
		});

		step("as on a server, with the seed: Y from the seed", (mc, t) -> {
			if (t == 0) {
				Store.setSeed(String.valueOf(SEED));
				GroundHeight.forget();
				expected = worldHeight(mc.getSingleplayerServer().overworld(), 15000, -9100);
				client(mc, "firecontrol target 15000 -9100");
				return false;
			}
			if (preset.targetX != 15000 && t < 400) return false;
			check("Y from the seed (" + expected + ")", preset.targetX == 15000 && preset.targetY == expected && preset.autoY, preset.targetX + " " + preset.targetY);
			return true;
		});

		step("menu with Y left empty", (mc, t) -> {
			if (t == 0) {
				GroundHeight.seedEvenInSingleplayer = false;
				expected = worldHeight(mc.getSingleplayerServer().overworld(), -3000, 7000);
				Compat.setScreen(mc, new FireControlScreen());
				return false;
			}
			if (t == 2) {
				List<EditBox> boxes = Compat.screen(mc).children().stream().filter(c -> c instanceof EditBox).map(c -> (EditBox) c).toList();
				check("menu Y box starts empty (auto)", boxes.get(1).getValue().isEmpty(), "'" + boxes.get(1).getValue() + "'");
				boxes.get(0).setValue("-3000");
				boxes.get(2).setValue("7000");
				return false;
			}
			if (preset.targetX != -3000 && t < 400) return false;
			check("menu Y is the world's ground (" + expected + ")", preset.targetX == -3000 && preset.targetZ == 7000 && preset.targetY == expected && preset.autoY,
					preset.targetX + " " + preset.targetY + " " + preset.targetZ);
			return true;
		});

		step("screenshots of the menu and More…", (mc, t) -> {
			if (t == 10) shot(mc, "ground_menu");
			if (t == 12) Compat.setScreen(mc, new MoreScreen(Compat.screen(mc)));
			if (t == 25) shot(mc, "ground_more");
			if (t < 30) return false;
			Compat.setScreen(mc, null);
			return true;
		});

		step("to the Nether", (mc, t) -> {
			if (t == 0) {
				MinecraftServer server = mc.getSingleplayerServer();
				server.execute(() -> {
					run(server, "gamemode spectator @a");
					run(server, "execute in minecraft:the_nether run tp @a 0 70 0");
				});
				return false;
			}
			return t > 400 && mc.level != null && Pairing.NETHER.equals(FireControl.dimensionId(mc.level));
		});

		step("seed alone gives the world's own Nether floors", (mc, t) -> {
			ServerLevel nether = mc.getSingleplayerServer().getLevel(Level.NETHER);
			Random r = new Random(11);
			int same = 0, n = 300, floors = 0, several = 0, roofs = 0;
			String firstDiff = "";
			long start = System.nanoTime();
			for (int i = 0; i < n; i++) {
				int x = r.nextInt(60000) - 30000, z = r.nextInt(60000) - 30000;
				int[] fromSeed = GroundHeight.seedNetherFloors(SEED, x, z), world = GroundHeight.worldNetherFloors(nether, x, z);
				if (Arrays.equals(fromSeed, world)) same++;
				else if (firstDiff.isEmpty()) firstDiff = x + " " + z + ": seed " + Arrays.toString(fromSeed) + ", world " + Arrays.toString(world);
				floors += world.length;
				if (world.length > 2) several++;
				if (world.length > 0 && world[0] == 128) roofs++;
			}
			LOG.info("[GROUND] {} Nether columns in {} ms, {} floors, {} with several under the roof", n, (System.nanoTime() - start) / 1_000_000, floors, several);
			check("seed Nether floors equal the world's (" + same + "/" + n + ")", same == n, firstDiff);
			check("every column has the roof top (128) first", roofs == n, roofs + "/" + n);
			check("most columns have a floor under the roof", floors - roofs > n / 2, floors + " floors");
			return true;
		});

		// the real Nether also has carved tunnels, fungi, glowstone and basalt that the bare terrain doesn't
		step("Nether floors fit the loaded blocks", (mc, t) -> {
			BlockPos at = mc.player.blockPosition();
			int tried = 0, same = 0, real = 0, wrongReal = 0, roofs = 0, shown = 0;
			for (int dx = -96; dx <= 96; dx += 8) {
				for (int dz = -96; dz <= 96; dz += 8) {
					int x = at.getX() + dx, z = at.getZ() + dz;
					if (!BlockScan.loaded(mc.level, x >> 4, z >> 4)) continue;
					int[] loaded = GroundHeight.loadedNetherFloors(mc.level, x, z), fromSeed = GroundHeight.seedNetherFloors(SEED, x, z);
					tried++;
					if (loaded.length > 0 && loaded[0] == 128) roofs++;
					int s = nearest(fromSeed, 64), w = nearest(GroundHeight.seedNetherFloors(SEED + 1, x, z), 64);
					if (loaded.length > 0 && Math.abs(nearest(loaded, 64) - s) <= 1) same++;
					if (Math.abs(nearest(loaded, w) - w) <= 1) wrongReal++;
					if (Math.abs(nearest(loaded, s) - s) <= 1) real++;
					else if (shown++ < 10) LOG.info("[GROUND]   {} {} {}: seed {} loaded {}", x, z, mc.level.getBiome(new BlockPos(x, s, z)).getRegisteredName(),
							Arrays.toString(fromSeed), Arrays.toString(loaded));
				}
			}
			LOG.info("[GROUND] loaded Nether: the seed's floor nearest y 64 is a real floor in {}/{} columns (a wrong seed's in {}), the nearest one in {}",
					real, tried, wrongReal, same);
			check("loaded Nether columns (" + tried + ")", tried >= 50, "");
			check("loaded roof top is 128", roofs == tried, roofs + "/" + tried);
			// tunnels, basalt, fungi and lava aren't in the bare terrain, so not all of them
			check("the seed's floor near y 64 is mostly a real floor (" + real + "/" + tried + ", wrong seed " + wrongReal + ")",
					real * 100 >= tried * 70 && real - wrongReal >= tried / 4, "");
			return true;
		});

		step("Nether seed check accepts the right seed", (mc, t) -> {
			if (t == 0) {
				check = null;
				GroundHeight.checkSeed(String.valueOf(SEED), c -> check = c);
			}
			if (check == null) return t > 600 && fail("no answer from the Nether seed check");
			check("right seed matches the Nether", Boolean.TRUE.equals(check.match()) && check.preset() == null && check.message().contains("Nether"), check.toString());
			return true;
		});

		for (String wrong : new String[] {"not the seed", String.valueOf(SEED + 1)}) {
			step("Nether seed check rejects '" + wrong + "'", (mc, t) -> {
				if (t == 0) {
					check = null;
					GroundHeight.checkSeed(wrong, c -> check = c);
				}
				if (check == null) return t > 600 && fail("no answer from the Nether seed check");
				check("wrong seed '" + wrong + "' doesn't fit the Nether", Boolean.FALSE.equals(check.match()), check.toString());
				return true;
			});
		}

		step("Nether pearl cannon, x z far away: floor nearest the cannon, from the world", (mc, t) -> {
			ServerLevel nether = mc.getSingleplayerServer().getLevel(Level.NETHER);
			if (t == 0) {
				Store.pairings().clear();
				preset = new Pairing();
				preset.name = "nether-pearl";
				preset.dimension = Pairing.NETHER;
				preset.cannonType = CannonType.PEARL.id;
				preset.pearl = new PearlLayout(new BlockPos(0, 70, 0), 0, false);
				preset.originX = 0;
				preset.originY = 70;
				preset.originZ = 0;
				preset.passcodeSource = "default";
				Store.pairings().add(preset);
				Actions.select(preset);
				expected = nearest(GroundHeight.worldNetherFloors(nether, 3000, -2000), 70);
				client(mc, "firecontrol target 3000 -2000");
				return false;
			}
			if (preset.targetX != 3000 && t < 400) return false;
			check("nether pearl Y is the world's floor nearest the cannon (" + expected + ")", preset.targetX == 3000 && preset.targetZ == -2000 && preset.targetY == expected && preset.autoY,
					preset.targetX + " " + preset.targetY + " " + preset.targetZ + " floors " + Arrays.toString(GroundHeight.worldNetherFloors(nether, 3000, -2000)));
			return true;
		});

		step("Nether pearl cannon, x z far away: from the seed", (mc, t) -> {
			if (t == 0) {
				GroundHeight.seedEvenInSingleplayer = true;
				Store.setSeed(String.valueOf(SEED));
				GroundHeight.forget();
				expected = nearest(GroundHeight.seedNetherFloors(SEED, -7000, 4100), 70);
				client(mc, "firecontrol target -7000 4100");
				return false;
			}
			if (preset.targetX != -7000 && t < 400) return false;
			GroundHeight.Answer a = GroundHeight.forTarget(preset, -7000, 4100, Pairing.NETHER);
			check("nether pearl Y from the seed (" + expected + ")", preset.targetX == -7000 && preset.targetY == expected && preset.autoY, preset.targetX + " " + preset.targetY);
			check("labelled as a Nether floor or roof", a.ready() && a.label().startsWith("Nether"), String.valueOf(a));
			return true;
		});

		step("Nether pearl cannon, overworld coordinates: floor at x/8 z/8", (mc, t) -> {
			if (t == 0) {
				answer = null;
				expected = nearest(GroundHeight.seedNetherFloors(SEED, Math.floorDiv(-24001, 8), Math.floorDiv(16003, 8)), 70);
				GroundHeight.whenReady(preset, -24001, 16003, "minecraft:overworld", a -> answer = a);
			}
			if (answer == null) return t > 400 && fail("no answer for overworld coordinates");
			check("overworld coordinates use the Nether floor at x/8 z/8 (" + expected + ")", answer.ready() && answer.y() == expected, String.valueOf(answer));
			Actions.setPearlTarget(preset, new BlockPos(-24001, answer.y(), 16003), "minecraft:overworld");
			preset.autoY = true;
			check("lands on that floor", preset.netherConversion() && preset.pearlLandY() == expected - 1, String.valueOf(preset.pearlLandY()));
			preset.autoY = false;
			check("a typed overworld Y keeps the cannon floor", preset.pearlLandY() == 69, String.valueOf(preset.pearlLandY()));
			return true;
		});

		step("Nether pearl cannon, x z nearby: the loaded floor", (mc, t) -> {
			BlockPos p = mc.player.blockPosition().offset(20, 0, -12);
			if (t == 0) {
				expected = nearest(GroundHeight.loadedNetherFloors(mc.level, p.getX(), p.getZ()), 70);
				client(mc, "firecontrol target " + p.getX() + " " + p.getZ());
				return false;
			}
			if (t < 5) return false;
			check("nearby nether Y is the loaded floor nearest the cannon (" + expected + ")", preset.targetX == p.getX() && preset.targetY == expected && preset.autoY,
					preset.targetX + " " + preset.targetY + " floors " + Arrays.toString(GroundHeight.loadedNetherFloors(mc.level, p.getX(), p.getZ())));
			return true;
		});

		step("strike in the Nether, x z: the roof", (mc, t) -> {
			if (t == 0) {
				Store.pairings().clear();
				preset = new Pairing();
				preset.name = "nether-osc";
				preset.dimension = Pairing.NETHER;
				preset.setCannon(CannonType.MS, new BlockPos(0, 160, 0), 940, "manual");
				Store.pairings().add(preset);
				Actions.select(preset);
				client(mc, "firecontrol target 5000 7000");
				return false;
			}
			if (preset.targetX != 5000 && t < 400) return false;
			GroundHeight.Answer a = GroundHeight.forTarget(preset, 5000, 7000, Pairing.NETHER);
			check("nether strike Y is the roof top (128)", preset.targetX == 5000 && preset.targetY == 128 && preset.autoY, preset.targetX + " " + preset.targetY);
			check("labelled as the roof", "Nether roof".equals(a.label()), String.valueOf(a));
			GroundHeight.seedEvenInSingleplayer = false;
			return true;
		});

		step("finish", (mc, t) -> {
			LOG.info("[GROUND] RESULT: {} passed, {} failed", passes, fails);
			Store.pairings().clear();
			Store.setSeed("");
			mc.stop();
			return true;
		});
	}

	private static int worldHeight(ServerLevel level, int x, int z) {
		return level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
	}

	// floors are listed top down, so a tie goes to the higher one
	private static int nearest(int[] floors, int y) {
		int best = Integer.MIN_VALUE;
		for (int f : floors) if (best == Integer.MIN_VALUE || Math.abs(f - y) < Math.abs(best - y)) best = f;
		return best;
	}

	private static void run(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	private void check(String what, boolean ok, String detail) {
		if (ok) {
			passes++;
			LOG.info("[GROUND] PASS {}", what);
		} else {
			fail(what + " (" + detail + ")");
		}
	}

	private boolean fail(String what) {
		fails++;
		LOG.info("[GROUND] FAIL {}", what);
		return true;
	}

	private static void client(Minecraft mc, String command) {
		try {
			FabricClientCommandSource src = (FabricClientCommandSource) mc.getConnection().getSuggestionsProvider();
			Compat.activeDispatcher().execute(command, src);
		} catch (CommandSyntaxException e) {
			LOG.info("[GROUND] FAIL command '{}': {}", command, e.getMessage());
		}
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "e2e_" + name + ".png", Compat.mainRenderTarget(mc), 1, c -> LOG.info("[GROUND] screenshot {}", c.getString()));
	}

	private static void createWorld(Minecraft mc) {
		try {
			String folder = FileUtil.findAvailableName(mc.getLevelSource().getBaseDir(), "Ground E2E", "");
			LevelSettings settings = E2ECompat.levelSettings("Ground E2E");
			mc.createWorldOpenFlows().createFreshLevel(folder, settings, new WorldOptions(SEED, true, false),
					registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), Compat.screen(mc));
		} catch (Exception e) {
			LOG.error("[GROUND] could not create world", e);
		}
	}
}
