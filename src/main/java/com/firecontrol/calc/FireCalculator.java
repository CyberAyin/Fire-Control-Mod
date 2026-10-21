package com.firecontrol.calc;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of CubicMetre's osc-fire-control (src/utils/calculations.ts and validation.ts, GPL-3.0).
 * Constants and rounding match the original so the panel bits come out the same.
 */
public final class FireCalculator {
	// Constants from the fire control spreadsheet
	private static final double DV_PLUS_X = 8.755553287151535;
	private static final double DV_PLUS_Y = 8.816216069168268;
	private static final double DV_PLUS_Z = 8.755553287151535;
	private static final double DV_MINUS_X = -8.664591055789826;
	private static final double DV_MINUS_Z = -8.664591055789826;
	private static final double DV_YX = -0.596968396919667;
	private static final double DY_MS = 3.51;
	private static final double DY_STAB = 106.92 + 44.4;
	private static final double DY_NUKE = 140;
	private static final double DX = 22.5000000095367;
	private static final double DZ = 0.9999999904633 - 1;
	private static final double DXZ_MS = 8.43750000953673;
	private static final double SLOWDOWN_XZ = 0.900000035762786;
	private static final double SLOWDOWN_Y = 1.5;

	public static final double SFC_SCALE = 100.0 / 1233.0;
	public static final int SFC_MAX_MAGNITUDE = 32767;
	public static final int[] SFC_PAYLOAD_SIZES = {16, 64, 256, 1024};
	private static final int SFC_POST_ACCEL_TICKS = 74;

	public static final int GRID_ROWS = 5;
	public static final int GRID_COLS = 13;
	// SFC bits: 0-14 orange (MSB first), 15-29 blue (MSB first), 30 orange negative, 31 blue negative
	public static final int SFC_BITS = 32;
	public static final int SFC_ORANGE_SIGN = 30;
	public static final int SFC_BLUE_SIGN = 31;

	private FireCalculator() {
	}

	public record Request(int x, int y, int z, FireMode mode, int nukeSize, int stabDepth, int magazineSlot) {
		public int payloadValue() {
			return mode == FireMode.NUKE ? nukeSize : stabDepth;
		}
	}

	public static final class Solution {
		public final CannonType type;
		public final Request request;
		// grid: index row*13+col
		public final boolean[] bits;
		public int coarseX, coarseY, coarseZ;
		public int fineX, fineY, fineZ;
		public int sfcOrange, sfcBlue;
		public double landX, landY, landZ;
		public double payloadTicks, accelerationTicks, totalTicks;
		double countX, countY, countZ, diffY;
		public final List<String> errors = new ArrayList<>();
		public final List<String> warnings = new ArrayList<>();

		Solution(CannonType type, Request request, int bitCount) {
			this.type = type;
			this.request = request;
			this.bits = new boolean[bitCount];
		}

		public boolean isValid() {
			return errors.isEmpty();
		}

		public int totalTicksRounded() {
			return (int) Math.ceil(totalTicks);
		}
	}

	public static Solution solve(CannonType type, int originX, int originY, int originZ, int passcode, Request req) {
		Solution s = type == CannonType.SFC
				? solveSfc(originX, originY, originZ, req)
				: solveGrid(type, originX, originY, originZ, passcode, req);
		validate(type, originX, originY, originZ, passcode, req, s);
		return s;
	}

