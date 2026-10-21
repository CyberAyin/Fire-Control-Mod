package com.firecontrol;

import com.firecontrol.calc.CannonType;
import com.firecontrol.calc.FireCalculator;
import com.firecontrol.calc.FireMode;
import com.firecontrol.calc.Passcodes;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import com.firecontrol.world.Blocks2;
import com.firecontrol.world.CannonScanner;
import com.firecontrol.world.PanelLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class FireControl {
	private static final int CHECK_INTERVAL = 4;
	private static final int PASSCODE_INTERVAL = 20;
	public static final double PANEL_RANGE = 64;

	private static Pairing active;
	private static FireCalculator.Solution solution;
	private static boolean solutionDirty = true;

	private static boolean[] known = new boolean[0];
	private static boolean[] lit = new boolean[0];
	private static int wrongCount = -1;
	private static boolean lastAllCorrect;
	private static Boolean lastInputPowered;
	private static boolean[] bitsBeforePress;
	// so a click and the button state change only count once
	private static long lastPressTime = -1000;
	private static Pairing inputWatchFor;
	private static int tickCounter;
	private static int passcodeCounter;
	private static final Set<Pairing> warnedPasscode = Collections.newSetFromMap(new IdentityHashMap<>());
	public static boolean overlayEnabled = true;

	private FireControl() {
	}

	public static String dimensionId(ClientLevel level) {
		return level.dimension().identifier().toString();
	}

	public static List<Pairing> pairingsHere() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return List.of();
		String dim = dimensionId(mc.level);
		return Store.pairings().stream().filter(p -> dim.equals(p.dimension)).toList();
	}

	// With several presets the panel the player is at decides (using the picked preset if it has more than one).
	// Away from every panel: the picked preset, then the nearest panel.
	public static Pairing resolveActive() {
		Minecraft mc = Minecraft.getInstance();
		List<Pairing> here = pairingsHere();
		if (here.isEmpty() || mc.player == null) return null;
		if (here.size() == 1) return here.get(0);
		String sel = Store.data().selected.get(Store.worldKey());
		Pairing picked = null;
		for (Pairing p : here) {
			if (p.name.equals(sel)) picked = p;
		}
		Vec3 pos = mc.player.position();
		Pairing atPanel = null;
		for (Pairing p : here) {
			if (!p.atControls(pos)) continue;
			if (p == picked) return p;
			if (atPanel == null) atPanel = p;
		}
		if (atPanel != null) return atPanel;
		if (picked != null) return picked;
		Pairing best = null;
		double bestD = Double.MAX_VALUE;
		for (Pairing p : here) {
			Vec3 c = p.controlsCenter();
			if (c == null) continue;
			double d = c.distanceToSqr(pos);
			if (d < bestD) {
				bestD = d;
				best = p;
			}
		}
		return best != null ? best : here.get(0);
	}

	public static Pairing active() {
		return active;
	}

	public static Pairing netherPearlFromOverworld() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !"minecraft:overworld".equals(dimensionId(mc.level))) return null;
		List<Pairing> pearls = Store.pairings().stream().filter(p -> p.isPearl() && Pairing.NETHER.equals(p.dimension)).toList();
		if (pearls.isEmpty()) return null;
		String sel = Store.data().selected.get(Store.worldKey());
		for (Pairing p : pearls) if (p.name.equals(sel)) return p;
		return pearls.size() == 1 ? pearls.get(0) : null;
	}

	public static FireCalculator.Solution solution() {
		return solution;
	}

	public static void invalidate() {
		solutionDirty = true;
		wrongCount = -1;
		tickCounter = CHECK_INTERVAL;
		PearlControl.invalidate();
	}

	public static boolean isKnown(int i) {
		return i < known.length && known[i];
	}

	public static boolean isLit(int i) {
		return i < lit.length && lit[i];
	}

	public static boolean isWrong(int i) {
		return solution != null && isKnown(i) && i < solution.bits.length && lit[i] != solution.bits[i];
	}

	// -1 = unknown
	public static int wrongCount() {
		return wrongCount;
	}

	public static boolean panelReady() {
		return wrongCount == 0;
	}

	public static boolean playerNearPanel() {
		Minecraft mc = Minecraft.getInstance();
		Vec3 c = active == null ? null : active.controlsCenter();
		return c != null && mc.player != null && c.distanceToSqr(mc.player.position()) < PANEL_RANGE * PANEL_RANGE;
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			active = null;
			solution = null;
			return;
		}
		if (++passcodeCounter >= PASSCODE_INTERVAL) {
			passcodeCounter = 0;
			readMissingPasscodes(mc.level);
		}
		Pairing now = resolveActive();
		if (now != active) {
			active = now;
			invalidate();
			lastAllCorrect = false;
		}
		if (active == null) {
			solution = null;
			wrongCount = -1;
			return;
		}
		if (active.isPearl()) {
			solution = null;
			wrongCount = -1;
			PearlControl.tick(mc, active);
			Eta.tick(mc.level);
			return;
		}
		if (solutionDirty) {
			solution = active.solve();
			solutionDirty = false;
			int n = active.panel == null ? 0 : active.panel.size();
			known = new boolean[n];
			lit = new boolean[n];
		}

		watchInputButton(mc.level, mc.player);
		Eta.tick(mc.level);

		if (++tickCounter < CHECK_INTERVAL) return;
		tickCounter = 0;
		readPanel(mc.level, mc.player);
	}

	private static void readPanel(ClientLevel level, LocalPlayer player) {
		PanelLayout panel = active.panel;
		if (panel == null || solution == null || !panel.matchesType(active.type()) || !playerNearPanel()) {
			wrongCount = -1;
			return;
		}
		if (active.fired) {
			wrongCount = -1;
			lastAllCorrect = false;
			return;
		}
		int wrong = 0;
		boolean allKnown = true;
		for (int i = 0; i < panel.size(); i++) {
			BlockPos p = panel.bulb(i);
			BlockState st = p == null || !level.isLoaded(p) ? null : level.getBlockState(p);
			known[i] = st != null && Blocks2.isBulb(st);
			lit[i] = known[i] && Blocks2.isLit(st);
			if (!known[i]) {
				allKnown = false;
				continue;
			}
			if (lit[i] != solution.bits[i]) wrong++;
		}
		int before = wrongCount;
		wrongCount = allKnown || wrong > 0 ? wrong : -1;
		boolean allCorrect = wrongCount == 0;
		if (allCorrect && !lastAllCorrect && solution.isValid()) {
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.6f));
		} else if (!allCorrect && lastAllCorrect && before == 0 && wrongCount > 0) {
			Msg.warn("⚠ " + wrongCount + " bulb(s) changed and no longer match the solution.");
		}
		lastAllCorrect = allCorrect;
	}

	// Cannons paired from far away get their passcode as soon as the key chunk loads.
	private static void readMissingPasscodes(ClientLevel level) {
		String dim = dimensionId(level);
		List<Pairing> check = new ArrayList<>(pairingsHere());
		Pairing pending = Store.pending();
		if (pending != null && pending.hasCannon() && dim.equals(pending.dimension)) check.add(pending);
		for (Pairing p : check) {
			if (!p.type().hasPasscode() || "cannon".equals(p.passcodeSource)) continue;
			if ("manual".equals(p.passcodeSource) && warnedPasscode.contains(p)) continue;
			CannonScanner.PasscodeRead read = CannonScanner.readPasscode(level, p.origin());
			if (read == null) continue;
			String who = p == pending ? "the saved cannon" : "cannon '" + p.name + "'";
			if ("manual".equals(p.passcodeSource)) {
				if (read.passcode() != p.passcode) {
					Msg.warn("The composter key on " + who + " reads " + read.passcode() + ", but you set the passcode to " + p.passcode
							+ " by hand. Use \"Read from cannon\" in /firecontrol → More to switch.");
				}
				warnedPasscode.add(p);
				continue;
			}
			p.passcode = read.passcode();
			p.passcodeSource = "cannon";
			Store.save();
			invalidate();
			Msg.ok("✔ Read the passcode of " + who + " from its composter key: " + p.passcode + ".");
			if (!Passcodes.isValid(p.passcode)) Msg.warn("That code isn't in CubicMetre's good-code list; it may misfire.");
		}
	}

	private static void watchInputButton(ClientLevel level, LocalPlayer player) {
		BlockPos pos = active.panel == null ? null : active.panel.inputButtonPos();
		if (pos == null || !level.isLoaded(pos)) {
			lastInputPowered = null;
			return;
		}
		boolean powered = Blocks2.isPressed(level.getBlockState(pos));
		if (inputWatchFor != active) {
			inputWatchFor = active;
			lastInputPowered = powered;
			bitsBeforePress = null;
			return;
		}
		if (lastInputPowered != null && !lastInputPowered && powered) pressed(level);
		// panel state the tick before the press
		if (!powered) bitsBeforePress = readBits(level, active.panel);
		lastInputPowered = powered;
	}

	// Catches our own Input Settings press right away. Other players' presses are caught by watching the button.
	public static void onUseBlock(ClientLevel level, BlockPos pos) {
		if (active == null || active.panel == null || !pos.equals(active.panel.inputButtonPos())) return;
		if (Blocks2.isPressed(level.getBlockState(pos))) return; // already down, the click does nothing
		pressed(level);
	}

	private static void pressed(ClientLevel level) {
		long now = level.getGameTime();
		if (Math.abs(now - lastPressTime) < 30) return;
		lastPressTime = now;
		onInputSettingsPressed(level, bitsBeforePress);
	}

	private static boolean[] readBits(ClientLevel level, PanelLayout panel) {
		if (panel == null) return null;
		boolean[] bits = new boolean[panel.size()];
		for (int i = 0; i < bits.length; i++) {
			BlockPos b = panel.bulb(i);
			BlockState st = b == null || !level.isLoaded(b) ? null : level.getBlockState(b);
			if (st == null || !Blocks2.isBulb(st)) return null;
			bits[i] = Blocks2.isLit(st);
		}
		return bits;
	}

	// The ETA follows what the panel actually shows. Settings entered by hand are decoded and become the target.
	public static boolean onInputSettingsPressed(ClientLevel level, boolean[] bits) {
		Pairing p = active;
		if (p == null) return false;
		if (p.panel == null || !p.panel.matchesType(p.type())) bits = null;
		else if (bits == null || bits.length != p.panel.size()) bits = readBits(level, p.panel);
		FireCalculator.Solution shot = solution;
		if (bits != null && (shot == null || !java.util.Arrays.equals(bits, shot.bits))) {
			shot = FireCalculator.decode(p.type(), p.originX, p.originY, p.originZ, bits, p.request());
			FireCalculator.Request r = shot.request;
			p.hasTarget = true;
			p.targetX = r.x();
			p.targetY = r.y();
			p.targetZ = r.z();
			p.mode = r.mode().id;
			if (r.mode() == FireMode.STAB) p.stabDepth = r.stabDepth();
			else p.nukeSize = r.nukeSize();
			invalidate();
			String payload = p.type() == CannonType.SFC ? "payload " + r.nukeSize() : r.mode() == FireMode.NUKE ? "nuke " + r.nukeSize() : "stab ~" + r.stabDepth();
			Msg.send(Msg.text("➤ Fired the panel's own settings: ", ChatFormatting.GOLD)
					.append(Msg.text(String.format("lands at %.0f %.0f %.0f", shot.landX, shot.landY, shot.landZ), ChatFormatting.WHITE))
					.append(Msg.text(" · " + payload, ChatFormatting.GRAY)));
			if (p.type() != CannonType.SFC && p.passcodeKnown()) {
				int code = FireCalculator.decodePasscode(bits);
				if (code != p.passcode) Msg.warn("⚠ The panel is set to passcode " + code + ", but the cannon's is " + p.passcode + ". The cannon will likely ignore this shot.");
			}
		} else if (shot == null) {
			Msg.warn("Input Settings pressed, but the mod couldn't read the panel and no target is set, so no ETA.");
			return false;
		} else if (!shot.isValid()) {
			Msg.error("Input Settings pressed, but the current solution is invalid: " + String.join("; ", shot.errors));
			return false;
		}
		Eta.start(p, shot, level.getGameTime());
		p.fired = true;
		Store.save();
		wrongCount = -1;
		return true;
	}

	public static MutableComponent describePending(Pairing half) {
		if (half.hasCannon()) {
			BlockPos o = half.origin();
			return Msg.text("cannon (" + half.type().displayName + " @ " + o.getX() + " " + o.getY() + " " + o.getZ()
					+ (!half.type().hasPasscode() ? "" : ", passcode " + (half.passcodeKnown() ? half.passcode : "not read yet")) + ")", ChatFormatting.GRAY);
		}
		BlockPos c = half.panel == null ? BlockPos.ZERO : BlockPos.containing(half.panel.center());
		return Msg.text("panel (" + (half.panel != null && half.panel.isGrid() ? "13×5 bulbs" : "SFC") + " at " + c.getX() + " " + c.getY() + " " + c.getZ() + ")", ChatFormatting.GRAY);
	}

	public static Component describe(Pairing p) {
		BlockPos o = p.origin();
		return Msg.text(p.name, ChatFormatting.WHITE)
				.append(Msg.text(" · " + p.type().displayName + " @ " + o.getX() + " " + o.getY() + " " + o.getZ()
						+ (!p.type().hasPasscode() ? "" : " · passcode " + p.passcode + (p.passcodeKnown() ? "" : " (not read yet)")), ChatFormatting.GRAY));
	}
}
