package com.firecontrol.e2e;

import com.firecontrol.Actions;
import com.firecontrol.AutoInput;
import com.firecontrol.FireControl;
import com.firecontrol.calc.CannonType;
import com.firecontrol.calc.FireCalculator;
import com.firecontrol.calc.FireMode;
import com.firecontrol.calc.Passcodes;
import com.firecontrol.compat.Compat;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import com.firecontrol.gui.FireControlScreen;
import com.firecontrol.gui.MoreScreen;
import com.firecontrol.world.Blocks2;
import com.firecontrol.world.CannonScanner;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.FileUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// End-to-end test, dev only. Run with -Dfirecontrol.e2e=<folder with the .litematic files>
public class E2EClient implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("firecontrol-e2e");

	// MS cannon + panel, Mk6 cannon + panel rotated 90 degrees clockwise
	static final BlockPos MS_ORIGIN = new BlockPos(0, 16, 0);
	static final BlockPos MS_PANEL = new BlockPos(48, -60, -16);
	static final BlockPos MK6_ORIGIN = new BlockPos(0, 16, 160);
	static final BlockPos MK6_PANEL = new BlockPos(60, -60, 160);
	static final int MS_PASSCODE = 703; // not the default code, so the test fails if it isn't actually read
	// second MS panel, out of render distance of every cannon (two-step pairing)
	static final BlockPos FAR_PANEL = new BlockPos(2048, -60, -16);
	// MS cannon starts at y 11
	static final BlockPos AT_MS_CANNON = new BlockPos(-40, -60, 0);
	static final int CHUNK_WAIT = 120;

	private final List<Step> steps = new ArrayList<>();
	private int stepIndex = -1;
	private int wait;
	private int fails, passes;
	private Path schemDir;

	private record Step(String name, BiFunction<Minecraft, Integer, Boolean> run) {
	}

	@Override
	public void onInitializeClient() {
		String dir = System.getProperty("firecontrol.e2e");
		if (dir == null) return;
		schemDir = Path.of(dir);
		buildSteps();
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(Minecraft mc) {
		if (stepIndex == -1) {
			if (Compat.screen(mc) instanceof TitleScreen || Compat.screen(mc) instanceof AccessibilityOnboardingScreen) {
				mc.options.onboardAccessibility = false;
				mc.options.pauseOnLostFocus = false; // don't pause when the window loses focus
				mc.options.save();
				createWorld(mc);
				stepIndex = 0;
			}
			return;
		}
		if (stepIndex >= steps.size()) return;
		if (wait > 0) {
			wait--;
			return;
		}
		Step step = steps.get(stepIndex);
		boolean done;
		try {
			done = step.run().apply(mc, ticksInStep++);
		} catch (Throwable t) {
			LOG.error("[E2E] step '{}' crashed", step.name(), t);
			fail(step.name() + " crashed: " + t);
			done = true;
		}
		if (done) {
			stepIndex++;
			ticksInStep = 0;
			if (stepIndex < steps.size()) LOG.info("[E2E] >> {}", steps.get(stepIndex).name());
		}
	}

	private int ticksInStep;
	private int panelTicks;
	// 0 = not paused yet, 1 = paused, 2 = resumed
	private int pauseStage, pausedAt;
	private String pressesAtPause = "";

	private void step(String name, BiFunction<Minecraft, Integer, Boolean> run) {
		steps.add(new Step(name, run));
	}

	private void buildSteps() {
		step("wait for world", (mc, t) -> mc.level != null && mc.player != null && mc.getSingleplayerServer() != null && t > 60);
		step("decode round trip", (mc, t) -> {
			// decode(encode(x)) == x
			java.util.Random rnd = new java.util.Random(7);
			int checked = 0, bad = 0;
			for (int i = 0; i < 3000; i++) {
				CannonType type = i % 2 == 0 ? CannonType.MK6 : CannonType.MS;
				int ox = 16 * (rnd.nextInt(100) - 50), oy = 16 * (1 + rnd.nextInt(8)), oz = 16 * (rnd.nextInt(100) - 50);
				int dx = (64 + rnd.nextInt(15000)) * (rnd.nextBoolean() ? 1 : -1), dz = (64 + rnd.nextInt(15000)) * (rnd.nextBoolean() ? 1 : -1);
				boolean nuke = rnd.nextBoolean();
				FireCalculator.Request req = new FireCalculator.Request(ox + dx, rnd.nextInt(250) - 50, oz + dz, nuke ? FireMode.NUKE : FireMode.STAB,
						1 + rnd.nextInt(20), 1 + rnd.nextInt(270), type == CannonType.MK6 ? rnd.nextInt(8) : 0);
				FireCalculator.Solution s = FireCalculator.solve(type, ox, oy, oz, 940, req);
				if (!s.isValid()) continue;
				checked++;
				FireCalculator.Solution d = FireCalculator.decode(type, ox, oy, oz, s.bits, new FireCalculator.Request(0, 0, 0, FireMode.NUKE, 10, 70, req.magazineSlot()));
				boolean ok = d.coarseX == s.coarseX && d.coarseY == s.coarseY && d.coarseZ == s.coarseZ && d.fineX == s.fineX && d.fineY == s.fineY && d.fineZ == s.fineZ
						&& Math.abs(d.landX - s.landX) < 1e-6 && Math.abs(d.landZ - s.landZ) < 1e-6 && FireCalculator.decodePasscode(s.bits) == 940
						&& d.request.mode() == req.mode()
						&& (nuke ? d.totalTicksRounded() == s.totalTicksRounded() : Math.abs(d.totalTicksRounded() - s.totalTicksRounded()) <= 12);
				if (!ok) bad++;
			}
			check("panel settings decode back to the same shot (" + checked + " shots)", bad == 0 && checked > 1000, bad + " bad of " + checked);
			return true;
		});
		step("paste schematics", (mc, t) -> {
			// same world every run, clear old pairings
			Store.pairings().clear();
			Store.setPending(null);
			MinecraftServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				try {
					ServerLevel level = server.overworld();
					LOG.info("[E2E] pasted MS cannon: {} blocks", LitematicPaster.paste(level, schemDir.resolve("OSC Minimal Signature.litematic"), MS_ORIGIN, Rotation.NONE));
					LOG.info("[E2E] pasted MS panel: {} blocks", LitematicPaster.paste(level, schemDir.resolve("OSC MS Control Panel.litematic"), MS_PANEL, Rotation.NONE));
					LOG.info("[E2E] pasted Mk6 cannon: {} blocks", LitematicPaster.paste(level, schemDir.resolve("The Orbital Strike Cannon Mk.6.1.litematic"), MK6_ORIGIN, Rotation.NONE));
					LOG.info("[E2E] pasted Mk6 panel (rotated): {} blocks", LitematicPaster.paste(level, schemDir.resolve("OSC 6 Control Panel.litematic"), MK6_PANEL, Rotation.CLOCKWISE_90));
					// set the passcode key like the pistons would: composter in the "1" slot (y+11) or the "0" slot above
					for (int i = 0; i < 10; i++) {
						BlockPos one = MS_ORIGIN.offset(-14 + i, 11, -16);
						boolean bit = ((MS_PASSCODE >> (9 - i)) & 1) == 1;
						BlockState composter = Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 1);
						level.setBlock(one, bit ? composter : Blocks.AIR.defaultBlockState(), LitematicPaster.FLAGS);
						level.setBlock(one.above(), bit ? Blocks.AIR.defaultBlockState() : composter, LitematicPaster.FLAGS);
						BlockPos comparator = one.south();
						level.setBlock(comparator, level.getBlockState(comparator).setValue(ComparatorBlock.POWERED, bit), LitematicPaster.FLAGS);
						BlockPos repeater = comparator.south();
						level.setBlock(repeater, level.getBlockState(repeater).setValue(RepeaterBlock.POWERED, bit), LitematicPaster.FLAGS);
					}
					LOG.info("[E2E] server key after rewrite: {}", keyRow(level, MS_ORIGIN));
					run(server, "gamemode survival @a");
					run(server, "difficulty peaceful");
					run(server, "time set noon");
					run(server, "gamerule advance_time false");
					run(server, "gamerule advance_weather false");
				} catch (Exception e) {
					LOG.error("[E2E] paste failed", e);
					fail("paste: " + e);
				}
			});
			wait = 40;
			return true;
		});
		step("go to the MS panel", (mc, t) -> {
			tp(mc, MS_PANEL, 10.5, 6.6, 19.5, 90);
			wait = 100; // wait for chunks
			return true;
		});
		step("pair MS", (mc, t) -> {
			LOG.info("[E2E] server key at pair time: {}", keyRow(mc.getSingleplayerServer().overworld(), MS_ORIGIN));
			LOG.info("[E2E] client key at pair time: {}", keyRow(mc.level, MS_ORIGIN));
			client(mc, "firecontrol pair");
			Pairing p = FireControl.resolveActive();
			check("MS pairing exists", p != null);
			if (p == null) return true;
			check("MS cannon type", p.type() == CannonType.MS, p.cannonType);
			check("MS origin", p.origin().equals(MS_ORIGIN), p.origin().toShortString());
			check("MS passcode read from cannon", p.passcode == MS_PASSCODE && "cannon".equals(p.passcodeSource), p.passcode + " " + p.passcodeSource);
			check("MS input button", BlockPos.of(p.panel.inputButton).equals(MS_PANEL.offset(9, 9, 11)), String.valueOf(p.panel.inputButtonPos()));
			int buttons = 0;
			for (int i = 0; i < p.panel.size(); i++) if (p.panel.button(i) != null) buttons++;
			check("MS 65 buttons", buttons == 65, String.valueOf(buttons));
			check("MS layout orientation", layoutMatches(p, (r, c) -> MS_PANEL.offset(7, 11 - r, 26 - c)), "");
			return true;
		});
		step("target MS", (mc, t) -> {
			if (t == 0) {
				client(mc, "firecontrol target 2000 70 -1500 nuke 12");
				return false;
			}
			if (t < 10) return false;
			FireCalculator.Solution s = FireControl.solution();
			check("MS solution valid", s != null && s.isValid(), s == null ? "null" : s.errors.toString());
			check("MS panel needs presses", FireControl.wrongCount() > 0, String.valueOf(FireControl.wrongCount()));
			LOG.info("[E2E] wrong before InputSettings: {}", FireControl.wrongCount());
			shot(mc, "1_ms_overlay_before");
			return true;
		});
		step("InputSettings MS", (mc, t) -> inputSettings(mc, t, "ms", "chat", (r, c) -> MS_PANEL.offset(7, 11 - r, 26 - c)));
		step("press Input Settings (MS)", (mc, t) -> pressInputButton(mc, t, MS_PANEL, 10.5, 6.6, 11.5, 90, "ms"));
		step("Input Settings with settings the mod didn't enter", (mc, t) -> {
			Pairing p = FireControl.active();
			if (t == 0) {
				panelTicks = p.etaTotalTicks; // panel still has the 2000 70 -1500 nuke 12 settings
				client(mc, "firecontrol target 2600 90 -900 nuke 5");
				check("a new target clears 'fired'", !p.fired, "");
				return false;
			}
			if (t == 40) {
				BlockPos input = p.panel.inputButtonPos();
				mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(input), Direction.EAST, input, false));
				return false;
			}
			if (t < 46) return false;
			check("ETA follows the panel, not the mod's target", p.etaTotalTicks == panelTicks, p.etaTotalTicks + " vs " + panelTicks);
			check("target synced from the panel", Math.abs(p.targetX - 2000) <= 8 && Math.abs(p.targetZ + 1500) <= 8 && "nuke".equals(p.mode) && p.nukeSize == 12,
					p.targetX + " " + p.targetY + " " + p.targetZ + " " + p.mode + " " + p.nukeSize);
			check("synced shot marked fired", p.fired, "");
			return true;
		});
		step("presets: one panel, two cannons", (mc, t) -> {
			Pairing ms = FireControl.resolveActive(); // ms-1, at the MS panel
			int before = FireControl.pairingsHere().size();
			client(mc, "firecontrol pair at 0 16 160 mk6");
			Pairing added = FireControl.resolveActive();
			check("another cannon on the same panel adds a preset", FireControl.pairingsHere().size() == before + 1 && added != ms
					&& added.type() == CannonType.MK6 && added.origin().equals(MK6_ORIGIN) && added.panel.sameBulbsAs(ms.panel), FireControl.pairingsHere().size() + " " + added.name);
			check("the first preset kept its cannon", ms.type() == CannonType.MS && ms.origin().equals(MS_ORIGIN), ms.cannonType + " " + ms.origin().toShortString());
			Actions.select(ms);
			check("picking a preset switches what the panel drives", FireControl.resolveActive() == ms, FireControl.resolveActive().name);
			String err = Actions.rename(added, "north gun");
			check("rename a preset", err == null && added.name.equals("north gun") && Actions.rename(added, ms.name) != null, err + " " + added.name);
			Actions.deletePreset(added);
			check("delete a preset", FireControl.pairingsHere().size() == before && FireControl.resolveActive() == ms, String.valueOf(FireControl.pairingsHere().size()));
			return true;
		});
		step("go to the rotated Mk6 panel", (mc, t) -> {
			// Mk6 stand spot (10.5, 4.1, 14.5) rotated 90 cw: x' = 1 - z, z' = x
			tp(mc, MK6_PANEL, 1 - 14.5, 4.2, 10.5, 180);
			wait = 100;
			return true;
		});
		step("pair Mk6", (mc, t) -> {
			client(mc, "firecontrol pair");
			Pairing p = FireControl.resolveActive();
			check("Mk6 pairing active", p != null && p.type() == CannonType.MK6, p == null ? "null" : p.cannonType);
			if (p == null) return true;
			check("Mk6 origin", p.origin().equals(MK6_ORIGIN), p.origin().toShortString());
			check("Mk6 passcode 940", p.passcode == 940 && "cannon".equals(p.passcodeSource), p.passcode + " " + p.passcodeSource);
			check("Mk6 input button", p.panel.inputButtonPos() != null && p.panel.inputButtonPos().equals(LitematicPaster.transform(MK6_PANEL, Rotation.CLOCKWISE_90, 9, 7, 5)), String.valueOf(p.panel.inputButtonPos()));
			check("Mk6 rotated layout orientation", layoutMatches(p, (r, c) -> LitematicPaster.transform(MK6_PANEL, Rotation.CLOCKWISE_90, 7, 9 - r, 20 - c)), "");
			check("two pairings stored", FireControl.pairingsHere().size() == 2, String.valueOf(FireControl.pairingsHere().size()));
			return true;
		});
		step("target Mk6 (stab, slot 3)", (mc, t) -> {
			if (t == 0) {
				client(mc, "firecontrol target -4000 40 3000 stab 90 slot 3");
				return false;
			}
			if (t < 10) return false;
			FireCalculator.Solution s = FireControl.solution();
			check("Mk6 solution valid", s != null && s.isValid(), s == null ? "null" : s.errors.toString());
			shot(mc, "4_mk6_overlay_before");
			return true;
		});
		step("InputSettings Mk6", (mc, t) -> {
			// open chat halfway through, it should pause and then resume
			if (pauseStage == 0 && AutoInput.running() && AutoInput.fraction() >= 0.3) {
				pressesAtPause = AutoInput.progress().split("/")[0];
				Compat.setScreen(mc, new ChatScreen("", false));
				pauseStage = 1;
				pausedAt = t;
				return false;
			}
			if (pauseStage == 1) {
				if (t == pausedAt + 3) check("mk6 opening a menu pauses InputSettings", !AutoInput.running() && AutoInput.paused(), "running " + AutoInput.running());
				if (t == pausedAt + 40) {
					client(mc, "firecontrol InputSettings"); // [Resume]
					check("mk6 Resume carries on with the presses so far", AutoInput.running() && AutoInput.progress().startsWith(pressesAtPause + "/"),
							AutoInput.progress() + " vs " + pressesAtPause + " before");
				}
				if (t == pausedAt + 44) {
					check("mk6 Resume closes the chat it was clicked in", AutoInput.running() && Compat.screen(mc) == null, String.valueOf(Compat.screen(mc)));
					pauseStage = 2;
				}
				return false;
			}
			return inputSettings(mc, t, "mk6", "menu", (r, c) -> LitematicPaster.transform(MK6_PANEL, Rotation.CLOCKWISE_90, 7, 9 - r, 20 - c));
		});
		step("refuse InputSettings away from the panel", (mc, t) -> {
			if (t == 0) {
				tp(mc, new BlockPos(0, -60, 80), 0.5, 0, 0.5, 0);
				return false;
			}
			if (t < 20) return false;
			client(mc, "firecontrol InputSettings");
			check("InputSettings refused far away", !AutoInput.running(), "");
			return true;
		});
		step("paste a second MS panel far from every cannon", (mc, t) -> {
			MinecraftServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				try {
					LOG.info("[E2E] pasted far MS panel: {} blocks", LitematicPaster.paste(server.overworld(), schemDir.resolve("OSC MS Control Panel.litematic"), FAR_PANEL, Rotation.NONE));
				} catch (Exception e) {
					LOG.error("[E2E] paste failed", e);
					fail("far paste: " + e);
				}
			});
			wait = 20;
			return true;
		});
		step("two steps: panel first", (mc, t) -> {
			if (t == 0) {
				tp(mc, FAR_PANEL, 10.5, 6.6, 19.5, 90);
				return false;
			}
			if (t < CHUNK_WAIT) return false;
			check("MS cannon not loaded at the far panel", !CannonScanner.keyLoaded(mc.level, MS_ORIGIN), "");
			int before = FireControl.pairingsHere().size();
			client(mc, "firecontrol pair");
			Pairing half = Store.pending();
			check("panel saved as half a pairing", half != null && half.panel != null && !half.hasCannon(), String.valueOf(half));
			check("no pairing made from the panel alone", FireControl.pairingsHere().size() == before, String.valueOf(FireControl.pairingsHere().size()));
			return true;
		});
		step("two steps: then the cannon", (mc, t) -> {
			if (t == 0) {
				tp(mc, AT_MS_CANNON, 0.5, 0, 0.5, 0);
				return false;
			}
			if (t < CHUNK_WAIT) return false;
			client(mc, "firecontrol pair");
			checkFarPairing("panel first");
			return true;
		});
		step("two steps: cannon first", (mc, t) -> {
			if (t == 0) {
				unpairFar(mc);
				client(mc, "firecontrol pair");
				Pairing half = Store.pending();
				check("cannon saved as half a pairing", half != null && half.hasCannon() && half.panel == null && half.origin().equals(MS_ORIGIN), half == null ? "null" : half.origin().toShortString());
				check("passcode read when saving the cannon", half != null && half.passcode == MS_PASSCODE && "cannon".equals(half.passcodeSource), half == null ? "null" : half.passcode + " " + half.passcodeSource);
				tp(mc, FAR_PANEL, 10.5, 6.6, 19.5, 90);
				return false;
			}
			if (t < CHUNK_WAIT) return false;
			check("MS cannon not loaded at the far panel (again)", !CannonScanner.keyLoaded(mc.level, MS_ORIGIN), "");
			client(mc, "firecontrol pair");
			checkFarPairing("cannon first");
			return true;
		});
		step("typed origin, cannon not loaded", (mc, t) -> {
			if (t == 0) {
				unpairFar(mc);
				client(mc, "firecontrol pair at 0 16 0 ms");
				Pairing p = farPairing();
				check("typed origin pairs at once", p != null && p.origin().equals(MS_ORIGIN) && p.type() == CannonType.MS && Store.pending() == null, p == null ? "null" : p.origin().toShortString());
				check("typed origin: passcode not read yet", p != null && !p.passcodeKnown() && p.passcode == Passcodes.DEFAULT, p == null ? "null" : p.passcode + " " + p.passcodeSource);
				tp(mc, AT_MS_CANNON, 0.5, 0, 0.5, 0);
				return false;
			}
			Pairing p = farPairing();
			if (p != null && !p.passcodeKnown() && t < CHUNK_WAIT * 3) return false;
			check("typed origin: passcode read once the cannon loads", p != null && p.passcode == MS_PASSCODE && "cannon".equals(p.passcodeSource), p == null ? "null" : p.passcode + " " + p.passcodeSource);
			LOG.info("[E2E] passcode read {} ticks after arriving at the cannon", t);
			return true;
		});
		step("target where you stand", (mc, t) -> {
			client(mc, "firecontrol target");
			Pairing p = FireControl.resolveActive();
			BlockPos me = mc.player.blockPosition();
			check("/firecontrol target with no coordinates uses your position", p != null && p.hasTarget && p.targetX == me.getX() && p.targetY == me.getY() && p.targetZ == me.getZ(),
					p == null ? "null" : p.targetX + " " + p.targetY + " " + p.targetZ + " vs " + me.toShortString());
			return true;
		});
		step("menu screenshots", (mc, t) -> {
			if (t == 0) {
				client(mc, "firecontrol");
				return false;
			}
			if (t == 6) {
				check("menu open", Compat.screen(mc) instanceof FireControlScreen, String.valueOf(Compat.screen(mc)));
				shot(mc, "7_menu");
				Compat.setScreen(mc, new MoreScreen(Compat.screen(mc)));
				return false;
			}
			if (t == 12) {
				shot(mc, "8_more");
				Compat.setScreen(mc, null);
				return true;
			}
			return false;
		});
		// Walk in diagonally so pairing finds the MS cannon while some of its chunks are still
		// unloaded. Unloaded blocks shouldn't count as misses, so it should still score 100%.
		step("cannon partly outside render distance", (mc, t) -> {
			int r = mc.options.getEffectiveRenderDistance();
			int tries = t / CHUNK_WAIT;
			if (t % CHUNK_WAIT == 0) {
				double a = r * 16 - 8 * tries;
				tp(mc, new BlockPos(0, -60, 0), a, 0, a, 135);
				return false;
			}
			if (t % CHUNK_WAIT != CHUNK_WAIT - 1) return false;
			var chunks = mc.level.getChunkSource();
			int unloaded = 0;
			for (int cx = -2; cx <= 1; cx++) {
				for (int cz = -2; cz <= 1; cz++) if (!chunks.hasChunk(cx, cz)) unloaded++;
			}
			List<CannonScanner.Result> found = CannonScanner.scan(mc.level, mc.player.blockPosition(), Math.max(64, r * 16));
			CannonScanner.Result ms = found.stream().filter(f -> f.origin().equals(MS_ORIGIN)).findFirst().orElse(null);
			if (ms == null && tries < 24) return false;
			LOG.info("[E2E] cannon first found at {} with {}/16 of its chunks unloaded: {}", mc.player.blockPosition().toShortString(), unloaded, ms);
			check("partly loaded cannon found by pairing's scan, as an MS", ms != null && ms.type() == CannonType.MS, String.valueOf(found));
			check("found while some of its chunks were still unloaded", unloaded > 0, unloaded + " unloaded");
			check("only its loaded blocks are scored (100% match)", ms != null && ms.confidence() > 0.99, String.valueOf(ms));
			return true;
		});
		step("finish", (mc, t) -> {
			if (t == 0) {
				LOG.info("[E2E] RESULT: {} passed, {} failed", passes, fails);
				Store.save();
			}
			if (t > 40) {
				mc.stop();
				return true;
			}
			return false;
		});
	}

	/** Starts InputSettings from the chat link (chat still open) or from the menu. */
	private boolean inputSettings(Minecraft mc, int t, String tag, String from, java.util.function.BiFunction<Integer, Integer, BlockPos> truth) {
		boolean menu = from.equals("menu");
		int start = menu ? 3 : 0;
		if (menu && t == 0) {
			client(mc, "firecontrol");
			return false;
		}
		if (t == start) {
			if (menu) check(tag + " /firecontrol opens the menu", Compat.screen(mc) instanceof FireControlScreen, String.valueOf(Compat.screen(mc)));
			else Compat.setScreen(mc, new ChatScreen("", false)); // same as clicking [Enter them for me]
			client(mc, "firecontrol InputSettings");
			check(tag + " InputSettings started", AutoInput.running(), "");
			return false;
		}
		if (t == start + 3) {
			check(tag + " InputSettings closes the " + from + " and keeps going", AutoInput.running() && Compat.screen(mc) == null, String.valueOf(Compat.screen(mc)));
		}
		if (t < start) return false;
		t -= start;
		if (t == 6) shot(mc, "2_" + tag + "_input_running");
		if (AutoInput.running() && t < 20 * 40) return false;
		if (t < 40 || t % 20 != 0) return false; // let the bulbs update
		if (FireControl.wrongCount() != 0 && t < 20 * 45) return false; // panel is re-read every 4 ticks
		FireCalculator.Solution s = FireControl.solution();
		ClientLevel level = mc.level;
		int wrong = 0;
		for (int r = 0; r < 5; r++) {
			for (int c = 0; c < 13; c++) {
				BlockPos b = truth.apply(r, c);
				if (Blocks2.isLit(level.getBlockState(b)) != s.bits[r * 13 + c]) wrong++;
			}
		}
		check(tag + " panel matches CubicMetre layout after InputSettings", wrong == 0, wrong + " wrong after " + t + " ticks");
		check(tag + " mod agrees panel is ready", FireControl.wrongCount() == 0, String.valueOf(FireControl.wrongCount()));
		LOG.info("[E2E] {} InputSettings took {} ticks", tag, t);
		shot(mc, "3_" + tag + "_ready");
		return true;
	}

	private boolean pressInputButton(Minecraft mc, int t, BlockPos panel, double x, double y, double z, float yaw, String tag) {
		if (t == 0) {
			tp(mc, panel, x, y, z, yaw);
			return false;
		}
		if (t < 30) return false;
		Pairing p = FireControl.active();
		if (t == 30) {
			BlockPos input = p.panel.inputButtonPos();
			Vec3 aim = Vec3.atCenterOf(input);
			mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(aim, Direction.EAST, input, false));
			check(tag + " Input Settings click caught at once", p.etaStart == mc.level.getGameTime(), p.etaStart + " vs " + mc.level.getGameTime());
			return false;
		}
		if (t == 36) {
			check(tag + " ETA started by Input Settings button", p.etaStart >= 0, String.valueOf(p.etaStart));
			check(tag + " ETA length matches website", p.etaTotalTicks == FireControl.solution().totalTicksRounded(), String.valueOf(p.etaTotalTicks));
			return false;
		}
		if (t == 40) {
			check(tag + " no longer 'ready' once fired", p.fired && !FireControl.panelReady(), p.fired + " " + FireControl.wrongCount());
			return false;
		}
		if (t == 70) {
			shot(mc, "5_" + tag + "_eta_hud");
			return true;
		}
		return false;
	}

	private static Pairing farPairing() {
		for (Pairing p : FireControl.pairingsHere()) {
			if (p.panel != null && p.panel.center().distanceToSqr(Vec3.atCenterOf(FAR_PANEL)) < 32 * 32) return p;
		}
		return null;
	}

	private void checkFarPairing(String order) {
		Pairing p = farPairing();
		check(order + ": pairing finished", p != null && p.type() == CannonType.MS && p.origin().equals(MS_ORIGIN), p == null ? "null" : p.cannonType + " " + p.origin().toShortString());
		check(order + ": passcode read from the cannon", p != null && p.passcode == MS_PASSCODE && "cannon".equals(p.passcodeSource), p == null ? "null" : p.passcode + " " + p.passcodeSource);
		check(order + ": half pairing cleared", Store.pending() == null, "");
		check(order + ": three pairings stored", FireControl.pairingsHere().size() == 3, String.valueOf(FireControl.pairingsHere().size()));
	}

	private static void unpairFar(Minecraft mc) {
		Pairing p = farPairing();
		if (p != null) Actions.deletePreset(p);
	}

	private static String keyRow(net.minecraft.world.level.Level level, BlockPos origin) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 10; i++) {
			BlockPos one = origin.offset(-14 + i, 11, -16);
			BlockState c = level.getBlockState(one.south());
			sb.append(level.getBlockState(one).is(Blocks.COMPOSTER) ? 'C' : '.')
					.append(level.getBlockState(one.above()).is(Blocks.COMPOSTER) ? 'c' : '.')
					.append(c.getBlock() instanceof ComparatorBlock && c.getValue(ComparatorBlock.POWERED) ? '1' : '0').append(' ');
		}
		return sb.toString();
	}

	private boolean layoutMatches(Pairing p, java.util.function.BiFunction<Integer, Integer, BlockPos> truth) {
		for (int r = 0; r < 5; r++) {
			for (int c = 0; c < 13; c++) {
				BlockPos expected = truth.apply(r, c);
				BlockPos got = p.panel.bulb(r * 13 + c);
				if (!expected.equals(got)) {
					LOG.info("[E2E] layout mismatch at r{} c{}: expected {} got {}", r, c, expected.toShortString(), got == null ? "null" : got.toShortString());
					return false;
				}
			}
		}
		return true;
	}

	private void check(String what, boolean ok) {
		check(what, ok, "");
	}

	private void check(String what, boolean ok, String detail) {
		if (ok) {
			passes++;
			LOG.info("[E2E] PASS {}", what);
		} else {
			fail(what + " (" + detail + ")");
		}
	}

	private void fail(String what) {
		fails++;
		LOG.info("[E2E] FAIL {}", what);
	}

	private static void client(Minecraft mc, String command) {
		try {
			FabricClientCommandSource src = (FabricClientCommandSource) mc.getConnection().getSuggestionsProvider();
			Compat.activeDispatcher().execute(command, src);
		} catch (CommandSyntaxException e) {
			LOG.info("[E2E] FAIL command '{}': {}", command, e.getMessage());
		}
	}

	private static void run(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	private static void tp(Minecraft mc, BlockPos base, double x, double y, double z, float yaw) {
		MinecraftServer server = mc.getSingleplayerServer();
		String cmd = String.format(java.util.Locale.ROOT, "tp @a %.3f %.3f %.3f %.1f 15", base.getX() + x, base.getY() + y, base.getZ() + z, yaw);
		server.execute(() -> run(server, cmd));
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "e2e_" + name + ".png", Compat.mainRenderTarget(mc), 1, c -> LOG.info("[E2E] screenshot {}", c.getString()));
	}

	private static void createWorld(Minecraft mc) {
		try {
			String folder = FileUtil.findAvailableName(mc.getLevelSource().getBaseDir(), "FireControl E2E", "");
			LevelSettings settings = E2ECompat.levelSettings("FireControl E2E");
			mc.createWorldOpenFlows().createFreshLevel(folder, settings, new WorldOptions(1234L, false, false), registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), Compat.screen(mc));
		} catch (Exception e) {
			LOG.error("[E2E] could not create world", e);
		}
	}
}
