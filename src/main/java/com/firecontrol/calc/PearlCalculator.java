package com.firecontrol.calc;

/**
 * Port of the "Main Cannon" solver from Danda420's 360 Precision Ender Pearl Cannon V3 calculator
 * (danda420.github.io/360_Precision_EnderPearlCannon).
 */
public final class PearlCalculator {
	private static final double BASE_Y_VEL = 0.08707139660848056;
	private static final double Y_VEL_PER_CHARGE = 0.01593734962936198;
	private static final double GRAVITY = 0.03;
	private static final double DRAG = 0.99;
	// height above the cannon floor where the charges go off
	public static final double DETONATION_Y_OFFSET = 40.05317302547412;
	private static final double MAG = 0.8478107958208771;
	private static final double DRIFT = 0.031777016335115336;

	// load time per axis, from the calculator: priming + aligning + a base, then a rate per charge
	public static final double LOAD_FIXED_TICKS = 82 + 150 + 13.06;
	public static final double LOAD_TICKS_PER_CHARGE = 8.5201;

	private PearlCalculator() {
	}

	public enum Axis {
		NORTH("North", DRIFT, -MAG),
		EAST("East", MAG, DRIFT),
		SOUTH("South", -DRIFT, MAG),
		WEST("West", -MAG, -DRIFT);

		public final String label;
		// push per charge (blocks/tick before drag), in the cannon's own frame
		public final double ux, uz;

		Axis(String label, double ux, double uz) {
			this.label = label;
			this.ux = ux;
			this.uz = uz;
		}
	}

	// tried in this order, cheapest wins
	private static final Axis[][] PAIRS = {{Axis.EAST, Axis.NORTH}, {Axis.EAST, Axis.SOUTH}, {Axis.WEST, Axis.NORTH}, {Axis.WEST, Axis.SOUTH}};

	public static final class Solution {
		public final Axis axisA, axisB;
		public final int chargesA, chargesB;
		public final int targetX, targetZ;
		public final double landX, landZ;
		public final double error;
		public final double flightTicks;
		public final int farJumps;

		Solution(Axis axisA, Axis axisB, int chargesA, int chargesB, int targetX, int targetZ, double landX, double landZ, double error, double flightTicks, int farJumps) {
			this.axisA = axisA;
			this.axisB = axisB;
			this.chargesA = chargesA;
			this.chargesB = chargesB;
			this.targetX = targetX;
			this.targetZ = targetZ;
			this.landX = landX;
			this.landZ = landZ;
			this.error = error;
			this.flightTicks = flightTicks;
			this.farJumps = farJumps;
		}

		public int total() {
			return chargesA + chargesB;
		}

		public int charges(Axis axis) {
			return (axis == axisA ? chargesA : 0) + (axis == axisB ? chargesB : 0);
		}
	}

	// targetY is the block to land on (the calculator defaults to floorY - 1)
	public static Solution solve(int originX, int floorY, int originZ, int targetX, int targetY, int targetZ, boolean netherConversion) {
		double launchY = floorY + DETONATION_Y_OFFSET;
		int tx = netherConversion ? Math.floorDiv(targetX, 8) : targetX;
		int tz = netherConversion ? Math.floorDiv(targetZ, 8) : targetZ;
		double dX = tx - originX, dZ = tz - originZ;

		Choice chosen = chooseAxes(dX, dZ);
		double totalDist = chosen.a + chosen.b;
		int nTotal = (int) Math.round(chargesForCapacity(totalDist, launchY, targetY));
		double splitA = totalDist > 0 ? chosen.a / totalDist : 0;
		int nA = (int) Math.round(nTotal * splitA);
		int nB = nTotal - nA;

		double mN = nTotal > 0 ? capacity(nTotal, launchY, targetY) / nTotal : 0;
		double vx = nA * chosen.axisA.ux + nB * chosen.axisB.ux, vz = nA * chosen.axisA.uz + nB * chosen.axisB.uz;
		double landX = originX + vx * mN;
		double landZ = originZ + vz * mN;
		double err = netherConversion ? Math.hypot(landX * 8 - targetX, landZ * 8 - targetZ) : Math.hypot(landX - targetX, landZ - targetZ);
		double flight = flightTime(nTotal, launchY, targetY);
		return new Solution(chosen.axisA, chosen.axisB, nA, nB, tx, tz, landX, landZ, err, flight, farJumps(originX, originZ, vx, vz, flight));
	}

	// x, z, flight ticks, far chunk jumps
	public static double[] landing(int originX, int floorY, int originZ, int targetY, int north, int east, int south, int west) {
		double launchY = floorY + DETONATION_Y_OFFSET;
		int total = north + east + south + west;
		double mN = total > 0 ? capacity(total, launchY, targetY) / total : 0;
		double vx = north * Axis.NORTH.ux + east * Axis.EAST.ux + south * Axis.SOUTH.ux + west * Axis.WEST.ux;
		double vz = north * Axis.NORTH.uz + east * Axis.EAST.uz + south * Axis.SOUTH.uz + west * Axis.WEST.uz;
		double flight = flightTime(total, launchY, targetY);
		return new double[] {originX + vx * mN, originZ + vz * mN, flight, farJumps(originX, originZ, vx, vz, flight)};
	}

