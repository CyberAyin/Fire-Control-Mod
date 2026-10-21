package com.firecontrol.world;

import com.firecontrol.calc.PearlCalculator.Axis;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.state.BlockState;

// Finds the pearl cannon by its row of four selector bulbs (any rotation), confirmed by blocks shared
// by the Overworld and Nether schematics.
public final class PearlScanner {
	public static final double MIN_CONFIDENCE = 0.8;
	public static final int RADIUS = 24;

	public record Result(PearlLayout layout, double confidence) {
	}

	private record Point(int dx, int dy, int dz, String block) {
	}

	// dx,dy,dz,block from 360_PearlCannon-Overworld.litematic (same in the Nether one)
	private static final String SIGNATURE = ""
			+ "3,0,7,sticky_piston;-3,1,2,observer;1,1,3,repeater;3,1,3,repeater;-5,1,4,observer;-4,1,4,activator_rail;"
			+ "1,1,4,repeater;-3,1,5,activator_rail;-2,1,5,activator_rail;-2,1,6,activator_rail;0,1,6,activator_rail;"
			+ "1,1,6,repeater;-5,1,8,piston;-2,1,8,piston;4,2,1,comparator;5,2,1,comparator;4,2,2,observer;6,2,4,repeater;"
			+ "7,2,4,repeater;-1,2,5,observer;7,2,5,repeater;0,2,6,observer;1,2,7,observer;-1,3,3,activator_rail;"
			+ "1,3,3,activator_rail;-2,3,4,activator_rail;3,3,4,activator_rail;4,3,4,activator_rail;5,3,4,activator_rail;"
			+ "1,3,5,activator_rail;3,3,5,activator_rail;2,3,6,activator_rail;2,3,8,piston;-1,6,1,slime_block;"
			+ "1,6,8,redstone_block;0,7,0,grindstone;-1,9,-1,grindstone;-1,10,-1,grindstone;-5,10,8,redstone_block;"
			+ "-4,10,8,redstone_block;-3,10,8,redstone_block;-2,10,8,redstone_block;3,10,8,redstone_block;-2,11,1,observer;"
			+ "-5,11,9,sticky_piston;-3,11,9,sticky_piston;-2,12,1,observer;-3,12,8,piston;-2,12,8,piston;1,12,8,piston;"
			+ "-5,12,9,observer;-3,12,9,observer;-2,13,1,observer;-1,14,0,slime_block;-2,14,1,observer;-5,14,8,piston;"
			+ "-2,14,8,piston;-1,15,1,furnace;1,17,8,redstone_block;-1,18,-1,tripwire_hook;1,18,-1,tripwire_hook;"
			+ "2,19,-2,sticky_piston;2,19,0,observer;1,20,0,dispenser;1,21,-2,observer;-2,23,0,observer;-2,24,-1,repeater;"
			+ "-2,28,8,piston;-5,30,8,piston;1,31,9,piston;-4,42,8,redstone_block;0,43,0,slime_block;2,45,-3,observer;"
			+ "2,45,3,redstone_lamp;-1,45,5,redstone_lamp;2,46,2,piston;1,46,8,piston;-2,48,-1,sticky_piston;3,49,2,observer;"
			+ "-5,49,8,redstone_block;-4,51,8,piston;-4,52,-2,activator_rail;0,52,-1,sticky_piston;-4,52,0,activator_rail;"
			+ "-2,52,5,observer;0,57,0,piston;-3,60,-4,redstone_lamp;-3,60,5,activator_rail;2,60,5,activator_rail;"
			+ "-3,61,-4,observer;7,61,0,hopper;-3,61,2,observer;4,62,4,activator_rail;6,64,-3,slime_block;-2,64,2,observer;"
			+ "2,64,2,observer;";

	private static List<Point> points;

	private PearlScanner() {
	}

	private static List<Point> points() {
		if (points == null) {
			List<Point> out = new ArrayList<>();
			for (String entry : SIGNATURE.split(";")) {
				if (entry.isBlank()) continue;
				String[] p = entry.split(",");
				out.add(new Point(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), p[3]));
			}
			points = out;
		}
		return points;
	}

	public static List<Result> scan(ClientLevel level, BlockPos around) {
		LongOpenHashSet bulbs = BlockScan.collect(level, around.offset(-RADIUS, -RADIUS, -RADIUS), around.offset(RADIUS, RADIUS, RADIUS), Blocks2::isBulb);
		List<Result> out = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (long l : bulbs) {
			BlockPos bulb = BlockPos.of(l);
			for (int rot = 0; rot < 4; rot++) {
				// assume this is the West bulb (4, 2, 0)
				BlockPos origin = bulb.offset(-PearlLayout.rotX(rot, 4, 0), -2, -PearlLayout.rotZ(rot, 4, 0));
				if (!seen.add(origin.asLong() * 4 + rot)) continue;
				Result r = matchAt(level, new PearlLayout(origin, rot, false));
				if (r != null && r.confidence() >= MIN_CONFIDENCE) out.add(r);
			}
		}
		out.sort(Comparator.comparingDouble((Result r) -> -r.confidence()).thenComparingDouble(r -> r.layout().origin().distSqr(around)));
		return out;
	}

	public static Result matchAt(ClientLevel level, PearlLayout layout) {
		for (Axis a : Axis.values()) {
			if (!level.isLoaded(layout.bulb(a)) || !Blocks2.isBulb(level.getBlockState(layout.bulb(a)))) return null;
			if (!Blocks2.isButton(level.getBlockState(layout.button(a)))) return null;
		}
		if (!(level.getBlockState(layout.at(-1, 4, -1)).getBlock() instanceof TripWireHookBlock)
				|| !(level.getBlockState(layout.at(1, 4, -1)).getBlock() instanceof TripWireHookBlock)) return null;
		Boolean extended = inputKind(level, layout);
		if (extended == null) return null;
		int total = 0, hit = 0;
		for (Point p : points()) {
			BlockPos pos = layout.at(p.dx, p.dy, p.dz);
			if (!level.isLoaded(pos)) continue;
			total++;
			if (Blocks2.id(level.getBlockState(pos)).equals(p.block)) hit++;
		}
		if (total < points().size() / 2) return null;
		return new Result(new PearlLayout(layout.origin(), layout.rotation, extended), (double) hit / total);
	}

	// false = dropper under the barrel, true = ExtendedInput (two barrels), null = neither
	private static Boolean inputKind(ClientLevel level, PearlLayout l) {
		BlockState low = level.getBlockState(l.at(2, 1, 0)), mid = level.getBlockState(l.at(2, 2, 0)), high = level.getBlockState(l.at(2, 3, 0));
		if (low.getBlock() instanceof DropperBlock && mid.getBlock() instanceof BarrelBlock) return false;
		if (mid.getBlock() instanceof BarrelBlock && high.getBlock() instanceof BarrelBlock) return true;
		return null;
	}
}
