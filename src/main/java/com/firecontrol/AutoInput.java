package com.firecontrol;

import com.firecontrol.calc.FireCalculator;
import com.firecontrol.compat.Compat;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import com.firecontrol.world.Blocks2;
import com.firecontrol.world.PanelLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// /firecontrol InputSettings: looks at each wrong button and presses it, walking along the panel if needed
public final class AutoInput {
	private static final int TIMEOUT_TICKS = 20 * 30;
	private static final int STUCK_TICKS = 40;
	private static final int MAX_ATTEMPTS = 3;
	private static final int SETTLE_TICKS = 60;

	private static boolean running;
	private static Pairing pairing;
	private static final Deque<Integer> queue = new ArrayDeque<>();
	private static int[] attempts = new int[0];
	private static long[] pressedAt = new long[0];
	private static int presses, planned, ticks, stuck, settle, cooldown;
	private static boolean movedKeys;
	private static Screen opener;
	private static boolean paused;
	private static boolean[] pausedBits;

	private AutoInput() {
	}

	public static boolean running() {
		return running;
	}

	public static boolean paused() {
		FireCalculator.Solution s = FireControl.solution();
		return paused && FireControl.active() == pairing && s != null && java.util.Arrays.equals(s.bits, pausedBits);
	}

	public static String progress() {
		return Math.min(presses, planned) + "/" + planned;
	}

	public static double fraction() {
		return planned == 0 ? 0 : Math.min(1.0, (double) presses / planned);
	}

