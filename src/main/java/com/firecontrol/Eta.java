package com.firecontrol;

import com.firecontrol.calc.FireCalculator;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;

// Impact (or pearl landing) countdown, in game ticks
public final class Eta {
	public static final int IMPACT_SHOW_TICKS = 100;
	// a pearl can be late when the server is slow to load the chunks on its way
	private static final int LANDING_WAIT_TICKS = 20 * 90;
	private static final double TELEPORT_BLOCKS = 48;
	private static final java.util.Set<Pairing> ANNOUNCED = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	private static Vec3 lastPos;

	private Eta() {
	}

	public static void start(Pairing p, FireCalculator.Solution s, long gameTime) {
		startTicks(p, s.totalTicksRounded(), (int) Math.ceil(s.payloadTicks), gameTime);
	}

	public static void startTicks(Pairing p, int totalTicks, int markTicks, long gameTime) {
		p.etaStart = gameTime;
		p.etaTotalTicks = totalTicks;
		p.etaPayloadTicks = markTicks;
		p.etaLanded = false;
		ANNOUNCED.remove(p);
		Store.save();
	}

	public static void stop(Pairing p) {
		p.etaStart = -1;
		Store.save();
	}

	public static boolean running(Pairing p, long gameTime) {
		return p.etaStart >= 0 && gameTime - p.etaStart < p.etaTotalTicks + afterZero(p);
	}

	// how long it shows after zero; a pearl over new terrain can take a few times its countdown
	private static int afterZero(Pairing p) {
		return waitsForLanding(p) ? Math.max(LANDING_WAIT_TICKS, 3 * p.etaTotalTicks) : IMPACT_SHOW_TICKS;
	}

	public static long remaining(Pairing p, long gameTime) {
		return p.etaStart + p.etaTotalTicks - gameTime;
	}

	// a pearl past its countdown that hasn't teleported you yet
	public static boolean waitsForLanding(Pairing p) {
		return p.isPearl() && !p.etaLanded;
	}

	public static void landed(Pairing p, long gameTime) {
		p.etaTotalTicks = (int) (gameTime - p.etaStart);
		p.etaLanded = true;
		ANNOUNCED.remove(p);
		Store.save();
	}

	static void tick(ClientLevel level) {
		long now = level.getGameTime();
		Vec3 pos = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.position();
		boolean teleported = pos != null && lastPos != null && pos.distanceToSqr(lastPos) > TELEPORT_BLOCKS * TELEPORT_BLOCKS;
		lastPos = pos;
		for (Pairing p : FireControl.pairingsHere()) {
			if (p.etaStart < 0) {
				ANNOUNCED.remove(p);
				continue;
			}
			// the pearl landing teleports you
			if (teleported && waitsForLanding(p) && now - p.etaStart > PearlControl.IN_CANNON_TICKS / 2) PearlControl.landed(p, now);
			long left = remaining(p, now);
			// game time can jump, so only ring once when crossing zero
			if (left <= 0 && left > -40 && !waitsForLanding(p) && ANNOUNCED.add(p)) {
				Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 0.7f));
			}
			if (left < -afterZero(p)) stop(p);
		}
	}
}