	// A flying pearl keeps the chunks within 2 of it loaded. A tick that takes it further lands it in a chunk
	// the server still has to load, and the pearl waits for it, so long shots take longer than their flight.
	static int farJumps(int originX, int originZ, double vx, double vz, double flight) {
		double x = originX + 0.5, z = originZ + 0.5, drag = 1;
		int jumps = 0;
		for (int i = 0; i < flight; i++) {
			double nx = x + vx * drag, nz = z + vz * drag;
			if (Math.max(Math.abs(Math.floorDiv((int) Math.floor(nx), 16) - Math.floorDiv((int) Math.floor(x), 16)),
					Math.abs(Math.floorDiv((int) Math.floor(nz), 16) - Math.floorDiv((int) Math.floor(z), 16))) > 2) jumps++;
			x = nx;
			z = nz;
			drag *= DRAG;
		}
		return jumps;
	}

	public static double loadTicks(int charges) {
		return loadTicks(charges, LOAD_TICKS_PER_CHARGE);
	}

	// a cannon that runs slower (or faster) than the calculator's rate
	public static double loadTicks(int charges, double ticksPerCharge) {
		return charges <= 0 ? 0 : LOAD_FIXED_TICKS + ticksPerCharge * charges;
	}

	private record Choice(Axis axisA, Axis axisB, double a, double b) {
	}

	private static Choice chooseAxes(double dX, double dZ) {
		Choice best = null;
		double bestTotal = 0;
		for (Axis[] pair : PAIRS) {
			double[] r = solveRaw(dX, dZ, pair[0], pair[1]);
			if (r[0] >= -1e-6 && r[1] >= -1e-6) {
				double total = r[0] + r[1];
				if (best == null || total < bestTotal) {
					best = new Choice(pair[0], pair[1], Math.max(r[0], 0), Math.max(r[1], 0));
					bestTotal = total;
				}
			}
		}
		if (best == null) {
			double bestPenalty = 0;
			for (Axis[] pair : PAIRS) {
				double[] r = solveRaw(dX, dZ, pair[0], pair[1]);
				double penalty = Math.max(0, -r[0]) + Math.max(0, -r[1]);
				if (best == null || penalty < bestPenalty) {
					best = new Choice(pair[0], pair[1], Math.max(r[0], 0), Math.max(r[1], 0));
					bestPenalty = penalty;
				}
			}
		}
		return best;
	}

	private static double[] solveRaw(double dX, double dZ, Axis a, Axis b) {
		double det = a.ux * b.uz - b.ux * a.uz;
		return new double[] {(dX * b.uz - b.ux * dZ) / det, (a.ux * dZ - dX * a.uz) / det};
	}

	// getV3Capacity
	static double capacity(double charges, double launchY, double targetY) {
		if (charges <= 0) return 0;
		Flight f = fly(charges, launchY, targetY);
		double m = 0, drag = 1;
		for (int i = 0; i < f.ticks; i++) {
			m += drag;
			drag *= DRAG;
		}
		m += drag * f.fraction;
		return charges * m;
	}

	static double flightTime(double charges, double launchY, double targetY) {
		if (charges <= 0) return 0;
		Flight f = fly(charges, launchY, targetY);
		return f.ticks + f.fraction;
	}

	private record Flight(int ticks, double fraction) {
	}

	private static Flight fly(double charges, double launchY, double targetY) {
		double vy = BASE_Y_VEL + charges * Y_VEL_PER_CHARGE;
		double y = launchY;
		int ticks = 0;
		double fraction = 0;
		while (true) {
			double nextY = y + vy;
			if (vy < 0 && y >= targetY && nextY <= targetY) {
				fraction = (targetY - y) / vy;
				break;
			}
			y = nextY;
			vy = vy * DRAG - GRAVITY;
			ticks++;
			if ((vy < 0 && y < targetY - 10) || ticks > 200000) break;
		}
		return new Flight(ticks, fraction);
	}

	// binary search for the (fractional) total charge count that covers a distance
	static double chargesForCapacity(double required, double launchY, double targetY) {
		if (required <= 0) return 0;
		double high = 64;
		while (capacity(high, launchY, targetY) < required) {
			high *= 2;
			if (high > 1e9) break;
		}
		double low = 0, best = 0;
		for (int i = 0; i < 80; i++) {
			double mid = (low + high) / 2;
			if (capacity(mid, launchY, targetY) < required) low = mid;
			else high = mid;
			best = mid;
		}
		return best;
	}
}
