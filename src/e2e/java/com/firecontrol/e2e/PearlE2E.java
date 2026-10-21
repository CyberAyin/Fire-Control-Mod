package com.firecontrol.e2e;

import com.firecontrol.Eta;
import com.firecontrol.FireControl;
import com.firecontrol.PearlControl;
import com.firecontrol.PearlLoader;
import com.firecontrol.calc.CannonType;
import com.firecontrol.calc.PearlCalculator;
import com.firecontrol.calc.PearlCalculator.Axis;
import com.firecontrol.compat.Compat;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import com.firecontrol.gui.FireControlScreen;
import com.firecontrol.world.Blocks2;
import com.firecontrol.world.PearlLayout;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.FileUtil;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Pearl cannon test, dev only. Run with -Dfirecontrol.pearl=<360_PearlCannon-Overworld.litematic>
public class PearlE2E implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("firecontrol-pearl-e2e");
	static final BlockPos A = new BlockPos(0, -60, 0);
	static final BlockPos B = new BlockPos(400, -60, 0);
	// breezes and golems, in schematic coordinates (region position 9 0 -6 + Pos)
	static final double[][] BREEZES = {{5.3, 62, -4.7}, {-4.3, 62, -4.7}, {5.3, 62, 3.7}, {-4.3, 62, 3.7}};
	static final double[][] GOLEMS = {{5.56, 62.5, -0.46}, {-4.55, 62.5, -0.4}};

	private final List<Step> steps = new ArrayList<>();
	private int stepIndex = -1, ticksInStep, passes, fails;
	private Path schematic;
	private Pairing preset;
	private double[] predicted;
	private long throwTick, launchedAt;
	private Vec3 before;
	private int throwAt;
	private boolean retried;

	private record Step(String name, BiFunction<Minecraft, Integer, Boolean> run) {
	}

	@Override
	public void onInitializeClient() {
		String file = System.getProperty("firecontrol.pearl");
		if (file == null) return;
		schematic = Path.of(file);
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
			LOG.error("[PEARL] step '{}' crashed", step.name(), t);
			fail(step.name() + " crashed: " + t);
			done = true;
		}
		if (done) {
			stepIndex++;
			ticksInStep = 0;
			if (stepIndex < steps.size()) LOG.info("[PEARL] >> {}", steps.get(stepIndex).name());
		}
	}

	private void step(String name, BiFunction<Minecraft, Integer, Boolean> run) {
		steps.add(new Step(name, run));
	}

	private void build() {
		step("wait for world", (mc, t) -> mc.level != null && mc.player != null && mc.getSingleplayerServer() != null && t > 60);
		step("paste two cannons", (mc, t) -> {
			if (t == 0) {
				Store.pairings().clear();
				MinecraftServer server = mc.getSingleplayerServer();
				server.execute(() -> {
					try {
						ServerLevel level = server.overworld();
						for (String c : new String[] {"difficulty normal", "gamerule spawn_mobs false", "gamerule advance_time false", "gamerule advance_weather false",
								"time set noon", "gamemode survival @a", "effect give @a minecraft:resistance infinite 255 true"}) run(server, c);
						LOG.info("[PEARL] pasted {} blocks at A", LitematicPaster.paste(level, schematic, A, Rotation.NONE));
						LOG.info("[PEARL] pasted {} blocks at B (turned 90)", LitematicPaster.paste(level, schematic, B, Rotation.CLOCKWISE_90));
						mobs(server, new PearlLayout(A, 0, false));
						mobs(server, new PearlLayout(B, 1, false));
						// cluttered inventory with a few free slots
						run(server, "clear @a");
						run(server, "give @a minecraft:ender_pearl 16");
						run(server, "give @a minecraft:cobblestone 640");
						run(server, "give @a minecraft:redstone 7");
					} catch (Exception e) {
						LOG.error("[PEARL] paste failed", e);
						fail("paste: " + e);
					}
				});
				return false;
			}
			if (t == 20) tp(mc, A, 3.5, 1, -2.5, 0);
			return t > 120;
		});
		step("pair", (mc, t) -> {
			client(mc, "firecontrol pair");
			preset = FireControl.resolveActive();
			check("pearl preset made", preset != null && preset.type() == CannonType.PEARL && preset.isPearl(), preset == null ? "null" : preset.cannonType);
			if (preset == null || !preset.isPearl()) return true;
			check("origin found", preset.origin().equals(A) && preset.pearl.rotation == 0 && !preset.pearl.extendedInput,
					preset.origin().toShortString() + " rot " + preset.pearl.rotation);
			return true;
		});
		step("target", (mc, t) -> {
			if (preset == null) return true;
			if (t == 0) {
				client(mc, "firecontrol target 205 -60 -73");
				return false;
			}
			if (t < 10) return false;
			PearlCalculator.Solution s = PearlControl.solution();
			check("solution", s != null && s.axisA == Axis.EAST && s.axisB == Axis.NORTH && s.chargesA == 5 && s.chargesB == 2,
					s == null ? "null" : s.axisA + " " + s.chargesA + " " + s.axisB + " " + s.chargesB);
			check("hole open before loading", PearlControl.holeOpen(), "");
			predicted = PearlCalculator.landing(A.getX(), A.getY(), A.getZ(), A.getY() - 1, 2, 5, 0, 0);
			shot(mc, "p1_before_load");
			return true;
		});
		step("load it for me (from chat)", (mc, t) -> load(mc, t, "a"));
		step("loaded right", (mc, t) -> {
			if (t < 20) return false;
			check("stacks match", PearlControl.stacked(Axis.EAST) == 5 && PearlControl.stacked(Axis.NORTH) == 2 && PearlControl.stacked(Axis.WEST) == 0 && PearlControl.stacked(Axis.SOUTH) == 0,
					stacks());
			check("all bulbs off at the end", lit(mc, preset.pearl).isEmpty(), lit(mc, preset.pearl));
			check("ready", PearlControl.ready() && preset.loaded, PearlControl.holeOpen() + " " + preset.loaded);
			check("counter items all back (barrel 1728, inventory 7)", serverCount(mc, preset.pearl.counter()) == 1728 && redstoneInInventory(mc) == 7,
					serverCount(mc, preset.pearl.counter()) + " / " + redstoneInInventory(mc));
			shot(mc, "p2_ready");
			return true;
		});
		step("throw from the hole", (mc, t) -> throwAndLand(mc, t, A, "A"));
		// second shot from the same cannon
		step("second target", (mc, t) -> {
			if (t == 0) {
				tp(mc, A, 3.5, 1, -2.5, 0);
				return false;
			}
			if (t < 100) return false;
			if (t == 100) {
				client(mc, "firecontrol target 150 -60 60");
				return false;
			}
			if (t < 110) return false;
			PearlCalculator.Solution s = PearlControl.solution();
			check("second solution (east + south)", s != null && s.axisA == Axis.EAST && s.axisB == Axis.SOUTH && s.total() > 0 && s.total() < 12,
					s == null ? "null" : s.axisA + " " + s.chargesA + " " + s.axisB + " " + s.chargesB);
			check("fired cannon is empty and open again", PearlControl.stored() == 0 && PearlControl.holeOpen() && !preset.fired, stacks() + " hole " + PearlControl.holeOpen());
			if (s != null) predicted = PearlCalculator.landing(A.getX(), A.getY(), A.getZ(), A.getY() - 1, 0, s.chargesA, s.chargesB, 0);
			return true;
		});
		step("second load", (mc, t) -> load(mc, t, "a2"));
		step("second load right", (mc, t) -> {
			if (t < 20) return false;
			PearlCalculator.Solution s = PearlControl.solution();
			check("second stacks match", s != null && PearlControl.stacked(Axis.EAST) == s.chargesA && PearlControl.stacked(Axis.SOUTH) == s.chargesB
					&& PearlControl.stacked(Axis.NORTH) == 0 && PearlControl.stacked(Axis.WEST) == 0, stacks());
			check("second ready", PearlControl.ready(), PearlControl.holeOpen() + " " + preset.loaded);
			return true;
		});
		step("second throw", (mc, t) -> throwAndLand(mc, t, A, "A2"));
		// turned cannon: the button labels don't match the compass
		step("pair turned cannon", (mc, t) -> {
			if (t == 0) {
				tp(mc, B, 3.5, 1, -2.5, 90);
				return false;
			}
			if (t < 100) return false;
			client(mc, "firecontrol pair");
			preset = FireControl.resolveActive();
			check("turned cannon found", preset != null && preset.isPearl() && preset.origin().equals(B) && preset.pearl.rotation == 1,
					preset == null ? "null" : preset.origin().toShortString() + " rot " + (preset.pearl == null ? "-" : preset.pearl.rotation));
			return true;
		});
		step("target from the turned cannon", (mc, t) -> {
			if (preset == null || !preset.isPearl()) return true;
			if (t == 0) {
				client(mc, "firecontrol target " + (B.getX() - 120) + " " + (B.getZ() + 160));
				return false;
			}
			if (t < 10) return false;
			PearlCalculator.Solution s = PearlControl.solution();
			check("turned solution (west + south)", s != null && s.axisA == Axis.WEST && s.axisB == Axis.SOUTH && s.total() > 0 && s.total() < 12,
					s == null ? "null" : s.axisA + " " + s.chargesA + " " + s.axisB + " " + s.chargesB);
			if (s != null) {
				int[] w = new int[4];
				w[s.axisA.ordinal()] += s.chargesA;
				w[s.axisB.ordinal()] += s.chargesB;
				predicted = PearlCalculator.landing(B.getX(), B.getY(), B.getZ(), B.getY() - 1, w[Axis.NORTH.ordinal()], w[Axis.EAST.ordinal()], w[Axis.SOUTH.ordinal()], w[Axis.WEST.ordinal()]);
			}
			return true;
		});
		step("load the turned cannon (from the menu)", (mc, t) -> load(mc, t, "b"));
		step("turned cannon loaded right", (mc, t) -> {
			if (t < 20) return false;
			PearlCalculator.Solution s = PearlControl.solution();
			PearlLayout l = preset.pearl;
			// turned 90 cw: SOUTH pushes west, EAST pushes south
			check("turned: world west on the SOUTH button, world south on EAST", l.labelFor(Axis.WEST) == Axis.SOUTH && l.labelFor(Axis.SOUTH) == Axis.EAST, "");
			check("turned stacks match", s != null && PearlControl.stacked(Axis.SOUTH) == s.charges(Axis.WEST) && PearlControl.stacked(Axis.EAST) == s.charges(Axis.SOUTH), stacks());
			check("turned ready", PearlControl.ready(), PearlControl.holeOpen() + " " + preset.loaded);
			return true;
		});
		// top-up: remove one charge and load again, only one should go in
		step("top up after losing a charge", (mc, t) -> {
			if (t == 0) {
				tp(mc, A, 3.5, 1, -2.5, 0);
				return false;
			}
			if (t == 100) {
				client(mc, "firecontrol target 205 -60 -73");
				client(mc, "firecontrol InputSettings");
				return false;
			}
			if (t == 101) check("full load started", PearlLoader.running(), "");
			if (t > 101 && interruptions(mc, t)) return false;
			if (t > 101 && PearlLoader.running() && t < 20 * 150) return false;
			if (t < 120) return false;
			check("full load done", preset != null && FireControl.active().loaded && PearlControl.stacked(Axis.EAST) == 5, stacks());
			check("all three interruptions happened and were resumed", interrupt == 3 && !PearlLoader.paused(), interrupt + " interruptions");
			check("after the pauses, your own redstone is still 7", redstoneInInventory(mc) == 7, String.valueOf(redstoneInInventory(mc)));
			MinecraftServer server = mc.getSingleplayerServer();
			BlockPos cell = A.offset(-1, 42, -1);
			server.execute(() -> {
				List<Entity> charges = server.overworld().getEntities((Entity) null, new AABB(cell).inflate(0, 2, 0), PearlControl::isCharge);
				if (!charges.isEmpty()) charges.get(0).discard();
			});
			return true;
		});
		step("load again tops up only East", (mc, t) -> {
			if (t < 30) return false;
			if (t == 30) {
				Pairing p = FireControl.active();
				p.loaded = false;
				client(mc, "firecontrol InputSettings");
				check("top-up started", PearlLoader.running(), "");
				return false;
			}
			if (PearlLoader.running() && t < 20 * 90) return false;
			check("topped up", PearlControl.stacked(Axis.EAST) == 5 && PearlControl.stacked(Axis.NORTH) == 2 && PearlControl.ready(), stacks());
			check("top-up used one item", serverCount(mc, new PearlLayout(A, 0, false).counter()) == 1728, String.valueOf(serverCount(mc, new PearlLayout(A, 0, false).counter())));
			return true;
		});
		step("menu screenshot", (mc, t) -> {
			if (t == 0) {
				client(mc, "firecontrol");
				return false;
			}
			if (t == 6) {
				check("menu open", Compat.screen(mc) instanceof FireControlScreen, String.valueOf(Compat.screen(mc)));
				shot(mc, "p3_menu");
			}
			if (t == 12) {
				Compat.setScreen(mc, null);
				return true;
			}
			return false;
		});
		step("finish", (mc, t) -> {
			if (t == 0) {
				LOG.info("[PEARL] RESULT: {} passed, {} failed", passes, fails);
				Store.save();
			}
			if (t > 40) {
				mc.stop();
				return true;
			}
			return false;
		});
	}

	/** Interrupts the load three times (Esc in the barrel, chat on the way to the INPUT, chat open while charging). */
	private boolean interruptions(Minecraft mc, int t) {
		String phase = PearlLoader.phase();
		boolean cursor = !mc.player.containerMenu.getCarried().isEmpty();
		if (!PearlLoader.paused() && PearlLoader.running()) {
			if (interrupt == 0 && phase.equals("TAKE") && cursor && redstoneInInventory(mc) > 7) {
				mc.player.closeContainer(); // Esc
				return interrupted("Esc in the barrel mid-take", t, 30);
			}
			if (interrupt == 1 && phase.equals("OPEN_INPUT")) {
				Compat.setScreen(mc, new ChatScreen("", false));
				return interrupted("chat on the way to the INPUT", t, 20);
			}
			if (interrupt == 2 && phase.equals("WAIT")) {
				Compat.setScreen(mc, new ChatScreen("", false));
				return interrupted("chat while the charges are made", t, 100);
			}
		}
		if (resumeAt == 0) return false;
		if (t == interruptedAt + 3) {
			check(interruptName + ": paused", PearlLoader.paused() && !PearlLoader.running(), "running " + PearlLoader.running() + " phase " + phase);
			LOG.info("[PEARL]   paused in {} holding {} redstone", phase, redstoneInInventory(mc));
		}
		if (t == resumeAt) {
			Compat.setScreen(mc, new ChatScreen("", false));
			client(mc, "firecontrol InputSettings"); // [Resume]
			check(interruptName + ": Resume carries on", PearlLoader.running(), "phase " + phase);
			resumeAt = 0;
		}
		return true;
	}

	private boolean interrupted(String name, int t, int pause) {
		interrupt++;
		interruptName = name;
		interruptedAt = t;
		resumeAt = t + pause;
		LOG.info("[PEARL]   interrupting: {}", name);
		return true;
	}

	private int interrupt, interruptedAt, resumeAt;
	private String interruptName = "";

	private boolean load(Minecraft mc, int t, String tag) {
		if (preset == null || !preset.isPearl()) return true;
		if (t == 0) {
			if (tag.equals("a")) {
				Compat.setScreen(mc, new ChatScreen("", false));
				client(mc, "firecontrol InputSettings");
			} else {
				client(mc, "firecontrol");
			}
			return false;
		}
		if (t == 4 && !tag.equals("a")) {
			check(tag + " menu open", Compat.screen(mc) instanceof FireControlScreen, String.valueOf(Compat.screen(mc)));
			client(mc, "firecontrol InputSettings");
			return false;
		}
		if (t == 6) {
			var screen = Compat.screen(mc);
			check(tag + " loader running and the chat/menu closed", PearlLoader.running()
					&& (screen == null || screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen), String.valueOf(screen));
		}
		if (t == 40) shot(mc, tag + "_loading");
		if (t == 200) shot(mc, tag + "_making");
		if (t % 40 == 0) clusters(mc, tag + " t=" + t);
		if (t > 6 && PearlLoader.running() && t < 20 * 180) return false;
		if (t <= 6) return false;
		check(tag + " loader finished", !PearlLoader.running() && preset.loaded, "running " + PearlLoader.running() + " loaded " + preset.loaded + " after " + t);
		LOG.info("[PEARL] {} load took {} ticks", tag, t);
		return true;
	}

	private boolean throwAndLand(Minecraft mc, int t, BlockPos origin, String tag) {
		if (preset == null || !preset.isPearl()) return true;
		if (t == 0) {
			throwAt = 0;
			retried = false;
		}
		int u = t - throwAt;
		PearlLayout l = preset.pearl;
		if (u == 0) {
			Vec3 hole = l.point(0.5, 1, -0.5);
			MinecraftServer server = mc.getSingleplayerServer();
			String cmd = String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f", hole.x, hole.y, hole.z);
			server.execute(() -> run(server, cmd));
			return false;
		}
		if (u == 20) {
			for (int i = 0; i < 9; i++) {
				if (mc.player.getInventory().getItem(i).is(net.minecraft.world.item.Items.ENDER_PEARL)) mc.player.getInventory().setSelectedSlot(i);
			}
			mc.player.setXRot(-90f);
			mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(mc.player.getYRot(), -90f, mc.player.onGround(), mc.player.horizontalCollision));
			return false;
		}
		if (u == 24) {
			before = mc.player.position();
			mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			throwTick = mc.level.getGameTime();
			launchedAt = -1;
			return false;
		}
		if (u < 24) return false;
		if (launchedAt < 0 && Eta.running(preset, mc.level.getGameTime())) {
			launchedAt = mc.level.getGameTime();
			check(tag + " countdown started by the pearl", preset.fired && launchedAt - throwTick < 20, (launchedAt - throwTick) + " ticks");
		}
		if ((u - 24) % 10 == 0) {
			MinecraftServer server = mc.getSingleplayerServer();
			final int tt = u - 24;
			server.execute(() -> {
				for (Entity e : server.overworld().getAllEntities()) {
					if (Compat.isEnderPearl(e)) {
						Vec3 rel = e.position().subtract(Vec3.atLowerCornerOf(origin));
						LOG.info("[PEARL] {} t={} pearl at rel {} {} {} vel {}", tag, tt, fmt(rel.x), fmt(rel.y), fmt(rel.z), e.getDeltaMovement());
					}
				}
			});
		}
		if (u == 24 + 60) shot(mc, tag + "_eta");
		boolean teleported = mc.player.position().distanceTo(before) > 20;
		// the lift sometimes drops the pearl, the mod should notice and we throw again
		if (!teleported && !retried && u > 24 + PearlControl.IN_CANNON_TICKS + 90 && !preset.fired && preset.loaded) {
			LOG.info("[PEARL] {} the cannon dropped the pearl; throwing again", tag);
			check(tag + " dropped pearl noticed (countdown cancelled, still loaded)", !Eta.running(preset, mc.level.getGameTime()), "");
			retried = true;
			throwAt = t;
			launchedAt = -1;
			return false;
		}
		if (!teleported && u < 24 + 600) return false;
		long flight = mc.level.getGameTime() - throwTick;
		Vec3 at = mc.player.position();
		double off = Math.hypot(at.x - predicted[0], at.z - predicted[1]);
		LOG.info("[PEARL] {} landed at {} {} after {} ticks; predicted {} {} after {} ticks (ETA said {})", tag, fmt(at.x), fmt(at.z), flight,
				fmt(predicted[0]), fmt(predicted[1]), fmt(PearlControl.IN_CANNON_TICKS + predicted[2]), preset.etaTotalTicks);
		check(tag + " landed within 10 blocks of the prediction", teleported && off < 10, fmt(off) + " blocks");
		check(tag + " countdown within 15 ticks", Math.abs(flight - preset.etaTotalTicks) <= 15, flight + " vs " + preset.etaTotalTicks);
		return true;
	}

	private void clusters(Minecraft mc, String tag) {
		BlockPos o = preset.origin();
		AABB box = new AABB(o).inflate(24, 0, 24).expandTowards(0, 70, 0);
		MinecraftServer server = mc.getSingleplayerServer();
		server.execute(() -> {
			java.util.Map<String, Integer> m = new java.util.TreeMap<>();
			for (Entity e : server.overworld().getEntities((Entity) null, box, PearlControl::isCharge)) {
				BlockPos b = e.blockPosition().subtract(o);
				m.merge(b.getX() + "," + b.getY() + "," + b.getZ(), 1, Integer::sum);
			}
			ServerLevel lv = server.overworld();
			// walls around the stack cell at y 54: x- East, z+ North, z- South, x+ West
			int[][] walls = {{-1, 52, 2, -1, 52, 0}, {0, 52, 3, 0, 52, 1}, {-2, 52, -1, 0, 52, -1}, {1, 52, -2, 1, 52, 0}};
			StringBuilder gate = new StringBuilder("walls(E N S W) ");
			for (int[] w : walls) {
				boolean lit = Blocks2.isLit(lv.getBlockState(o.offset(w[0], w[1], w[2])));
				var pst = lv.getBlockState(o.offset(w[3], w[4], w[5]));
				boolean ext = pst.hasProperty(net.minecraft.world.level.block.piston.PistonBaseBlock.EXTENDED) && pst.getValue(net.minecraft.world.level.block.piston.PistonBaseBlock.EXTENDED);
				gate.append(lit ? 'L' : '-').append(ext ? "x " : "o ");
			}
			gate.append("sel=").append(lit(lv, new PearlLayout(o, 0, false)));
			LOG.info("[PEARL]   {} {} | {} | hole {} | {}", tag, PearlLoader.status(), m, PearlControl.holeOpen(), gate);
		});
	}

	private static String lit(Minecraft mc, PearlLayout l) {
		return lit(mc.level, l);
	}

	private static String lit(net.minecraft.world.level.Level level, PearlLayout l) {
		StringBuilder sb = new StringBuilder();
		for (Axis a : Axis.values()) if (Blocks2.isLit(level.getBlockState(l.bulb(a)))) sb.append(a.name().charAt(0));
		return sb.toString();
	}

	private static String stacks() {
		StringBuilder sb = new StringBuilder();
		for (Axis a : Axis.values()) sb.append(a.name().charAt(0)).append(PearlControl.stacked(a)).append(' ');
		return sb.append("stored ").append(PearlControl.stored()).append(" total ").append(PearlControl.total()).toString();
	}

	// block entities have to be read on the server thread
	private static int serverCount(Minecraft mc, BlockPos pos) {
		MinecraftServer server = mc.getSingleplayerServer();
		return server.submit(() -> {
			if (!(server.overworld().getBlockEntity(pos) instanceof Container c)) return -1;
			int n = 0;
			for (int i = 0; i < c.getContainerSize(); i++) n += c.getItem(i).getCount();
			return n;
		}).join();
	}

	private static int redstoneInInventory(Minecraft mc) {
		int n = 0;
		for (int i = 0; i < 36; i++) {
			var st = mc.player.getInventory().getItem(i);
			if (st.is(net.minecraft.world.item.Items.REDSTONE)) n += st.getCount();
		}
		return n;
	}

	private static void mobs(MinecraftServer server, PearlLayout l) {
		for (double[] b : BREEZES) summon(server, "breeze", l.point(b[0], b[1], b[2]));
		for (double[] g : GOLEMS) summon(server, "iron_golem", l.point(g[0], g[1], g[2]));
	}

	private static void summon(MinecraftServer server, String type, Vec3 at) {
		run(server, String.format(Locale.ROOT, "summon minecraft:%s %.3f %.3f %.3f {PersistenceRequired:1b}", type, at.x, at.y, at.z));
	}

	private void check(String what, boolean ok, String detail) {
		if (ok) {
			passes++;
			LOG.info("[PEARL] PASS {}", what);
		} else {
			fail(what + " (" + detail + ")");
		}
	}

	private void fail(String what) {
		fails++;
		LOG.info("[PEARL] FAIL {}", what);
	}

	private static void client(Minecraft mc, String command) {
		try {
			FabricClientCommandSource src = (FabricClientCommandSource) mc.getConnection().getSuggestionsProvider();
			Compat.activeDispatcher().execute(command, src);
		} catch (CommandSyntaxException e) {
			LOG.info("[PEARL] FAIL command '{}': {}", command, e.getMessage());
		}
	}

	private static void run(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	private static void tp(Minecraft mc, BlockPos base, double x, double y, double z, float yaw) {
		MinecraftServer server = mc.getSingleplayerServer();
		Vec3 p = new PearlLayout(base, base.equals(B) ? 1 : 0, false).point(x, y, z);
		String cmd = String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f %.1f 10", p.x, p.y, p.z, yaw);
		server.execute(() -> run(server, cmd));
	}

	private static String fmt(double d) {
		return String.format(Locale.ROOT, "%.1f", d);
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "e2e_" + name + ".png", Compat.mainRenderTarget(mc), 1, c -> LOG.info("[PEARL] screenshot {}", c.getString()));
	}

	private static void createWorld(Minecraft mc) {
		try {
			String folder = FileUtil.findAvailableName(mc.getLevelSource().getBaseDir(), "Pearl E2E", "");
			LevelSettings settings = E2ECompat.levelSettings("Pearl E2E");
			mc.createWorldOpenFlows().createFreshLevel(folder, settings, new WorldOptions(1234L, false, false), registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), Compat.screen(mc));
		} catch (Exception e) {
			LOG.error("[PEARL] could not create world", e);
		}
	}
}
