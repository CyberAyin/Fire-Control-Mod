package com.firecontrol;

import com.firecontrol.calc.PearlCalculator;
import com.firecontrol.calc.PearlCalculator.Axis;
import com.firecontrol.compat.Compat;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import com.firecontrol.world.Blocks2;
import com.firecontrol.world.PearlLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// InputSettings at a pearl cannon: sets the axis bulbs, moves counter items from the barrel to the INPUT
// and waits for each stack. Stops before the throw.
public final class PearlLoader {
	private static final int CLICKS_PER_TICK = 2;
	private static final int OPEN_TIMEOUT = 40;
	private static final int STUCK_TICKS = 100;
	private static final int STALL_TICKS = 20 * 60;
	private static final int BATCH_DONE_TICKS = 20 * 15;
	private static final int SETTLE_TIMEOUT = 20 * 60;
	private static final int MAX_TOP_UPS = 2;

	private enum Phase { SELECT, OPEN_COUNTER, TAKE, OPEN_INPUT, PUT, WAIT, SETTLE, CLEAR }

	private record Job(Axis label, int count) {
	}

	private record Click(int slot, int button, boolean quick) {
	}

	private static boolean running;
	private static Pairing pairing;
	private static PearlCalculator.Solution solution;
	private static final Deque<Job> jobs = new ArrayDeque<>();
	private static Job job;
	private static Phase phase;
	private static int ticks, phaseTicks, timeout, cooldown, stuck, attempts, topUps;
	private static Screen opener;
	private static boolean movedKeys, clickedContainer;
	private static int closedAt = -100;
	private static Axis pressedAxis;
	private static long pressedAt;
	private static Axis waitingWall;
	// the pulse takes about 40 ticks
	private static final int WALL_TICKS = 100;

	private static Item counterItem;
	private static final Deque<Click> clicks = new ArrayDeque<>();
	private static final List<Integer> carried = new ArrayList<>();
	private static int carriedCount, inputBefore;
	private static int stackedBefore, made, settled;
	private static long lastProgressAt, escapingSince = -1;
	private static final int ESCAPE_TICKS = 40;
	// container clicks: ticks since the last one, and tries
	private static int answerTicks, retries;
	private static final int MAX_RETRIES = 3, OPEN_ATTEMPTS = 5, MAX_PRESSES = 4;
	private static String title = "", step = "";
	private static int expectedTicks, plannedSplits;
	// in game ticks, since the cannon runs on server time. A pause holds it, except while the cannon carries on by itself.
	private static long startTick, heldTicks, heldSince = -1, lastTick;
	// server ticks per real second, measured, so the time left is in real seconds on a lagging server
	private static double tps = 20;
	private static long tpsMillis = -1, tpsTick;
	// game time the cannon was last seen doing something. Chunks the server doesn't tick still show, but go quiet.
	private static long activeAt;
	private static final int QUIET_TICKS = 300;
	private static final int PRESS_TICKS = 45, BATCH_TICKS = 25;
	private static final int BATCH_FIXED_TICKS = (int) PearlCalculator.loadTicks(1) - 9;

	private static boolean paused, batchRetried;
	private static int ownBefore;
	private static boolean took;
	// counted on resume, not at the pause: items dropped off the cursor only show up after a server sync
	private static boolean giveBack;
	private static int giveBackCount;
	private static boolean putAgain, putPlanned;

	private PearlLoader() {
	}

	public static boolean running() {
		return running;
	}

	public static boolean paused() {
		return paused && FireControl.active() == pairing && sameShot(pairing.solvePearl(), solution);
	}

	// paused, but the cannon is still busy with its own part, so the clock runs
	public static boolean carriesOn() {
		return paused() && heldSince < 0;
	}

	public static String phase() {
		return phase == null ? "" : phase.name();
	}

	private static boolean sameShot(PearlCalculator.Solution a, PearlCalculator.Solution b) {
		return a != null && b != null && a.axisA == b.axisA && a.axisB == b.axisB && a.chargesA == b.chargesA && a.chargesB == b.chargesB;
	}

	public static String status() {
		return title + " · " + detail();
	}

	public static String title() {
		return title;
	}

	public static String detail() {
		return step.isEmpty() ? timeLeft() : step + " · " + timeLeft();
	}