	public static void start() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Pairing p = FireControl.active();
		FireCalculator.Solution s = FireControl.solution();
		if (player == null || mc.level == null || mc.gameMode == null) return;
		if (p == null) {
			Msg.error("Nothing paired yet. Stand near your control panel and run /firecontrol pair.");
			return;
		}
		if (p.panel == null || !p.panel.matchesType(p.type())) {
			Msg.error("This pairing has no matching control panel. Run /firecontrol pair at the panel.");
			return;
		}
		if (s == null) {
			Msg.error("Set a target first: /firecontrol target <x> <y> <z> nuke <size>");
			return;
		}
		if (!s.isValid()) {
			Msg.error("The target is invalid: " + String.join("; ", s.errors));
			return;
		}
		if (!p.panel.operatorArea().contains(player.position())) {
			Msg.error("Stand at the control panel to use InputSettings (within a few blocks in front of the buttons).");
			return;
		}
		// the panel shows what's left, only the press count carries over
		boolean resume = paused();
		paused = false;
		pairing = p;
		if (!resume) {
			attempts = new int[p.panel.size()];
			pressedAt = new long[p.panel.size()];
			presses = 0;
		}
		ticks = 0;
		stuck = 0;
		settle = 0;
		cooldown = 0;
		List<Integer> wrong = wrongCells(mc.level, s);
		List<Integer> noButton = wrong.stream().filter(i -> p.panel.button(i) == null).toList();
		if (!noButton.isEmpty()) Msg.warn(noButton.size() + " wrong bulb(s) have no button I could find; toggle those by hand.");
		wrong.removeAll(noButton);
		if (wrong.isEmpty()) {
			if (noButton.isEmpty()) Msg.ok("The panel already matches. Press INPUT SETTINGS to fire.");
			return;
		}
		queue.clear();
		queue.addAll(order(wrong, p.panel, player));
		planned = (resume ? presses : 0) + queue.size();
		running = true;
		opener = Compat.screen(mc);
	}

	public static void stop(String reason) {
		paused = false;
		if (!running) return;
		running = false;
		releaseKeys();
		queue.clear();
		if (reason != null) Msg.warn("InputSettings stopped: " + reason);
	}

	private static void pause(String reason) {
		running = false;
		releaseKeys();
		queue.clear();
		FireCalculator.Solution s = FireControl.solution();
		paused = s != null;
		pausedBits = s == null ? null : s.bits.clone();
		Msg.send(Msg.text("InputSettings paused: " + reason + ". ", ChatFormatting.YELLOW)
				.append(Msg.button("Resume", "/firecontrol InputSettings", "Carries on where it stopped (stand at the panel)")));
	}

	private static List<Integer> wrongCells(ClientLevel level, FireCalculator.Solution s) {
		List<Integer> out = new ArrayList<>();
		PanelLayout panel = pairing.panel;
		for (int i = 0; i < panel.size(); i++) {
			BlockPos b = panel.bulb(i);
			if (b == null || !level.isLoaded(b)) continue;
			BlockState st = level.getBlockState(b);
			if (Blocks2.isBulb(st) && Blocks2.isLit(st) != s.bits[i]) out.add(i);
		}
		return out;
	}

	// snake column by column from the nearest side to keep head movement short
	private static List<Integer> order(List<Integer> cells, PanelLayout panel, LocalPlayer player) {
		Direction right = panel.frontDir().getAxis().isHorizontal() ? panel.frontDir().getOpposite().getClockWise() : player.getDirection().getClockWise();
		Vec3 pos = player.position();
		List<Integer> sorted = new ArrayList<>(cells);
		sorted.sort(Comparator.comparingDouble(i -> lateral(panel.button(i), right)));
		double first = sorted.isEmpty() ? 0 : lateral(panel.button(sorted.get(0)), right);
		double last = sorted.isEmpty() ? 0 : lateral(panel.button(sorted.get(sorted.size() - 1)), right);
		double me = pos.x * right.getStepX() + pos.z * right.getStepZ();
		if (Math.abs(me - last) < Math.abs(me - first)) java.util.Collections.reverse(sorted);
		return sorted;
	}

	private static double lateral(BlockPos p, Direction right) {
		return p.getX() * right.getStepX() + p.getZ() * right.getStepZ();
	}

	public static void tick(Minecraft mc) {
		if (!running) return;
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		FireCalculator.Solution s = FireControl.solution();
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
		if (FireControl.active() != pairing || s == null) {
			stop("the target or pairing changed");
			return;
		}
		if (screen != null) {
			pause("a menu was opened");
			return;
		}
		if (!pairing.panel.operatorArea().contains(player.position())) {
			pause("you left the control panel area");
			return;
		}
		if (player.hurtTime > 0) {
			pause("you took damage");
			return;
		}
		if (++ticks > TIMEOUT_TICKS) {
			stop("took too long");
			return;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}

		// skip cells that are already right
		while (!queue.isEmpty() && !isWrong(level, s, queue.peekFirst())) queue.pollFirst();

		if (queue.isEmpty()) {
			releaseKeys();
			settle(level, s);
			return;
		}

		int cell = queue.peekFirst();
		BlockPos buttonPos = pairing.panel.button(cell);
		BlockState button = level.getBlockState(buttonPos);
		if (!Blocks2.isButton(button)) {
			queue.pollFirst();
			Msg.warn("A panel button is missing at " + buttonPos.toShortString() + ".");
			return;
		}
		if (Blocks2.isPressed(button)) {
			// still pressed, try again later
			queue.addLast(queue.pollFirst());
			if (queue.size() == 1) cooldown = 2;
			return;
		}

		Vec3 aim = aimPoint(level, buttonPos, button);
		Vec3 eye = player.getEyePosition();
		double reach = player.blockInteractionRange() - 0.25;
		if (new AABB(buttonPos).distanceToSqr(eye) > reach * reach) {
			lookAt(player, aim);
			if (!walkToward(mc, player, buttonPos, button) || ++stuck > STUCK_TICKS) {
				queue.pollFirst();
				stuck = 0;
				Msg.warn("Couldn't reach the button at " + buttonPos.toShortString() + "; press it by hand.");
			}
			return;
		}
		releaseKeys();
		stuck = 0;

		lookAt(player, aim);
		// rotation first so the server sees us looking at the button
		player.connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), player.onGround(), player.horizontalCollision));
		Direction face = Blocks2.buttonOutward(button);
		mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(aim, face, buttonPos, false));
		Compat.swing(player);

		queue.pollFirst();
		attempts[cell]++;
		pressedAt[cell] = level.getGameTime();
		presses++;
		settle = 0;
		cooldown = Math.max(0, Store.data().pressInterval - 1);
	}

	private static void settle(ClientLevel level, FireCalculator.Solution s) {
		List<Integer> wrong = wrongCells(level, s);
		if (wrong.isEmpty()) {
			running = false;
			return;
		}
		settle++;
		long now = level.getGameTime();
		List<Integer> retry = new ArrayList<>();
		boolean waiting = false;
		for (int i : wrong) {
			BlockPos b = pairing.panel.button(i);
			if (b == null || attempts[i] >= MAX_ATTEMPTS) continue;
			// not settled until the button is released again
			if (Blocks2.isPressed(level.getBlockState(b)) || now - pressedAt[i] < 10) {
				waiting = true;
				continue;
			}
			retry.add(i);
		}
		if (!retry.isEmpty() && (!waiting || settle > SETTLE_TICKS)) {
			queue.addAll(retry);
			planned += retry.size();
			settle = 0;
			return;
		}
		if (!waiting && retry.isEmpty() || settle > SETTLE_TICKS * 2) {
			running = false;
			Msg.warn(wrong.size() + " bulb(s) still don't match. Check the yellow squares on the panel.");
		}
	}

	private static boolean isWrong(ClientLevel level, FireCalculator.Solution s, int cell) {
		BlockPos b = pairing.panel.bulb(cell);
		if (b == null) return false;
		BlockState st = level.getBlockState(b);
		return Blocks2.isBulb(st) && Blocks2.isLit(st) != s.bits[cell];
	}

	private static Vec3 aimPoint(ClientLevel level, BlockPos pos, BlockState state) {
		VoxelShape shape = state.getShape(level, pos);
		return shape.isEmpty() ? Vec3.atCenterOf(pos) : shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
	}

	private static void lookAt(LocalPlayer player, Vec3 target) {
		Vec3 d = target.subtract(player.getEyePosition());
		double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
		float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
		float pitch = (float) -(Mth.atan2(d.y, horiz) * Mth.RAD_TO_DEG);
		// keep yaw continuous so the camera turns the short way
		yaw = player.getYRot() + Mth.wrapDegrees(yaw - player.getYRot());
		player.setYRot(yaw);
		player.setXRot(Mth.clamp(pitch, -90f, 90f));
		player.yRotO = player.getYRot();
		player.xRotO = player.getXRot();
	}

	private static boolean walkToward(Minecraft mc, LocalPlayer player, BlockPos buttonPos, BlockState button) {
		Direction front = pairing.panel.frontDir();
		if (!front.getAxis().isHorizontal()) return false;
		Vec3 plane = Vec3.atCenterOf(buttonPos).subtract(front.getStepX() * 0.5, 0, front.getStepZ() * 0.5);
		Direction right = front.getOpposite().getClockWise();
		Vec3 pos = player.position();
		double depth = (pos.x - plane.x) * front.getStepX() + (pos.z - plane.z) * front.getStepZ();
		double lat = pos.x * right.getStepX() + pos.z * right.getStepZ();
		double buttonLat = (buttonPos.getX() + 0.5) * right.getStepX() + (buttonPos.getZ() + 0.5) * right.getStepZ();
		double wantDepth = Mth.clamp(depth, 0.45, 0.9);
		double wantLat = Mth.clamp(lat, buttonLat - 1.2, buttonLat + 1.2);
		double dx = (wantDepth - depth) * front.getStepX() + (wantLat - lat) * right.getStepX();
		double dz = (wantDepth - depth) * front.getStepZ() + (wantLat - lat) * right.getStepZ();
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 0.08) {
			releaseKeys();
			return true;
		}
		float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
		double fwd = -dx * Mth.sin(yawRad) + dz * Mth.cos(yawRad);
		double left = dx * Mth.cos(yawRad) + dz * Mth.sin(yawRad);
		double t = 0.25 * len;
		setKey(mc.options.keyUp, fwd > t);
		setKey(mc.options.keyDown, fwd < -t);
		setKey(mc.options.keyLeft, left > t);
		setKey(mc.options.keyRight, left < -t);
		setKey(mc.options.keyShift, false);
		return true;
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
}