	private static Solution solveGrid(CannonType type, int ox, int oy, int oz, int passcode, Request req) {
		Solution s = new Solution(type, req, GRID_ROWS * GRID_COLS);
		boolean ms = type == CannonType.MS;
		boolean nuke = req.mode() == FireMode.NUKE;

		double dvX = req.x() > ox ? DV_PLUS_X : DV_MINUS_X;
		double dvZ = req.z() > oz ? DV_PLUS_Z : DV_MINUS_Z;

		double baseDY = nuke ? DY_NUKE : DY_STAB;
		double dy = ms ? baseDY - DY_MS : baseDY;

		double dX = ms ? DXZ_MS : DX;
		double dZ = ms ? DXZ_MS : DZ;
		double iX = ox + dX + req.magazineSlot();
		double iY = oy + 2.1975;
		double iZ = oz + dZ;

		double diffY = (dy + req.y()) - iY;
		if (diffY < 50) diffY = 50;

		double countY = diffY / (SLOWDOWN_Y * DV_PLUS_Y);
		int coarseY = (int) Math.floor(countY);
		int fineY = (int) Math.round((countY - coarseY) * 10);

		double diffX = req.x() - (iX + (coarseY + fineY / 10.0) * DV_YX);
		double diffZ = req.z() - iZ;

		double countX = diffX / (SLOWDOWN_XZ * dvX);
		double countZ = diffZ / (SLOWDOWN_XZ * dvZ);

		int coarseX = (int) Math.floor(Math.abs(countX));
		int fineX = (int) Math.round((Math.abs(countX) - coarseX) * 10);
		int coarseZ = (int) Math.floor(Math.abs(countZ));
		int fineZ = (int) Math.round((Math.abs(countZ) - coarseZ) * 10);

		double vX = dvX * (coarseX + fineX / 10.0) + DV_YX * (coarseY + fineY / 10.0);
		double vY = DV_PLUS_Y * (coarseY + fineY / 10.0);
		double vZ = dvZ * (coarseZ + fineZ / 10.0);

		s.coarseX = coarseX;
		s.coarseY = coarseY;
		s.coarseZ = coarseZ;
		s.fineX = fineX;
		s.fineY = fineY;
		s.fineZ = fineZ;
		s.landX = iX + SLOWDOWN_XZ * vX;
		s.landY = iY + SLOWDOWN_Y * vY;
		s.landZ = iZ + SLOWDOWN_XZ * vZ;

		int payloadSize = nuke ? req.nukeSize() : (int) Math.ceil(req.stabDepth() / (9 * 0.99));

		int[][] grid = {
			{
				bit(coarseZ, 1), bit(coarseZ, 32), bit(coarseZ, 1024), inv(fineZ, 8), inv(fineY, 8), bit(coarseY, 8), inv(fineX, 8),
				bit(coarseX, 8192), bit(coarseX, 256), bit(coarseX, 8), bit(payloadSize, 2), bit(passcode, 512), bit(passcode, 16)
			},
			{
				bit(coarseZ, 2), bit(coarseZ, 64), bit(coarseZ, 2048), inv(fineZ, 4), inv(fineY, 4), bit(coarseY, 4), inv(fineX, 4),
				bit(coarseX, 4096), bit(coarseX, 128), bit(coarseX, 4), bit(payloadSize, 4), bit(passcode, 256), bit(passcode, 8)
			},
			{
				bit(coarseZ, 4), bit(coarseZ, 128), bit(coarseZ, 4096), inv(fineZ, 2), inv(fineY, 2), bit(coarseY, 2), inv(fineX, 2),
				bit(coarseX, 2048), bit(coarseX, 64), bit(coarseX, 2), bit(payloadSize, 8), bit(passcode, 128), bit(passcode, 4)
			},
			{
				bit(coarseZ, 8), bit(coarseZ, 256), bit(coarseZ, 8192), inv(fineZ, 1), inv(fineY, 1), bit(coarseY, 1), inv(fineX, 1),
				bit(coarseX, 1024), bit(coarseX, 32), bit(coarseX, 1), bit(payloadSize, 16), bit(passcode, 64), bit(passcode, 2)
			},
			{
				bit(coarseZ, 16), bit(coarseZ, 512), bit(coarseZ, 16384), diffZ < 0 ? 1 : 0, bit(coarseY, 16), diffX < 0 ? 1 : 0,
				bit(coarseX, 16384), bit(coarseX, 512), bit(coarseX, 16), bit(payloadSize, 1), nuke ? 1 : 0, bit(passcode, 32), bit(passcode, 1)
			}
		};
		for (int r = 0; r < GRID_ROWS; r++) {
			for (int c = 0; c < GRID_COLS; c++) {
				s.bits[r * GRID_COLS + c] = grid[r][c] == 1;
			}
		}

		double accel = 6.0 * (Math.max(coarseX, coarseZ) + coarseY + 2);
		double payload = nuke
				? 6.0 * ((req.nukeSize() * (req.nukeSize() + 1)) / 2.0 + req.nukeSize() * 2.4375) + 930
				: 6.0 * (2 * Math.round(req.stabDepth() / (9 * 0.99))) + 1300;
		s.payloadTicks = payload;
		s.accelerationTicks = accel;
		s.totalTicks = payload + accel;

		// raw counts are only needed for validation
		s.countX = countX;
		s.countY = countY;
		s.countZ = countZ;
		s.diffY = diffY;
		return s;
	}

