package com.firecontrol.world;

import com.firecontrol.calc.PearlCalculator.Axis;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Danda420 pearl cannon (V3) in schematic coordinates, origin = launch column at floor level.
// Buttons at x 4..7 (W S E N), INPUT and COUNTER ITEMS at x 2, launch hole at x 0.
public final class PearlLayout {
	// schematic origin, Y = cannon floor
	public int x, y, z;
	// quarter turns clockwise from the schematic
	public int rotation;
	// ExtendedInput variant (27-slot INPUT barrel)
	public boolean extendedInput;

	public PearlLayout() {
	}

	public PearlLayout(BlockPos origin, int rotation, boolean extendedInput) {
		this.x = origin.getX();
		this.y = origin.getY();
		this.z = origin.getZ();
		this.rotation = Math.floorMod(rotation, 4);
		this.extendedInput = extendedInput;
	}

	public BlockPos origin() {
		return new BlockPos(x, y, z);
	}

	// same rotation as Litematica's clockwise rotation
	public BlockPos at(int dx, int dy, int dz) {
		return origin().offset(rotX(rotation, dx, dz), dy, rotZ(rotation, dx, dz));
	}

	public Vec3 point(double dx, double dy, double dz) {
		// blocks rotate around the corner of the origin block
		double rx = rotXd(rotation, dx, dz), rz = rotZd(rotation, dx, dz);
		double ox = rotation == 1 || rotation == 2 ? 1 : 0, oz = rotation == 2 || rotation == 3 ? 1 : 0;
		return new Vec3(x + rx + ox, y + dy, z + rz + oz);
	}

	public static int rotX(int rot, int dx, int dz) {
		return switch (Math.floorMod(rot, 4)) {
			case 1 -> -dz;
			case 2 -> -dx;
			case 3 -> dz;
			default -> dx;
		};
	}

	public static int rotZ(int rot, int dx, int dz) {
		return switch (Math.floorMod(rot, 4)) {
			case 1 -> dx;
			case 2 -> -dz;
			case 3 -> -dx;
			default -> dz;
		};
	}

	private static double rotXd(int rot, double dx, double dz) {
		return switch (Math.floorMod(rot, 4)) {
			case 1 -> -dz;
			case 2 -> -dx;
			case 3 -> dz;
			default -> dx;
		};
	}

	private static double rotZd(int rot, double dx, double dz) {
		return switch (Math.floorMod(rot, 4)) {
			case 1 -> dx;
			case 2 -> -dz;
			case 3 -> -dx;
			default -> dz;
		};
	}

	public Direction dir(Direction schematic) {
		Direction d = schematic;
		for (int i = 0; i < rotation; i++) d = d.getClockWise();
		return d;
	}

	public Direction front() {
		return dir(Direction.NORTH);
	}

	// W, S, E, N from left to right
	public static int selectorDx(Axis label) {
		return switch (label) {
			case WEST -> 4;
			case SOUTH -> 5;
			case EAST -> 6;
			case NORTH -> 7;
		};
	}

	public BlockPos bulb(Axis label) {
		return at(selectorDx(label), 2, 0);
	}

	public BlockPos button(Axis label) {
		return at(selectorDx(label), 2, -1);
	}

	public BlockPos sign(Axis label) {
		return at(selectorDx(label), 3, -1);
	}

	public Axis labelFor(Axis world) {
		Axis a = world;
		for (int i = 0; i < rotation; i++) a = counterClockwise(a);
		return a;
	}

	public Axis worldFor(Axis label) {
		Axis a = label;
		for (int i = 0; i < rotation; i++) a = clockwise(a);
		return a;
	}

	private static Axis clockwise(Axis a) {
		return switch (a) {
			case NORTH -> Axis.EAST;
			case EAST -> Axis.SOUTH;
			case SOUTH -> Axis.WEST;
			case WEST -> Axis.NORTH;
		};
	}

	private static Axis counterClockwise(Axis a) {
		return switch (a) {
			case NORTH -> Axis.WEST;
			case WEST -> Axis.SOUTH;
			case SOUTH -> Axis.EAST;
			case EAST -> Axis.NORTH;
		};
	}

	public BlockPos input() {
		return extendedInput ? at(2, 2, 0) : at(2, 1, 0);
	}

	public BlockPos counter() {
		return extendedInput ? at(2, 3, 0) : at(2, 2, 0);
	}

	public int inputSlots() {
		return extendedInput ? 27 : 9;
	}

	public BlockPos tripwire() {
		return at(0, 4, -1);
	}

	public Vec3 throwTarget() {
		return point(0.5, 3.5, -0.5);
	}

	public AABB hole() {
		BlockPos a = at(0, 3, -1), b = at(0, 4, -1);
		return new AABB(Vec3.atLowerCornerOf(a), Vec3.atLowerCornerOf(a).add(1, 1, 1)).minmax(new AABB(b));
	}

	public BlockPos cacheButton() {
		return at(0, 2, -2);
	}

	public BlockPos cacheBulb() {
		return at(-1, 2, -2);
	}

	public AABB operatorArea() {
		Vec3 a = point(-0.5, 0.5, -6), b = point(9.5, 5, 0.2);
		return new AABB(a, b);
	}

	public Vec3 standSpot() {
		return point(3.5, 1, -2.5);
	}

	public AABB bounds() {
		return new AABB(point(-9, 0, -6), point(10, 66, 10));
	}

	// breeze chambers start at y + 58
	public AABB storage() {
		return new AABB(point(-9, 8, -6), point(10, 58, 10));
	}

	public Vec3 center() {
		return point(3.5, 2, -1);
	}
}
