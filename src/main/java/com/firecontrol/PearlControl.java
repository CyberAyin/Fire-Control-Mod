package com.firecontrol;

import com.firecontrol.calc.PearlCalculator;
import com.firecontrol.calc.PearlCalculator.Axis;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import com.firecontrol.world.Blocks2;
import com.firecontrol.world.PearlLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Live state of the active pearl cannon (lit bulbs, hole, charges per axis), plus the landing countdown
public final class PearlControl {
	private static final int CHECK_INTERVAL = 4;
	// ticks from passing the powder snow to the charges going off
	public static final int IN_CANNON_TICKS = 78;
	// stacks sit at about floor + 42
	private static final int STACK_MIN_DY = 36, STACK_MAX_DY = 48;

	private static Pairing pairing;
	private static PearlCalculator.Solution solution;
	private static boolean dirty = true;
	private static int counter;

	private static boolean loadedChunks;
	private static final boolean[] lit = new boolean[4];
	// walls around the stack cell at y + 54, open = that axis's charges can get to its stack
	private static final boolean[] wallOpen = new boolean[4];
	private static boolean holeOpen, cacheOn;
	private static final int[] stacked = new int[4];
	private static int stored, total, escaping, making;
	private static boolean wasReady;
	private static int countedPearl = -1;
	private static long launchCheckAt = -1;
	private static int chargesAtThrow;
	// game ticks a flying pearl waits per chunk the server has to load. Singleplayer: 1.5 over flat land,
	// about 20 over new Nether terrain. Learned per server and dimension from each landing.
	private static final double DEFAULT_CHUNK_WAIT = 6, DEFAULT_NETHER_CHUNK_WAIT = 12, MAX_CHUNK_WAIT = 60;
	private static Pairing thrownFor;
	private static double thrownFlight, thrownJumps;

	private PearlControl() {
	}

	public static void invalidate() {
		dirty = true;
		counter = CHECK_INTERVAL;
	}

	public static PearlCalculator.Solution solution() {
		return solution;
	}

