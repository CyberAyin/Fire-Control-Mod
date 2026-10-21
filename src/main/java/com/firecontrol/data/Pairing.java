package com.firecontrol.data;

import com.firecontrol.calc.CannonType;
import com.firecontrol.calc.FireCalculator;
import com.firecontrol.calc.FireMode;
import com.firecontrol.calc.PearlCalculator;
import com.firecontrol.world.PanelLayout;
import com.firecontrol.world.PearlLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// A panel + cannon pairing (shown as a preset) with its target and ETA. A half-finished pairing
// only has one side set. Pearl cannons have no panel.
public final class Pairing {
	public String name;
	public String dimension;
	public String cannonType;
	public int originX, originY, originZ;
	public int passcode;
	// "cannon", "manual" or "default"
	public String passcodeSource;
	public PanelLayout panel;
	public PearlLayout pearl;

	public boolean hasTarget;
	public int targetX, targetY, targetZ;
	// targetY came from GroundHeight (or the pearl cannon floor)
	public boolean autoY;
	public String mode = "nuke";
	public int nukeSize = 10;
	public int stabDepth = 70;
	public int magazineSlot = 0;
	// pearl cannon: the dimension the target coordinates are in
	public String targetDimension;
	public boolean fired;
	public boolean loaded;

	// ETA in game time, so it survives lag and relogs
	public long etaStart = -1;
	public int etaTotalTicks;
	public int etaPayloadTicks;

	public boolean hasCannon() {
		return cannonType != null;
	}

	public CannonType type() {
		CannonType t = CannonType.byId(cannonType);
		return t == null ? CannonType.MK6 : t;
	}

	public BlockPos origin() {
		return new BlockPos(originX, originY, originZ);
	}

	public void setCannon(CannonType type, BlockPos origin, int passcode, String passcodeSource) {
		cannonType = type.id;
		originX = origin.getX();
		originY = origin.getY();
		originZ = origin.getZ();
		this.passcode = passcode;
		this.passcodeSource = passcodeSource;
	}

	public boolean passcodeKnown() {
		return !"default".equals(passcodeSource);
	}

	public FireCalculator.Request request() {
		FireMode m = FireMode.byId(mode);
		return new FireCalculator.Request(targetX, targetY, targetZ, m == null ? FireMode.NUKE : m, nukeSize, stabDepth, magazineSlot);
	}

	public FireCalculator.Solution solve() {
		if (!hasTarget || type().isPearl()) return null;
		return FireCalculator.solve(type(), originX, originY, originZ, passcode, request());
	}

	public boolean isPearl() {
		return type().isPearl() && pearl != null;
	}

	// overworld coordinates are divided by 8 for a nether cannon (and the other way round)
	public PearlCalculator.Solution solvePearl() {
		if (!hasTarget || !isPearl()) return null;
		int tx = targetX, tz = targetZ;
		boolean convert = netherConversion();
		if (!convert && NETHER.equals(targetDimension) && !NETHER.equals(dimension)) {
			tx *= 8;
			tz *= 8;
		}
		int landY = convert ? originY - 1 : targetY - 1;
		return PearlCalculator.solve(originX, originY, originZ, tx, landY, tz, convert);
	}

	public static final String NETHER = "minecraft:the_nether";

	public boolean netherConversion() {
		return NETHER.equals(dimension) && targetDimension != null && !NETHER.equals(targetDimension);
	}

	public String groundDimension(String coordsDimension) {
		if (isPearl() && NETHER.equals(dimension) && coordsDimension != null && !NETHER.equals(coordsDimension)) return null;
		return dimension;
	}

	public AABB operatorArea() {
		if (panel != null) return panel.operatorArea();
		if (pearl != null) return pearl.operatorArea();
		return null;
	}

	public boolean atControls(Vec3 pos) {
		AABB area = operatorArea();
		return area != null && area.contains(pos);
	}

	public Vec3 controlsCenter() {
		if (panel != null) return panel.center();
		if (pearl != null) return pearl.center();
		return null;
	}
}
