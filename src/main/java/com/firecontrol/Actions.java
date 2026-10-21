package com.firecontrol;

import com.firecontrol.calc.CannonType;
import com.firecontrol.calc.FireCalculator;
import com.firecontrol.calc.FireMode;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import com.firecontrol.world.CannonScanner;
import com.firecontrol.world.PanelLayout;
import com.firecontrol.world.PanelScanner;
import com.firecontrol.world.PearlLayout;
import com.firecontrol.world.PearlScanner;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

// Preset changes shared by the commands and the menu (no chat output)
public final class Actions {
	private Actions() {
	}

	// size is the nuke size, stab depth or SFC payload depending on the mode, null keeps the current one
	public static void setTarget(Pairing p, BlockPos t, FireMode mode, Integer size, Integer slot) {
		CannonType type = p.type();
		if (type.isPearl()) {
			setPearlTarget(p, t, null);
			return;
		}
		if (mode != null) p.mode = mode.id;
		FireMode m = FireMode.byId(p.mode);
		if (type == CannonType.SFC) {
			if (size != null) p.nukeSize = size;
			else if (!isSfcSize(p.nukeSize)) p.nukeSize = 16;
		} else if (size != null) {
			if (m == FireMode.STAB) p.stabDepth = size;
			else p.nukeSize = size;
		} else if (m == FireMode.NUKE && p.nukeSize > 31) {
			p.nukeSize = 10;
		}
		if (type != CannonType.MK6) p.magazineSlot = 0;
		else if (slot != null) p.magazineSlot = slot;
		p.hasTarget = true;
		p.targetX = t.getX();
		p.targetY = t.getY();
		p.targetZ = t.getZ();
		p.autoY = false;
		p.fired = false;
		FireControl.invalidate();
	}

	// dimension: the one the coordinates are in, or null for the current one
	public static void setPearlTarget(Pairing p, BlockPos t, String dimension) {
		Minecraft mc = Minecraft.getInstance();
		p.hasTarget = true;
		p.targetX = t.getX();
		p.targetY = t.getY();
		p.targetZ = t.getZ();
		p.targetDimension = dimension != null ? dimension : mc.level != null ? FireControl.dimensionId(mc.level) : p.dimension;
		p.autoY = false;
		p.fired = false;
		p.loaded = false;
		FireControl.invalidate();
	}

	public static boolean rescanPearl(Pairing p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return false;
		List<PearlScanner.Result> found = PearlScanner.scan(mc.level, mc.player.blockPosition());
		if (found.isEmpty()) return false;
		PearlLayout l = found.get(0).layout();
		p.pearl = l;
		p.originX = l.x;
		p.originY = l.y;
		p.originZ = l.z;
		Store.save();
		FireControl.invalidate();
		return true;
	}

	public static boolean isSfcSize(int n) {
		for (int s : FireCalculator.SFC_PAYLOAD_SIZES) if (s == n) return true;
		return false;
	}

	public static void deletePreset(Pairing p) {
		Store.pairings().remove(p);
		Store.save();
		FireControl.invalidate();
	}

	public static String rename(Pairing p, String name) {
		String n = name.trim();
		if (n.isEmpty()) return "Type a name.";
		for (Pairing q : Store.pairings()) {
			if (q != p && q.name.equalsIgnoreCase(n)) return "Another preset is already called '" + n + "'.";
		}
		String key = Store.worldKey();
		if (p.name.equals(Store.data().selected.get(key))) Store.data().selected.put(key, n);
		p.name = n;
		Store.save();
		return null;
	}

	public static void select(Pairing p) {
		Store.data().selected.put(Store.worldKey(), p.name);
		Store.save();
		FireControl.invalidate();
	}

	public static boolean readPasscode(Pairing p) {
		Minecraft mc = Minecraft.getInstance();
		CannonScanner.PasscodeRead read = mc.level == null ? null : CannonScanner.readPasscode(mc.level, p.origin());
		if (read == null) return false;
		p.passcode = read.passcode();
		p.passcodeSource = "cannon";
		Store.save();
		FireControl.invalidate();
		return true;
	}

	public static void setPasscode(Pairing p, int code) {
		p.passcode = code;
		p.passcodeSource = "manual";
		Store.save();
		FireControl.invalidate();
	}

	public static boolean rescanPanel(Pairing p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return false;
		List<PanelLayout> panels = PanelScanner.scan(mc.level, mc.player).stream().filter(l -> l.matchesType(p.type())).toList();
		if (panels.isEmpty()) return false;
		p.panel = panels.get(0);
		Store.save();
		FireControl.invalidate();
		return true;
	}
}