	private static Solution solveSfc(int ox, int oy, int oz, Request req) {
		Solution s = new Solution(CannonType.SFC, req, SFC_BITS);
		double dx = req.x() - ox;
		double dz = req.z() - oz;
		int orange = (int) Math.round(SFC_SCALE * (dx + dz));
		int blue = (int) Math.round(SFC_SCALE * (dx - dz));
		s.sfcOrange = orange;
		s.sfcBlue = blue;
		boolean[] ob = to15BitsMsbLeft(orange);
		boolean[] bb = to15BitsMsbLeft(blue);
		System.arraycopy(ob, 0, s.bits, 0, 15);
		System.arraycopy(bb, 0, s.bits, 15, 15);
		s.bits[SFC_ORANGE_SIGN] = orange < 0;
		s.bits[SFC_BLUE_SIGN] = blue < 0;

		double inv = 1233.0 / 100.0;
		s.landX = ox + ((orange + blue) * inv) / 2.0;
		s.landY = oy + 20.5;
		s.landZ = oz + ((orange - blue) * inv) / 2.0;

		int payload = switch (req.nukeSize()) {
			case 64 -> 1074;
			case 256 -> 3026;
			case 1024 -> 10770;
			default -> 667;
		};
		int accel = Math.max(Math.abs(orange), Math.abs(blue));
		s.payloadTicks = payload;
		s.accelerationTicks = accel;
		s.totalTicks = payload + accel + SFC_POST_ACCEL_TICKS;
		return s;
	}

	private static void validate(CannonType type, int ox, int oy, int oz, int passcode, Request req, Solution s) {
		List<String> e = s.errors;
		boolean sfc = type == CannonType.SFC;
		if (!sfc) {
			if (ox % 16 != 0) e.add("Cannon origin X (" + ox + ") must be divisible by 16");
			if (oy % 16 != 0) e.add("Cannon origin Y (" + oy + ") must be divisible by 16");
			if (oz % 16 != 0) e.add("Cannon origin Z (" + oz + ") must be divisible by 16");
		}

		if (sfc) {
			boolean ok = false;
			for (int size : SFC_PAYLOAD_SIZES) ok |= size == req.nukeSize();
			if (!ok) e.add("Payload size (" + req.nukeSize() + ") must be one of 16, 64, 256, 1024");
		} else if (req.mode() == FireMode.NUKE) {
			if (req.nukeSize() < 1) e.add("Nuke size (" + req.nukeSize() + ") must be at least 1");
			if (req.nukeSize() > 31) e.add("Nuke size (" + req.nukeSize() + ") must be 31 or less");
			if (req.nukeSize() > 16 && req.nukeSize() <= 31) s.warnings.add("Nuke sizes above 16 can lag the server a lot");
		} else {
			if (req.stabDepth() < 1) e.add("Stab depth (" + req.stabDepth() + ") must be at least 1");
			if (Math.ceil(req.stabDepth() / (9 * 0.99)) > 31) e.add("Stab depth (" + req.stabDepth() + ") is too large (max ~275)");
		}

		double dx = req.x() - ox;
		double dz = req.z() - oz;
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance < 64) e.add(String.format("Target is %.1f blocks away, must be at least 64", distance));

		if (!sfc && !Passcodes.isValid(passcode)) e.add("Passcode " + passcode + " is not in CubicMetre's good-code list");

