package com.firecontrol.world;

import com.firecontrol.calc.CannonType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class CannonScanner {
	public static final double MIN_CONFIDENCE = 0.75;
	// passcode key: 10 composters read by comparators at dz -15, MSB at dx -14
	private static final int KEY_DX = -14, KEY_DY = 11, KEY_DZ = -16;

	private record Point(int dx, int dy, int dz, Block block) {
	}

	public record Result(BlockPos origin, CannonType type, double confidence) {
	}

	public record PasscodeRead(int passcode, boolean fromComparators) {
	}

	private static List<Point> common, mk6, ms;

	private CannonScanner() {
	}

	private static List<Point> parse(String data) {
		List<Point> out = new ArrayList<>();
		for (String entry : data.split(";")) {
			if (entry.isBlank()) continue;
			String[] p = entry.split(",");
			Block block = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(p[3]));
			out.add(new Point(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), block));
		}
		return out;
	}

	private static void ensureParsed() {
		if (common == null) {
			common = parse(CannonSignatures.COMMON);
			mk6 = parse(CannonSignatures.MK6);
			ms = parse(CannonSignatures.MS);
		}
	}

	public static List<Result> scan(ClientLevel level, BlockPos around, int radius) {
		ensureParsed();
		List<Result> results = new ArrayList<>();
		int minX = Mth.floor((around.getX() - radius) / 16.0) * 16, maxX = around.getX() + radius;
		int minZ = Mth.floor((around.getZ() - radius) / 16.0) * 16, maxZ = around.getZ() + radius;
		int minY = Mth.floor(level.getMinY() / 16.0) * 16, maxY = level.getMaxY();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = minX; x <= maxX; x += 16) {
			for (int z = minZ; z <= maxZ; z += 16) {
				if (!BlockScan.loaded(level, x >> 4, z >> 4)) continue;
				for (int y = minY; y <= maxY; y += 16) {
					Result r = scoreAt(level, x, y, z, cursor);
					if (r != null && r.confidence() >= MIN_CONFIDENCE) results.add(r);
				}
			}
		}
		results.sort(Comparator.comparingDouble((Result r) -> -r.confidence()).thenComparingDouble(r -> r.origin().distSqr(around)));
		return results;
	}

	// null when that part of the world is not loaded
	public static Result matchAt(ClientLevel level, BlockPos origin) {
		ensureParsed();
		if (!level.isLoaded(origin) || !keyLoaded(level, origin)) return null;
		Result r = scoreAt(level, origin.getX(), origin.getY(), origin.getZ(), new BlockPos.MutableBlockPos());
		return r != null ? r : new Result(origin, null, 0);
	}

	private static Result scoreAt(ClientLevel level, int x, int y, int z, BlockPos.MutableBlockPos cursor) {
		// quick reject on the first few blocks
		int[] quick = score(level, common.subList(0, 8), x, y, z, cursor);
		if (quick[1] < 4 || quick[0] < quick[1] * 0.6) return null;
		int[] c = score(level, common, x, y, z, cursor);
		int[] a = score(level, mk6, x, y, z, cursor);
		int[] b = score(level, ms, x, y, z, cursor);
		double confMk6 = ratio(c[0] + a[0], c[1] + a[1]);
		double confMs = ratio(c[0] + b[0], c[1] + b[1]);
		boolean isMs = ratio(b[0], b[1]) > ratio(a[0], a[1]);
		return new Result(new BlockPos(x, y, z), isMs ? CannonType.MS : CannonType.MK6, isMs ? confMs : confMk6);
	}

	public static boolean keyLoaded(ClientLevel level, BlockPos origin) {
		return level.isLoaded(origin.offset(KEY_DX, KEY_DY, KEY_DZ)) && level.isLoaded(origin.offset(KEY_DX + 9, KEY_DY, KEY_DZ + 1));
	}

	private static int[] score(ClientLevel level, List<Point> points, int x, int y, int z, BlockPos.MutableBlockPos cursor) {
		int match = 0, total = 0;
		for (Point p : points) {
			int px = x + p.dx(), py = y + p.dy(), pz = z + p.dz();
			if (py < level.getMinY() || py > level.getMaxY() || !BlockScan.loaded(level, px >> 4, pz >> 4)) continue;
			total++;
			if (level.getBlockState(cursor.set(px, py, pz)).is(p.block())) match++;
		}
		return new int[] {match, total};
	}

	private static double ratio(int a, int b) {
		return b == 0 ? 0 : (double) a / b;
	}

	// composter in the "1" slot (comparator) or one block up in the "0" slot, the comparator wins if present
	public static PasscodeRead readPasscode(ClientLevel level, BlockPos origin) {
		if (!keyLoaded(level, origin)) return null;
		int code = 0;
		int known = 0;
		boolean comparators = true;
		for (int i = 0; i < 10; i++) {
			BlockPos keyPos = origin.offset(KEY_DX + i, KEY_DY, KEY_DZ);
			BlockState key = level.getBlockState(keyPos);
			BlockState comparator = level.getBlockState(keyPos.south());
			boolean bit;
			if (comparator.getBlock() instanceof ComparatorBlock) {
				bit = comparator.getValue(ComparatorBlock.POWERED);
				known++;
			} else if (key.getBlock() instanceof ComposterBlock) {
				bit = key.getValue(ComposterBlock.LEVEL) > 0;
				comparators = false;
				known++;
			} else {
				bit = false;
				comparators = false;
			}
			if (bit) code |= 1 << (9 - i);
		}
		return known >= 8 ? new PasscodeRead(code, comparators) : null;
	}
}