	static void tick(Minecraft mc, Pairing p) {
		if (p != pairing) {
			pairing = p;
			dirty = true;
			wasReady = false;
		}
		if (dirty) {
			solution = p.solvePearl();
			dirty = false;
		}
		if (++counter < CHECK_INTERVAL) return;
		counter = 0;
		read(mc.level, p.pearl);
		watchPearl(mc.level, mc.player, p);
		boolean ready = ready();
		if (ready && !wasReady && p.loaded && !p.fired) {
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.6f));
		}
		wasReady = ready;
		checkLaunched(mc.level, p);
	}

	public static void readNow(ClientLevel level) {
		if (pairing != null) read(level, pairing.pearl);
	}

	private static void read(ClientLevel level, PearlLayout l) {
		loadedChunks = level.isLoaded(l.bulb(Axis.NORTH)) && level.isLoaded(l.at(0, 42, 0));
		if (!loadedChunks) return;
		for (Axis a : Axis.values()) {
			lit[a.ordinal()] = Blocks2.isLit(level.getBlockState(l.bulb(a)));
			// bulb is lit while the wall is closed
			wallOpen[a.ordinal()] = !Blocks2.isLit(level.getBlockState(wallBulb(l, a)));
		}
		holeOpen = level.getBlockState(l.at(0, 3, -1)).isAir();
		cacheOn = Blocks2.isLit(level.getBlockState(l.cacheBulb()));
		java.util.Arrays.fill(stacked, 0);
		stored = 0;
		total = 0;
		escaping = 0;
		AABB storage = l.storage();
		// the breezes' charges wait up top until the whole batch is through, then drop together
		making = level.getEntities((Entity) null, new AABB(storage.minX, storage.maxY, storage.minZ, storage.maxX, l.bounds().maxY + 4, storage.maxZ),
				PearlControl::isCharge).size();
		BlockPos column = l.at(0, 0, 0);
		// below the breeze chambers but outside the cannon = flying out. A stack drifting off
		// diagonally is leaving through an open wall.
		AABB wide = storage.inflate(20, 0, 20);
		for (Entity e : level.getEntities((Entity) null, wide, PearlControl::isCharge)) {
			BlockPos b = e.blockPosition();
			int dx = Math.abs(b.getX() - column.getX()), dz = Math.abs(b.getZ() - column.getZ());
			boolean inside = l.bounds().contains(e.position());
			if (!inside || dx >= 2 && dz >= 2 && b.getY() - l.y >= 50 || dx > 5 || dz > 5) {
				escaping++;
				continue;
			}
			total++;
			if (!storage.contains(e.position())) continue;
			stored++;
			Axis a = slotOf(l, b);
			if (a != null) stacked[a.ordinal()]++;
		}
	}

	public static int escaping() {
		return escaping;
	}

	public static int making() {
		return making;
	}

	// each selector pulses its own relay column (~40 ticks), which flips that axis's wall
	public static BlockPos wallBulb(PearlLayout l, Axis label) {
		return switch (label) {
			case EAST -> l.at(-1, 52, 2);
			case NORTH -> l.at(0, 52, 3);
			case SOUTH -> l.at(-2, 52, -1);
			case WEST -> l.at(1, 52, -2);
		};
	}

	// x of each signal column (z 8 and 9 in the schematic)
	private static int columnDx(Axis label) {
		return switch (label) {
			case WEST -> -5;
			case SOUTH -> -4;
			case EAST -> -3;
			case NORTH -> -2;
		};
	}

	// an observer left one block up by a sticky piston jams the column. null if fine or not loaded
	public static BlockPos jammedRelay(ClientLevel level, PearlLayout l, Axis label) {
		for (int y : new int[] {12, 28, 44, 51}) {
			BlockPos home = l.at(columnDx(label), y, 9);
			if (!level.isLoaded(home) || level.getBlockState(home).is(net.minecraft.world.level.block.Blocks.OBSERVER)) continue;
			if (level.getBlockState(home.above()).is(net.minecraft.world.level.block.Blocks.OBSERVER)) return home.above();
		}
		return null;
	}

	public static boolean wallOpen(Axis label) {
		return wallOpen[label.ordinal()];
	}

	public static boolean isCharge(Entity e) {
		return com.firecontrol.compat.Compat.isWindCharge(e);
	}

	private static Axis slotOf(PearlLayout l, BlockPos pos) {
		int dy = pos.getY() - l.y;
		if (dy < STACK_MIN_DY || dy > STACK_MAX_DY) return null;
		for (Axis a : Axis.values()) {
			BlockPos cell = l.at(slotDx(a), dy, slotDz(a));
			if (cell.getX() == pos.getX() && cell.getZ() == pos.getZ()) return a;
		}
		return null;
	}

	private static int slotDx(Axis label) {
		return label == Axis.WEST || label == Axis.SOUTH ? 0 : -1;
	}

	private static int slotDz(Axis label) {
		return label == Axis.WEST || label == Axis.NORTH ? 0 : -1;
	}

	private static void watchPearl(ClientLevel level, LocalPlayer player, Pairing p) {
		if (player == null) return;
		PearlLayout l = p.pearl;
		// above the tripwire and powder snow, so a pearl bouncing off a closed hole doesn't count
		AABB column = new AABB(l.point(-1, 6, -2), l.point(2, 44, 1));
		for (Entity e : level.getEntities((Entity) null, column, com.firecontrol.compat.Compat::isEnderPearl)) {
			if (!(e instanceof Projectile proj) || proj.getOwner() != player || e.getId() == countedPearl) continue;
			countedPearl = e.getId();
			int[] c = stackedByLabel();
			double[] land = landingFor(p, c);
			warnIfShort(p, land);
			Eta.startTicks(p, etaTicks(land[2], land[3]), IN_CANNON_TICKS, level.getGameTime());
			thrownFor = p;
			thrownFlight = land[2];
			thrownJumps = land[3];
			launchCheckAt = level.getGameTime() + IN_CANNON_TICKS + 60;
			chargesAtThrow = stored;
			p.fired = true;
			p.loaded = false;
			Store.save();
			return;
		}
	}

	// The cannon fires with whatever is in its stacks, so a pearl thrown mid-load goes a long way wrong.
	// Nothing can hold it back once it is in the air; say where it is really going.
	private static void warnIfShort(Pairing p, double[] land) {
		if (solution == null || matchesSolution()) return;
		java.util.List<String> off = new java.util.ArrayList<>();
		for (Axis world : Axis.values()) {
			Axis label = p.pearl.labelFor(world);
			if (stacked(label) != solution.charges(world)) off.add(label.label + " " + stacked(label) + "/" + solution.charges(world));
		}
		if (PearlLoader.running() || PearlLoader.paused()) PearlLoader.stop(null);
		Msg.error("Thrown before the cannon was loaded: " + String.join(", ", off) + ".");
		Msg.warn(String.format("That pearl lands near %.0f %.0f, not %d %d.", land[0], land[1], solution.targetX, solution.targetZ));
	}

	public static boolean startCountdown(Pairing p, long gameTime) {
		if (p != pairing || !known()) return false;
		double[] land = landingFor(p, stackedByLabel());
		Eta.startTicks(p, etaTicks(land[2], land[3]), IN_CANNON_TICKS, gameTime);
		thrownFor = null;
		p.fired = true;
		p.loaded = false;
		Store.save();
		return true;
	}

	// from passing the powder snow to landing: the flight, plus the waits for chunks the server has to load on the way
	public static int etaTicks(double flightTicks, double farJumps) {
		return IN_CANNON_TICKS + (int) Math.ceil(flightTicks + chunkWait() * farJumps);
	}

	public static double chunkWait() {
		Double w = Store.data().chunkWait.get(chunkWaitKey());
		if (w != null) return w;
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && Pairing.NETHER.equals(FireControl.dimensionId(mc.level)) ? DEFAULT_NETHER_CHUNK_WAIT : DEFAULT_CHUNK_WAIT;
	}

	// per server and dimension: new Nether terrain is much slower to make than flat land
	private static String chunkWaitKey() {
		Minecraft mc = Minecraft.getInstance();
		return Store.worldKey() + " " + (mc.level == null ? "" : FireControl.dimensionId(mc.level));
	}

	// learns how long this server takes per chunk from what the flight really took
	static void landed(Pairing p, long gameTime) {
		int took = (int) (gameTime - p.etaStart);
		if (p == thrownFor && thrownJumps >= 10 && took > IN_CANNON_TICKS + thrownFlight * 0.8) {
			double seen = Math.min(MAX_CHUNK_WAIT, Math.max(0, (took - IN_CANNON_TICKS - thrownFlight) / thrownJumps));
			Store.data().chunkWait.put(chunkWaitKey(), 0.3 * chunkWait() + 0.7 * seen);
		}
		thrownFor = null;
		Eta.landed(p, gameTime);
	}

	// The lift sometimes drops the pearl. If all the charges are still there well after it
	// should have fired, cancel the countdown and keep the cannon loaded.
	private static void checkLaunched(ClientLevel level, Pairing p) {
		if (launchCheckAt < 0 || level.getGameTime() < launchCheckAt) return;
		launchCheckAt = -1;
		if (!p.fired || !known() || chargesAtThrow == 0 || stored < chargesAtThrow || !playerNear(p)) return;
		p.fired = false;
		p.loaded = true;
		Eta.stop(p);
		Msg.warn("The cannon didn't launch your pearl; its charges are still loaded. Step into the hole and throw another one straight up.");
	}

	private static boolean playerNear(Pairing p) {
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && p.pearl.center().distanceToSqr(player.position()) < 48 * 48;
	}

	public static double[] landingFor(Pairing p, int[] byLabel) {
		PearlLayout l = p.pearl;
		int[] world = new int[4];
		for (Axis label : Axis.values()) world[l.worldFor(label).ordinal()] += byLabel[label.ordinal()];
		return PearlCalculator.landing(p.originX, p.originY, p.originZ, p.pearlLandY(), world[Axis.NORTH.ordinal()], world[Axis.EAST.ordinal()],
				world[Axis.SOUTH.ordinal()], world[Axis.WEST.ordinal()]);
	}

	public static boolean known() {
		return pairing != null && loadedChunks;
	}

	public static boolean lit(Axis label) {
		return lit[label.ordinal()];
	}

	public static boolean holeOpen() {
		return holeOpen;
	}

	public static boolean cacheOn() {
		return cacheOn;
	}

	public static int stacked(Axis label) {
		return stacked[label.ordinal()];
	}

	public static int[] stackedByLabel() {
		return stacked.clone();
	}

	public static int stored() {
		return stored;
	}

	public static int total() {
		return total;
	}

	public static boolean ready() {
		return matchesSolution() && holeOpen && !pairing.fired;
	}

	// the stacks hold exactly this target's charges. Anything else and a pearl thrown now lands somewhere else.
	public static boolean matchesSolution() {
		Pairing p = pairing;
		if (p == null || solution == null || !known()) return false;
		for (Axis world : Axis.values()) {
			if (stacked(p.pearl.labelFor(world)) != solution.charges(world)) return false;
		}
		return solution.total() > 0;
	}

	// negative = too many
	public static int missing(Pairing p, Axis label) {
		PearlCalculator.Solution s = p == pairing ? solution : p.solvePearl();
		int want = s == null ? 0 : s.charges(p.pearl.worldFor(label));
		return want - stacked(label);
	}

	public static String plan(Pairing p, PearlCalculator.Solution s) {
		StringBuilder sb = new StringBuilder();
		for (Axis world : new Axis[] {s.axisA, s.axisB}) {
			int n = s.charges(world);
			if (n == 0) continue;
			sb.append(sb.length() == 0 ? "" : " + ").append(p.pearl.labelFor(world).label).append(' ').append(n);
		}
		return sb.length() == 0 ? "nothing" : sb.toString();
	}

	public static boolean playerAtCannon(Pairing p) {
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && p.isPearl() && p.pearl.operatorArea().contains(player.position());
	}

	public static Vec3 throwTarget(Pairing p) {
		return p.pearl.throwTarget();
	}
}