		if (sfc) {
			if (Math.abs(s.sfcOrange) > SFC_MAX_MAGNITUDE) e.add("Orange channel (" + s.sfcOrange + ") exceeds 15-bit range");
			if (Math.abs(s.sfcBlue) > SFC_MAX_MAGNITUDE) e.add("Blue channel (" + s.sfcBlue + ") exceeds 15-bit range");
		} else {
			if (Math.abs(s.countX) >= 32767.999) e.add("X distance is too large (count overflow)");
			if (Math.abs(s.countZ) >= 32767.999) e.add("Z distance is too large (count overflow)");
			if (Math.abs(s.countY) >= 31.999) e.add("Y distance is too large (count overflow)");
			if (Math.abs(s.countX) < 1 && Math.abs(s.countZ) < 1) e.add("Target is too close (both X and Z counts < 1)");
			if (s.diffY < 50) e.add("Y difference is less than minimum (50)");
			if (oy < 6 || oy > 196) s.warnings.add("Cannon origin Y outside the recommended 6..196 range");
		}
	}

	// Inverse of solve. The magazine slot, exact stab depth and SFC payload size aren't on the panel,
	// so they come from hint.
	public static Solution decode(CannonType type, int ox, int oy, int oz, boolean[] b, Request hint) {
		return type == CannonType.SFC ? decodeSfc(ox, oy, oz, b, hint) : decodeGrid(type, ox, oy, oz, b, hint);
	}

	public static int decodePasscode(boolean[] b) {
		int code = cell(b, 4, 12) + 32 * cell(b, 4, 11);
		for (int r = 0; r < 4; r++) code += (512 >> r) * cell(b, r, 11) + (16 >> r) * cell(b, r, 12);
		return code;
	}

	private static int cell(boolean[] b, int row, int col) {
		return b[row * GRID_COLS + col] ? 1 : 0;
	}

	private static Solution decodeGrid(CannonType type, int ox, int oy, int oz, boolean[] b, Request hint) {
		// same layout as solveGrid: rows 0-3 hold the high-to-low bits of each column, row 4 the rest
		int coarseZ = 0, coarseX = 0, coarseY = 16 * cell(b, 4, 4), fineX = 0, fineY = 0, fineZ = 0;
		for (int r = 0; r < 5; r++) coarseZ += (cell(b, r, 0) << r) + (cell(b, r, 1) << (5 + r)) + (cell(b, r, 2) << (10 + r));
		coarseX += 16384 * cell(b, 4, 6) + 512 * cell(b, 4, 7) + 16 * cell(b, 4, 8);
		int payloadSize = cell(b, 4, 9);
		for (int r = 0; r < 4; r++) {
			coarseX += (8192 >> r) * cell(b, r, 7) + (256 >> r) * cell(b, r, 8) + (8 >> r) * cell(b, r, 9);
			coarseY += (8 >> r) * cell(b, r, 5);
			fineZ += (8 >> r) * (1 - cell(b, r, 3));
			fineY += (8 >> r) * (1 - cell(b, r, 4));
			fineX += (8 >> r) * (1 - cell(b, r, 6));
			payloadSize += (2 << r) * cell(b, r, 10);
		}
		boolean negZ = cell(b, 4, 3) == 1, negX = cell(b, 4, 5) == 1, nuke = cell(b, 4, 10) == 1;
		boolean ms = type == CannonType.MS;

		// the panel only stores ceil(depth / 8.91), keep the requested depth if it matches
		int stabDepth = hint.mode() == FireMode.STAB && (int) Math.ceil(hint.stabDepth() / (9 * 0.99)) == payloadSize
				? hint.stabDepth() : (int) Math.floor(payloadSize * 9 * 0.99);
		int slot = type == CannonType.MK6 ? hint.magazineSlot() : 0;

		double dvX = negX ? DV_MINUS_X : DV_PLUS_X;
		double dvZ = negZ ? DV_MINUS_Z : DV_PLUS_Z;
		double iX = ox + (ms ? DXZ_MS : DX) + slot;
		double iY = oy + 2.1975;
		double iZ = oz + (ms ? DXZ_MS : DZ);
		double y = coarseY + fineY / 10.0;
		double vX = dvX * (coarseX + fineX / 10.0) + DV_YX * y;
		double vY = DV_PLUS_Y * y;
		double vZ = dvZ * (coarseZ + fineZ / 10.0);
		double landX = iX + SLOWDOWN_XZ * vX;
		double landY = iY + SLOWDOWN_Y * vY;
		double landZ = iZ + SLOWDOWN_XZ * vZ;
		double dy = (nuke ? DY_NUKE : DY_STAB) - (ms ? DY_MS : 0);
		int targetY = (int) Math.round(iY + y * SLOWDOWN_Y * DV_PLUS_Y - dy);

		Request req = new Request((int) Math.round(landX), targetY, (int) Math.round(landZ), nuke ? FireMode.NUKE : FireMode.STAB,
				nuke ? payloadSize : hint.nukeSize(), stabDepth, slot);
		Solution s = new Solution(type, req, GRID_ROWS * GRID_COLS);
		System.arraycopy(b, 0, s.bits, 0, s.bits.length);
		s.coarseX = coarseX;
		s.coarseY = coarseY;
		s.coarseZ = coarseZ;
		s.fineX = fineX;
		s.fineY = fineY;
		s.fineZ = fineZ;
		s.landX = landX;
		s.landY = landY;
		s.landZ = landZ;
		s.accelerationTicks = 6.0 * (Math.max(coarseX, coarseZ) + coarseY + 2);
		s.payloadTicks = nuke
				? 6.0 * ((payloadSize * (payloadSize + 1)) / 2.0 + payloadSize * 2.4375) + 930
				: 6.0 * (2 * Math.round(stabDepth / (9 * 0.99))) + 1300;
		s.totalTicks = s.payloadTicks + s.accelerationTicks;
		return s;
	}

	private static Solution decodeSfc(int ox, int oy, int oz, boolean[] b, Request hint) {
		int orange = 0, blue = 0;
		for (int k = 0; k < 15; k++) {
			orange = orange * 2 + (b[k] ? 1 : 0);
			blue = blue * 2 + (b[15 + k] ? 1 : 0);
		}
		if (b[SFC_ORANGE_SIGN]) orange = -orange;
		if (b[SFC_BLUE_SIGN]) blue = -blue;
		double inv = 1233.0 / 100.0;
		double landX = ox + ((orange + blue) * inv) / 2.0;
		double landZ = oz + ((orange - blue) * inv) / 2.0;
		Request req = new Request((int) Math.round(landX), hint.y(), (int) Math.round(landZ), FireMode.NUKE, hint.nukeSize(), hint.stabDepth(), 0);
		Solution s = solveSfc(ox, oy, oz, req);
		// keep the panel's exact channels, not the ones from the rounded target
		System.arraycopy(b, 0, s.bits, 0, s.bits.length);
		s.totalTicks += Math.max(Math.abs(orange), Math.abs(blue)) - s.accelerationTicks;
		s.accelerationTicks = Math.max(Math.abs(orange), Math.abs(blue));
		s.sfcOrange = orange;
		s.sfcBlue = blue;
		s.landX = landX;
		s.landY = oy + 20.5;
		s.landZ = landZ;
		return s;
	}

	private static int bit(double value, int divisor) {
		return (int) (Math.floor(Math.abs(value) / divisor) % 2);
	}

	private static int inv(double value, int divisor) {
		return 1 - bit(value, divisor);
	}

	private static boolean[] to15BitsMsbLeft(int value) {
		int mag = Math.min(Math.abs(value), SFC_MAX_MAGNITUDE);
		boolean[] out = new boolean[15];
		for (int i = 14, k = 0; i >= 0; i--, k++) out[k] = ((mag >> i) & 1) == 1;
		return out;
	}

	public static String formatTicks(double ticks) {
		long seconds = (long) Math.ceil(ticks / 20.0);
		return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
	}
}