	// clock based, eases toward full if the cannon takes longer
	public static double progress() {
		if (!running && !paused) return -1;
		double partial = heldSince < 0 ? Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false) : 0;
		double x = (elapsedTicks() + partial) / Math.max(1, expectedTicks);
		return x < 0.85 ? x : 0.85 + 0.15 * (1 - Math.exp(-(x - 0.85) / 0.15));
	}

	// the server's time packets can set the client's game time back a little
	private static long gameTime() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null) lastTick = Math.max(lastTick, level.getGameTime());
		return lastTick;
	}

	private static long elapsedTicks() {
		return (heldSince >= 0 ? heldSince : gameTime()) - startTick - heldTicks;
	}

	private static void hold(boolean on) {
		long now = gameTime();
		if (on && heldSince < 0) {
			heldSince = now;
		} else if (!on && heldSince >= 0) {
			heldTicks += now - heldSince;
			heldSince = -1;
		}
	}

	private static void measureTps(ClientLevel level) {
		long millis = net.minecraft.util.Util.getMillis();
		if (tpsMillis < 0 || millis - tpsMillis > 10_000) {
			tpsMillis = millis;
			tpsTick = level.getGameTime();
		} else if (millis - tpsMillis >= 2000) {
			double seen = (level.getGameTime() - tpsTick) * 1000.0 / (millis - tpsMillis);
			tps = Mth.clamp(0.5 * tps + 0.5 * seen, 1, 20);
			tpsMillis = millis;
			tpsTick = level.getGameTime();
		}
	}

	private static void say(String t, String s) {
		title = t;
		step = s;
	}

	public static Axis loadingAxis() {
		return running && phase == Phase.WAIT && job != null ? job.label : null;
	}

	public static String loadingProgress() {
		if (loadingAxis() == null) return null;
		return job.count + " · " + timeLeft();
	}

	public static String timeLeft() {
		long left = expectedTicks - elapsedTicks();
		if (left <= 0) return "almost done";
		int sec = (int) Math.ceil(left / tps);
		return sec / 60 + ":" + String.format("%02d", sec % 60) + " left";
	}

	public static void start() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Pairing p = FireControl.active();
		if (player == null || mc.level == null || mc.gameMode == null || p == null || !p.isPearl()) return;
		if (running) {
			Msg.info("Already loading.");
			return;
		}
		// not PearlControl's copy, which may not have a target set this tick yet
		PearlCalculator.Solution s = p.solvePearl();
		if (s == null) {
			Msg.error("Set a target first: /firecontrol target <x> <z>, or stand there and run /firecontrol target.");
			return;
		}
		if (s.total() == 0) {
			Msg.error("The target is right on top of the cannon; there's nothing to load.");
			return;
		}
		if (!p.pearl.operatorArea().contains(player.position())) {
			Msg.error("Stand in front of the pearl cannon's buttons to load it.");
			return;
		}
		if (paused) {
			if (p == pairing && sameShot(s, solution)) {
				resume(mc);
				return;
			}
			discardPause();
		}
		if (!PearlControl.known()) {
			Msg.error("The cannon isn't loaded on your side yet; try again in a moment.");
			return;
		}
		if (!PearlControl.holeOpen()) {
			Msg.error("The cannon is still busy (its launch hole is closed). Try again when it opens.");
			return;
		}
		// leftover charges are only ok if they're part of this shot
		PearlLayout l = p.pearl;
		List<String> extra = new ArrayList<>();
		for (Axis label : Axis.values()) {
			int want = s.charges(l.worldFor(label)), have = PearlControl.stacked(label);
			if (have > want) extra.add(label.label + " " + have + (want > 0 ? " (needs " + want + ")" : ""));
		}
		if (!extra.isEmpty()) {
			double[] land = PearlControl.landingFor(p, PearlControl.stackedByLabel());
			Msg.error("The cannon still holds charges from another load: " + String.join(", ", extra) + ".");
			Msg.info(String.format("A pearl thrown now lands near %.0f %.0f. Charges can't be taken out, so fire them before loading a new target.", land[0], land[1]));
			return;
		}
		// picked, so it stays the active preset while you step away
		if (!p.name.equals(Store.data().selected.get(Store.worldKey()))) Actions.select(p);
		pairing = p;
		solution = s;
		jobs.clear();
		int totalTicks = 0;
		for (Axis world : new Axis[] {s.axisA, s.axisB}) {
			Axis label = l.labelFor(world);
			int need = s.charges(world) - PearlControl.stacked(label);
			if (need > 0) {
				jobs.add(new Job(label, need));
				totalTicks += (int) PearlCalculator.loadTicks(need);
			}
		}
		if (jobs.isEmpty()) {
			p.loaded = true;
			Store.save();
			Msg.ok("Already loaded for this target. Step into the hole and throw your pearl straight up.");
			return;
		}
		int presses = 0;
		Axis previous = null;
		for (Job j : jobs) {
			if (j.label != previous) presses += 2;
			previous = j.label;
		}
		for (Axis a : Axis.values()) if (PearlControl.lit(a) && a != jobs.peekFirst().label) presses++;
		// an axis goes in batches when the INPUT or your free slots can't hold it all; plan those now so the time doesn't jump
		int free = 0;
		for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).isEmpty()) free++;
		int perBatch = Math.max(1, Math.min(free, l.inputSlots()) * 64);
		plannedSplits = 0;
		for (Job j : jobs) plannedSplits += (j.count - 1) / perBatch;
		expectedTicks = totalTicks + presses * PRESS_TICKS + jobs.size() * BATCH_TICKS + plannedSplits * (BATCH_FIXED_TICKS + BATCH_TICKS);
		timeout = totalTicks * 3 + 20 * 300;
		ticks = 0;
		lastTick = mc.level.getGameTime();
		startTick = lastTick;
		heldTicks = 0;
		heldSince = -1;
		batchRetried = false;
		pressedAxis = null;
		waitingWall = null;
		closedAt = -100;
		topUps = 0;
		p.loaded = false;
		opener = Compat.screen(mc);
		running = true;
		if (PearlControl.cacheOn()) Msg.warn("Pearl cache is on: the cannon will hold your pearl until you switch it off.");
		nextJob();
	}

	public static void stop(String reason) {
		paused = false;
		giveBack = false;
		putAgain = false;
		if (!running) return;
		running = false;
		releaseKeys();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && clickedContainer) {
			putBackCursor(mc);
			mc.player.closeContainer();
		}
		clickedContainer = false;
		clicks.clear();
		jobs.clear();
		say("", "");
		if (reason != null) Msg.warn("Loading stopped: " + reason);
		if (carriedCount > 0 && (phase == Phase.OPEN_INPUT || phase == Phase.PUT || phase == Phase.TAKE)) {
			Msg.info("You still have " + carriedCount + " " + itemName() + " from the COUNTER ITEMS barrel in your inventory.");
		}
		carriedCount = 0;
	}

	private static void pause(String reason) {
		Minecraft mc = Minecraft.getInstance();
		running = false;
		paused = true;
		hold(!cannonBusy(mc));
		releaseKeys();
		if (mc.player != null && clickedContainer) {
			putBackCursor(mc);
			mc.player.closeContainer();
		}
		clickedContainer = false;
		clicks.clear();
		giveBack |= phase == Phase.TAKE && took;
		putAgain |= phase == Phase.PUT && putPlanned;
		Msg.send(Msg.text("Loading paused: " + reason + ". ", ChatFormatting.YELLOW)
				.append(Msg.button("Resume", "/firecontrol InputSettings", "Carries on where it stopped (stand at the cannon's buttons)")));
	}

	private static void resume(Minecraft mc) {
		paused = false;
		hold(false);
		lastProgressAt = mc.level == null ? 0 : mc.level.getGameTime();
		attempts = 0;
		pressedAxis = null;
		opener = Compat.screen(mc);
		switch (phase) {
			case OPEN_COUNTER, TAKE -> setPhase(Phase.OPEN_COUNTER);
			case OPEN_INPUT, PUT -> setPhase(Phase.OPEN_INPUT);
			default -> setPhase(phase);
		}
		running = true;
	}

	private static void discardPause() {
		Minecraft mc = Minecraft.getInstance();
		int left = mc.player != null && counterItem != null ? counterItems(mc.player) - ownBefore : 0;
		boolean mid = giveBack || putAgain || phase == Phase.OPEN_INPUT || phase == Phase.PUT;
		paused = false;
		giveBack = false;
		putAgain = false;
		carriedCount = 0;
		if (mid && left > 0) Msg.info("You still have " + left + " " + itemName() + " from the COUNTER ITEMS barrel in your inventory.");
	}

	// what an axis still needs. The cannon doesn't always make exactly one charge per item, so this goes by its stack.
	private static int missing(Axis label) {
		return solution.charges(pairing.pearl.worldFor(label)) - PearlControl.stacked(label);
	}

	private static void nextJob() {
		PearlControl.readNow(Minecraft.getInstance().level);
		do {
			job = jobs.pollFirst();
		} while (job != null && missing(job.label) <= 0);
		if (job == null) {
			setPhase(Phase.SETTLE);
			return;
		}
		attempts = 0;
		setPhase(Phase.SELECT);
	}

	private static void setPhase(Phase p) {
		phase = p;
		phaseTicks = 0;
		stuck = 0;
		answerTicks = 0;
		retries = 0;
		escapingSince = -1;
		attempts = p == Phase.SELECT ? attempts : 0;
		putPlanned = false;
		if (p == Phase.TAKE) took = false;
		active(Minecraft.getInstance().level);
	}

	private static void active(ClientLevel level) {
		if (level != null) activeAt = level.getGameTime();
	}

	// what the cannon does by itself (charges, a wall flipping, settling) goes on during a pause,
	// as long as it's loaded here and seen moving
	private static boolean cannonBusy(Minecraft mc) {
		ClientLevel level = mc.level;
		// a batch shows nothing while the cannon runs its items through
		long quiet = phase == Phase.WAIT && made == 0 ? firstChargeTicks(job.count) : QUIET_TICKS;
		if (level == null || mc.isPaused() || FireControl.active() != pairing || !PearlControl.known()
				|| level.getGameTime() - activeAt > quiet) return false;
		return switch (phase) {
			case WAIT -> PearlControl.stacked(job.label) - stackedBefore < job.count;
			case SETTLE -> !PearlControl.holeOpen();
			case SELECT, CLEAR -> waitingWall != null && PearlControl.wallOpen(waitingWall) != PearlControl.lit(waitingWall);
			default -> false;
		};
	}

	public static void tick(Minecraft mc) {
		if (mc.level != null && (running || paused)) measureTps(mc.level);
		if (paused) {
			if (phase == Phase.WAIT && mc.level != null && FireControl.active() == pairing && mc.level.getGameTime() % 5 == 0) countCharges(mc.level);
			hold(!cannonBusy(mc));
		}
		if (!running) return;
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || mc.gameMode == null) {
			stop(null);
			return;
		}
		Screen screen = Compat.screen(mc);
		if (screen != null && screen == opener) {
			Compat.setScreen(mc, null);
			opener = null;
			return;
		}
		opener = null;
		// a container we clicked can show up late on a slow server
		boolean opening = clickedContainer || (phase == Phase.OPEN_COUNTER || phase == Phase.OPEN_INPUT) && attempts > 0;
		// the closed screen can still be up for a tick on some versions, or come back late after a second click
		if (!opening && screen instanceof AbstractContainerScreen<?> s && s.getMenu() != player.inventoryMenu
				&& ticks - closedAt >= 0 && ticks - closedAt <= 5 + openTimeout(mc)) {
			player.closeContainer();
			return;
		}
		boolean ownScreen = opening && screen instanceof AbstractContainerScreen;
		if (FireControl.active() != pairing || pairing.solvePearl() == null) {
			stop("the target or preset changed");
			return;
		}
		if (screen != null && !ownScreen) {
			pause("a menu was opened");
			return;
		}
		if (!pairing.pearl.operatorArea().contains(player.position())) {
			pause("you left the cannon");
			return;
		}
		if (player.hurtTime > 0) {
			pause("you took damage");
			return;
		}
		if (++ticks > timeout) {
			stop("took too long");
			return;
		}
		phaseTicks++;
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		switch (phase) {
			case SELECT -> select(mc, player, level, job.label, Phase.OPEN_COUNTER);
			case CLEAR -> select(mc, player, level, null, null);
			case OPEN_COUNTER -> open(mc, player, level, pairing.pearl.counter(), 27, Phase.TAKE, "the COUNTER ITEMS barrel");
			case TAKE -> take(mc, player);
			case OPEN_INPUT -> open(mc, player, level, pairing.pearl.input(), pairing.pearl.inputSlots(), Phase.PUT, "the INPUT");
			case PUT -> put(mc, player);
			case WAIT -> waitForCharges(level);
			case SETTLE -> settle(level);
		}
	}

	private static void select(Minecraft mc, LocalPlayer player, ClientLevel level, Axis want, Phase next) {
		PearlLayout l = pairing.pearl;
		if (want == null) say("Finishing", "switching the bulbs off");
		else say("Loading " + want.label, waitingWall != null ? "the cannon is switching" : "selecting the axis");
		PearlControl.readNow(level);
		// One selector at a time. Two pulses in neighbouring columns can jam one of them,
		// and then its wall stays open.
		int wallTicks = WALL_TICKS + 2 * lagTicks(mc);
		if (waitingWall != null) {
			if (PearlControl.wallOpen(waitingWall) == Blocks2.isLit(level.getBlockState(l.bulb(waitingWall)))) {
				waitingWall = null;
			} else if (level.getGameTime() - pressedAt > wallTicks) {
				stop(wallProblem(level, waitingWall));
				return;
			} else {
				return;
			}
		}
		// give a pulse that is already on its way time to arrive
		for (Axis a : Axis.values()) {
			if (PearlControl.wallOpen(a) != Blocks2.isLit(level.getBlockState(l.bulb(a)))) {
				if (phaseTicks > wallTicks) stop(wallProblem(level, a));
				return;
			}
		}
		Axis press = null;
		// others off first so two axes are never lit together
		for (Axis a : Axis.values()) {
			if (a != want && Blocks2.isLit(level.getBlockState(l.bulb(a)))) press = a;
		}
		if (press == null && want != null && !Blocks2.isLit(level.getBlockState(l.bulb(want)))) press = want;
		if (press == null) {
			releaseKeys();
			if (next == null) finish();
			else setPhase(next);
			return;
		}
		BlockPos buttonPos = l.button(press);
		BlockState button = level.getBlockState(buttonPos);
		if (!Blocks2.isButton(button)) {
			stop("the " + press.label + " button is missing");
			return;
		}
		// a press counts once the server has let the button go again, so a slow one isn't pressed twice
		if (press == pressedAxis && level.getGameTime() - pressedAt < 30 + 2 * lagTicks(mc)) return;
		if (Blocks2.isPressed(button)) return;
		if (press == pressedAxis && ++attempts > MAX_PRESSES) {
			pause("the " + press.label + " selector bulb didn't switch after " + MAX_PRESSES + " presses");
			return;
		}
		if (!reach(mc, player, buttonPos)) return;
		Vec3 aim = shapeCenter(level, buttonPos, button);
		look(player, aim);
		mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(aim, Blocks2.buttonOutward(button), buttonPos, false));
		Compat.swing(player);
		if (press != pressedAxis) attempts = 0;
		pressedAxis = press;
		pressedAt = level.getGameTime();
		active(level);
		waitingWall = press;
		cooldown = Math.max(1, Store.data().pressInterval);
	}

	private static String escaped(ClientLevel level) {
		String lost = PearlControl.escaping() + " " + job.label.label + " charge" + (PearlControl.escaping() == 1 ? "" : "s") + " flew out of the cannon";
		for (Axis a : Axis.values()) {
			if (a != job.label && PearlControl.wallOpen(a)) return lost + ": " + wallProblem(level, a);
		}
		return lost + ". A wall round the stack at the top of the cannon must have let them through";
	}

	private static String wallProblem(ClientLevel level, Axis a) {
		boolean open = PearlControl.wallOpen(a);
		String what = "the " + a.label + " wall at the top of the cannon is " + (open ? "open" : "closed") + " while " + a.label
				+ " is switched " + (Blocks2.isLit(level.getBlockState(pairing.pearl.bulb(a))) ? "on" : "off") + ", so its signal column didn't pass the pulse on";
		BlockPos jam = PearlControl.jammedRelay(level, pairing.pearl, a);
		String flip = "flip the wall's copper bulb at " + PearlControl.wallBulb(pairing.pearl, a).toShortString() + " once (a lever on it, on then off)";
		if (jam != null) return what + ". Fix: move the observer at " + jam.toShortString() + " back down one block (same facing), then " + flip;
		return what + ". Fix: check that axis's piston relay column behind the cannon, then " + flip;
	}

	private static void open(Minecraft mc, LocalPlayer player, ClientLevel level, BlockPos pos, int slots, Phase next, String what) {
		say("Loading " + job.label.label, next == Phase.TAKE ? "taking " + job.count + " counter items" : "filling the INPUT");
		AbstractContainerMenu menu = player.containerMenu;
		// also when a slow server opens it only after a second click. The contents come after the screen,
		// with a state id; clicking before that would go by an empty container.
		if ((clickedContainer || attempts > 0) && menu != player.inventoryMenu && menu.slots.size() == slots + 36) {
			clickedContainer = true;
			if (menu.getStateId() != 0) {
				if (++stuck >= 2) {
					stuck = 0;
					setPhase(next);
				}
			} else if (phaseTicks > openTimeout(mc)) {
				player.closeContainer();
				clickedContainer = false;
				retryOpen(what);
			}
			return;
		}
		if (clickedContainer) {
			if (phaseTicks > openTimeout(mc)) {
				clickedContainer = false;
				retryOpen(what);
			}
			return;
		}
		if (player.isShiftKeyDown()) {
			mc.options.keyShift.setDown(false);
			return;
		}
		if (!reach(mc, player, pos)) return;
		releaseKeys();
		PearlLayout l = pairing.pearl;
		// the sign covers the middle of the face, click below it
		Direction front = l.front();
		Vec3 aim = Vec3.atCenterOf(pos).add(front.getStepX() * 0.49, -0.38, front.getStepZ() * 0.49);
		look(player, aim);
		mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(aim, front, pos, false));
		Compat.swing(player);
		clickedContainer = true;
		phaseTicks = 0;
	}

	private static int openTimeout(Minecraft mc) {
		return OPEN_TIMEOUT + 3 * lagTicks(mc);
	}

	private static void retryOpen(String what) {
		if (++attempts >= OPEN_ATTEMPTS) pause("couldn't open " + what + " (something in the way, or the server is lagging)");
		else phaseTicks = 0;
	}

	private static int lagTicks(Minecraft mc) {
		return FireControl.lagTicks(mc);
	}

	private static void take(Minecraft mc, LocalPlayer player) {
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == player.inventoryMenu) {
			pause("the COUNTER ITEMS barrel was closed");
			return;
		}
		if (giveBack) {
			giveBack(mc, player, menu);
			return;
		}
		if (phaseTicks == 1 && !planTake(menu, player)) return;
		if (!clickSome(mc, menu)) return;
		// a click the server refused comes back as a resync, so check once it had time to answer
		if (++answerTicks <= 2 + lagTicks(mc)) return;
		if (!menu.getCarried().isEmpty()) {
			putBackCursor(mc);
			answerTicks = 0;
			return;
		}
		int got = 0;
		for (int index : carried) {
			ItemStack st = player.getInventory().getItem(index);
			if (st.is(counterItem)) got += st.getCount();
		}
		if (got != carriedCount) {
			// put back whatever did move and plan again
			if (++retries <= MAX_RETRIES) {
				giveBack = true;
				phaseTicks = 0;
				return;
			}
			pause("the counter items didn't come out of the barrel as planned (" + got + " of " + carriedCount + ")");
			return;
		}
		player.closeContainer();
		clickedContainer = false;
		closedAt = ticks;
		setPhase(Phase.OPEN_INPUT);
	}

	private static boolean planTake(AbstractContainerMenu menu, LocalPlayer player) {
		answerTicks = 0;
		PearlControl.readNow(Minecraft.getInstance().level);
		int want = missing(job.label);
		if (want <= 0) {
			// the batch before made enough
			player.closeContainer();
			clickedContainer = false;
			closedAt = ticks;
			nextJob();
			return false;
		}
		Inventory inv = player.getInventory();
		List<Slot> barrel = new ArrayList<>(), empty = new ArrayList<>();
		for (Slot s : menu.slots) {
			if (s.container == inv) {
				if (s.getContainerSlot() < 36 && !s.hasItem()) empty.add(s);
			} else {
				barrel.add(s);
			}
		}
		// counter item = whatever the barrel holds most of (redstone in the schematic)
		java.util.Map<Item, Integer> totals = new java.util.HashMap<>();
		for (Slot s : barrel) if (s.hasItem()) totals.merge(s.getItem().getItem(), s.getItem().getCount(), Integer::sum);
		counterItem = totals.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey).orElse(null);
		if (counterItem == null) {
			stop("the COUNTER ITEMS barrel is empty. Fill it with any one item (Danda's schematic uses redstone)");
			return false;
		}
		ownBefore = counterItems(player);
		took = true;
		int stackSize = new ItemStack(counterItem).getMaxStackSize();
		int[] counts = new int[barrel.size()];
		for (int i = 0; i < counts.length; i++) counts[i] = barrel.get(i).getItem().is(counterItem) ? barrel.get(i).getItem().getCount() : 0;
		// one batch = what the axis still needs, as far as the INPUT and the free slots go
		int batch = Math.min(want, pairing.pearl.inputSlots() * stackSize);
		clicks.clear();
		carried.clear();
		carriedCount = 0;
		int need = batch;
		while (need > 0 && !empty.isEmpty()) {
			int src = 0;
			for (int i = 1; i < counts.length; i++) if (counts[i] > counts[src]) src = i;
			if (counts[src] <= 0) break;
			Slot to = empty.remove(0);
			int k = Math.min(stackSize, need), c = counts[src];
			int from = barrel.get(src).index;
			clicks.add(new Click(from, 0, false));
			if (c <= k) {
				clicks.add(new Click(to.index, 0, false));
				counts[src] = 0;
				k = c;
			} else {
				for (int i = 0; i < k; i++) clicks.add(new Click(to.index, 1, false));
				clicks.add(new Click(from, 0, false));
				counts[src] = c - k;
			}
			carried.add(to.getContainerSlot());
			carriedCount += k;
			need -= k;
		}
		if (carriedCount == 0) {
			stop(empty.isEmpty() ? "make room in your inventory (one empty slot per " + stackSize + " items)"
					: "the COUNTER ITEMS barrel has no " + itemName() + " left");
			return false;
		}
		if (carriedCount < want) {
			jobs.addFirst(new Job(job.label, want - carriedCount));
			if (plannedSplits > 0) plannedSplits--;
			else expectedTicks += BATCH_FIXED_TICKS + BATCH_TICKS;
		}
		job = new Job(job.label, carriedCount);
		return true;
	}

	private static void giveBack(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu) {
		if (phaseTicks == 1) {
			giveBackCount = counterItems(player) - ownBefore;
			if (giveBackCount <= 0) {
				giveBack = false;
				phaseTicks = 0;
				return;
			}
			say("Loading " + job.label.label, "putting back " + giveBackCount + " counter items");
			List<Slot> mine = new ArrayList<>(), barrel = new ArrayList<>();
			for (Slot s : menu.slots) {
				if (s.container != player.getInventory()) barrel.add(s);
				else if (s.getContainerSlot() < 36 && s.getItem().is(counterItem)) mine.add(s);
			}
			clicks.clear();
			answerTicks = 0;
			if (planMove(mine, barrel, giveBackCount) < giveBackCount) {
				stop("couldn't put " + giveBackCount + " " + itemName() + " back in the COUNTER ITEMS barrel (is it full?)");
				return;
			}
		}
		if (!clickSome(mc, menu)) return;
		if (++answerTicks <= 2 + lagTicks(mc)) return;
		if (counterItems(player) != ownBefore || !menu.getCarried().isEmpty()) {
			if (++retries <= MAX_RETRIES) {
				putBackCursor(mc);
				phaseTicks = 0;
				return;
			}
			pause("the counter items didn't go back into the barrel as planned");
			return;
		}
		giveBack = false;
		phaseTicks = 0;
	}

	private static int planMove(List<Slot> from, List<Slot> to, int n) {
		int max = new ItemStack(counterItem).getMaxStackSize();
		int[] room = new int[to.size()];
		for (int j = 0; j < room.length; j++) {
			ItemStack st = to.get(j).getItem();
			room[j] = st.isEmpty() ? max : st.is(counterItem) ? max - st.getCount() : 0;
		}
		int moved = 0;
		for (Slot src : from) {
			if (moved >= n) break;
			int cursor = src.getItem().getCount(), k = Math.min(cursor, n - moved);
			clicks.add(new Click(src.index, 0, false));
			for (int j = 0; j < room.length && k > 0; j++) {
				if (room[j] <= 0) continue;
				int m = Math.min(k, room[j]);
				if (m == cursor) {
					clicks.add(new Click(to.get(j).index, 0, false));
				} else {
					for (int i = 0; i < m; i++) clicks.add(new Click(to.get(j).index, 1, false));
				}
				room[j] -= m;
				cursor -= m;
				k -= m;
				moved += m;
			}
			if (cursor > 0) clicks.add(new Click(src.index, 0, false));
		}
		return moved;
	}

	private static int counterItems(LocalPlayer player) {
		Inventory inv = player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (counterItem != null && st.is(counterItem)) n += st.getCount();
		}
		return n;
	}

	private static void put(Minecraft mc, LocalPlayer player) {
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == player.inventoryMenu) {
			pause("the INPUT was closed");
			return;
		}
		Inventory inv = player.getInventory();
		if (phaseTicks == 1) {
			if (!putAgain) {
				inputBefore = 0;
				int other = 0;
				for (Slot s : menu.slots) {
					if (s.container == inv || !s.hasItem()) continue;
					if (s.getItem().is(counterItem)) inputBefore += s.getItem().getCount();
					else other += s.getItem().getCount();
				}
				if (other > 0) {
					stop("the INPUT has " + other + " other items in it. Take them out, then try again");
					return;
				}
				PearlControl.readNow(Minecraft.getInstance().level);
				stackedBefore = PearlControl.stacked(job.label);
			}
			planPut(menu, inv);
		}
		if (!clickSome(mc, menu)) return;
		if (++answerTicks <= 2 + lagTicks(mc)) return;
		int left = 0;
		for (int index : carried) if (inv.getItem(index).is(counterItem)) left += inv.getItem(index).getCount();
		if (left > 0 && ++retries <= MAX_RETRIES) {
			planPut(menu, inv);
			return;
		}
		// counted by what left your inventory: the cannon starts using the INPUT straight away. Counter items
		// still in the INPUT from before become charges for this axis too.
		int batch = inputBefore + carriedCount - left;
		if (batch <= 0) {
			pause("the INPUT didn't take the counter items");
			return;
		}
		if (left > 0) Msg.info(left + " " + itemName() + " didn't fit in the INPUT; they're still in your inventory.");
		job = new Job(job.label, batch);
		putAgain = false;
		carriedCount = 0;
		player.closeContainer();
		clickedContainer = false;
		closedAt = ticks;
		made = 0;
		settled = 0;
		setPhase(Phase.WAIT);
		lastProgressAt = mc.level.getGameTime();
	}

	private static void planPut(AbstractContainerMenu menu, Inventory inv) {
		clicks.clear();
		answerTicks = 0;
		for (Slot s : menu.slots) {
			if (s.container == inv && carried.contains(s.getContainerSlot()) && s.getItem().is(counterItem)) clicks.add(new Click(s.index, 0, true));
		}
		putPlanned = true;
	}

	private static boolean clickSome(Minecraft mc, AbstractContainerMenu menu) {
		for (int i = 0; i < CLICKS_PER_TICK && !clicks.isEmpty(); i++) {
			Click c = clicks.pollFirst();
			Compat.clickSlot(mc, menu.containerId, c.slot, c.button, c.quick);
		}
		return clicks.isEmpty();
	}

	private static void putBackCursor(Minecraft mc) {
		AbstractContainerMenu menu = mc.player.containerMenu;
		if (menu == mc.player.inventoryMenu || menu.getCarried().isEmpty()) return;
		for (Slot s : menu.slots) {
			if (s.container != mc.player.getInventory() && (!s.hasItem() || ItemStack.isSameItemSameComponents(s.getItem(), menu.getCarried()))) {
				Compat.clickSlot(mc, menu.containerId, s.index, 0, false);
				if (menu.getCarried().isEmpty()) return;
			}
		}
	}

	// any charge below the breeze chambers that isn't in a stack belongs to this batch. Returns how many are in its stack.
	private static int countCharges(ClientLevel level) {
		PearlControl.readNow(level);
		int inStack = PearlControl.stacked(job.label) - stackedBefore;
		int elsewhere = 0;
		for (Axis a : Axis.values()) if (a != job.label) elsewhere += PearlControl.stacked(a);
		int mine = Math.max(inStack, PearlControl.stored() + PearlControl.making() - elsewhere - stackedBefore);
		if (mine > made || inStack > settled) {
			made = Math.max(made, mine);
			settled = Math.max(settled, inStack);
			lastProgressAt = level.getGameTime();
			active(level);
		}
		return inStack;
	}

	// The cannon runs the items through before the first charge shows, about 8.5 ticks each:
	// over a minute for a big batch.
	private static long firstChargeTicks(int count) {
		return (long) PearlCalculator.loadTicks(count);
	}

	// in the cannon but in no stack: still up top, on their way, or stuck
	private static int loose() {
		int n = PearlControl.stored() + PearlControl.making();
		for (Axis a : Axis.values()) n -= PearlControl.stacked(a);
		return n;
	}

	private static void waitForCharges(ClientLevel level) {
		if (phaseTicks % 5 != 0) return;
		int inStack = countCharges(level);
		// charges arrive in bursts, so the HUD uses the time based bar instead of a count
		say("Loading " + job.label.label, job.count + " charge" + (job.count == 1 ? "" : "s"));
		// in game time, so a lagging server gets the same time
		long quiet = level.getGameTime() - lastProgressAt;
		// the cannon doesn't always make one charge per item, so let any still on their way arrive before switching
		if (inStack >= job.count && (loose() == 0 || quiet > BATCH_DONE_TICKS)) {
			batchRetried = false;
			nextJob();
			return;
		}
		// a charge dropping from the breezes can pass the wall corners on its way in; one flying out stays outside
		if (PearlControl.escaping() == 0) escapingSince = -1;
		else if (escapingSince < 0) escapingSince = level.getGameTime();
		else if (level.getGameTime() - escapingSince >= ESCAPE_TICKS) {
			stop(escaped(level));
			return;
		}
		// a batch that went quiet a little short, or that filled its axis anyway, carries on; short axes are topped up at the end
		boolean inPlace = made > 0 && made - inStack <= Math.max(1, made / 20);
		if (quiet > BATCH_DONE_TICKS && inPlace && (job.count - inStack <= Math.max(2, job.count / 10) || missing(job.label) <= 0)) {
			batchRetried = false;
			nextJob();
			return;
		}
		long stall = made == 0 ? firstChargeTicks(job.count) + STALL_TICKS : STALL_TICKS;
		// nothing at all, e.g. the items were used up while you were away: load the batch again, once
		if (quiet > stall && made == 0 && !batchRetried) {
			batchRetried = true;
			jobs.addFirst(new Job(job.label, job.count));
			expectedTicks += (int) PearlCalculator.loadTicks(job.count) + BATCH_TICKS;
			nextJob();
			return;
		}
		if (quiet > stall) {
			stop(made >= job.count ? "the " + job.label.label + " charges didn't reach their stack (" + inStack + "/" + job.count + ")"
					: "the cannon stopped making charges at " + made + "/" + job.count + ". Check its breezes and the INPUT");
		}
	}

	private static void settle(ClientLevel level) {
		say("Checking", "counting the charges");
		if (phaseTicks > SETTLE_TIMEOUT) {
			stop("the cannon didn't open its launch hole");
			return;
		}
		if (phaseTicks % 5 != 0 || !PearlControl.holeOpen()) return;
		PearlLayout l = pairing.pearl;
		int inStacks = 0;
		for (Axis a : Axis.values()) inStacks += PearlControl.stacked(a);
		if (inStacks < PearlControl.stored() && phaseTicks < SETTLE_TIMEOUT / 2) return; // still sliding into place
		List<String> extra = new ArrayList<>();
		boolean toppedUp = false;
		for (Axis world : new Axis[] {solution.axisA, solution.axisB}) {
			Axis label = l.labelFor(world);
			int want = solution.charges(world), have = PearlControl.stacked(label);
			if (have < want && topUps < MAX_TOP_UPS) {
				jobs.add(new Job(label, want - have));
				expectedTicks += (int) PearlCalculator.loadTicks(want - have) + 2 * PRESS_TICKS + BATCH_TICKS;
				toppedUp = true;
			} else if (have != want) {
				extra.add(label.label + " " + have + "/" + want);
			}
		}
		if (toppedUp) {
			topUps++;
			nextJob();
			return;
		}
		notExact = extra;
		// leave all selectors off
		setPhase(Phase.CLEAR);
	}

	private static List<String> notExact = List.of();

	private static void finish() {
		running = false;
		releaseKeys();
		say("", "");
		pairing.loaded = true;
		Store.save();
		if (!notExact.isEmpty()) {
			double[] land = PearlControl.landingFor(pairing, PearlControl.stackedByLabel());
			Msg.warn(String.format("Loaded, but not exactly: %s. The pearl will land near %.0f %.0f.", String.join(", ", notExact), land[0], land[1]));
		}
	}

	private static boolean reach(Minecraft mc, LocalPlayer player, BlockPos pos) {
		double reach = player.blockInteractionRange() - 0.3;
		if (new AABB(pos).distanceToSqr(player.getEyePosition()) <= reach * reach) {
			releaseKeys();
			stuck = 0;
			return true;
		}
		if (++stuck > STUCK_TICKS + 2 * lagTicks(mc)) {
			pause("couldn't get close enough to " + pos.toShortString());
			return false;
		}
		Vec3 target = pairing.pearl.standSpot();
		look(player, Vec3.atCenterOf(pos));
		double dx = target.x - player.getX(), dz = target.z - player.getZ();
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 0.2) {
			releaseKeys();
			return false;
		}
		float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
		double fwd = -dx * Mth.sin(yawRad) + dz * Mth.cos(yawRad);
		double left = dx * Mth.cos(yawRad) + dz * Mth.sin(yawRad);
		double t = 0.25 * len;
		setKey(mc.options.keyUp, fwd > t);
		setKey(mc.options.keyDown, fwd < -t);
		setKey(mc.options.keyLeft, left > t);
		setKey(mc.options.keyRight, left < -t);
		return false;
	}

	private static void look(LocalPlayer player, Vec3 target) {
		Vec3 d = target.subtract(player.getEyePosition());
		double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
		float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
		float pitch = (float) -(Mth.atan2(d.y, horiz) * Mth.RAD_TO_DEG);
		yaw = player.getYRot() + Mth.wrapDegrees(yaw - player.getYRot());
		player.setYRot(yaw);
		player.setXRot(Mth.clamp(pitch, -90f, 90f));
		player.yRotO = player.getYRot();
		player.xRotO = player.getXRot();
		player.connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), player.onGround(), player.horizontalCollision));
	}

	private static Vec3 shapeCenter(ClientLevel level, BlockPos pos, BlockState state) {
		VoxelShape shape = state.getShape(level, pos);
		return shape.isEmpty() ? Vec3.atCenterOf(pos) : shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
	}

	private static void setKey(KeyMapping key, boolean down) {
		key.setDown(down);
		movedKeys |= down;
	}

	private static void releaseKeys() {
		if (!movedKeys) return;
		Minecraft mc = Minecraft.getInstance();
		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		movedKeys = false;
	}

	private static String itemName() {
		return counterItem == null ? "items" : BuiltInRegistries.ITEM.getKey(counterItem).getPath().replace('_', ' ');
	}
}
