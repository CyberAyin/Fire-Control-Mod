package com.firecontrol.world;

import com.firecontrol.calc.CannonType;
import com.firecontrol.calc.FireCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Positions are stored as BlockPos longs for Gson. Cell index = bit index: grid row*13+col (col 0 = left),
// SFC 0-14 orange, 15-29 blue, 30/31 sign bulbs.
public final class PanelLayout {
	public static final long NONE = Long.MIN_VALUE;

	// "grid" (Mk6/MS) or "sfc"
	public String kind;
	public long[] bulbs;
	public long[] buttons;
	public long inputButton = NONE;
	public String front;
	// from the signs, may be null
	public String signHint;

	public boolean isGrid() {
		return "grid".equals(kind);
	}

	public int size() {
		return bulbs.length;
	}

	public BlockPos bulb(int i) {
		return bulbs[i] == NONE ? null : BlockPos.of(bulbs[i]);
	}

	public BlockPos button(int i) {
		return buttons[i] == NONE ? null : BlockPos.of(buttons[i]);
	}

	public BlockPos inputButtonPos() {
		return inputButton == NONE ? null : BlockPos.of(inputButton);
	}

	public Direction frontDir() {
		Direction d = front == null ? null : Direction.byName(front);
		return d == null ? Direction.NORTH : d;
	}

	public boolean matchesType(CannonType type) {
		return !type.isPearl() && type.usesGridPanel() == isGrid();
	}

	public AABB bounds() {
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (long[] arr : new long[][] {bulbs, buttons, {inputButton}}) {
			for (long l : arr) {
				if (l == NONE) continue;
				BlockPos p = BlockPos.of(l);
				minX = Math.min(minX, p.getX());
				minY = Math.min(minY, p.getY());
				minZ = Math.min(minZ, p.getZ());
				maxX = Math.max(maxX, p.getX());
				maxY = Math.max(maxY, p.getY());
				maxZ = Math.max(maxZ, p.getZ());
			}
		}
		return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
	}

	// panel plus a few blocks in front of it, InputSettings only runs in here
	public AABB operatorArea() {
		AABB box = bounds().inflate(2, 3, 2);
		Direction f = frontDir();
		return box.expandTowards(f.getStepX() * 6.0, f.getStepY() * 6.0, f.getStepZ() * 6.0);
	}

	public Vec3 center() {
		return bounds().getCenter();
	}

	public boolean sameBulbsAs(PanelLayout other) {
		if (other.bulbs.length != bulbs.length) return false;
		java.util.Set<Long> mine = new java.util.HashSet<>();
		for (long l : bulbs) if (l != NONE) mine.add(l);
		for (long l : other.bulbs) if (l != NONE && !mine.contains(l)) return false;
		return !mine.isEmpty();
	}

	public void flip() {
		if (isGrid()) {
			int rows = FireCalculator.GRID_ROWS, cols = FireCalculator.GRID_COLS;
			for (int r = 0; r < rows; r++) {
				for (int c = 0; c < cols / 2; c++) {
					swap(r * cols + c, r * cols + (cols - 1 - c));
				}
			}
		} else {
			for (int i = 0; i < 7; i++) {
				swap(i, 14 - i);
				swap(15 + i, 29 - i);
			}
		}
	}

	private void swap(int a, int b) {
		long t = bulbs[a];
		bulbs[a] = bulbs[b];
		bulbs[b] = t;
		t = buttons[a];
		buttons[a] = buttons[b];
		buttons[b] = t;
	}
}
