package com.firecontrol;

import com.firecontrol.calc.FireCalculator;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

// Impact (or pearl landing) countdown, in game ticks
public final class Eta {
	public static final int IMPACT_SHOW_TICKS = 100;
	private static final java.util.Set<Pairing> ANNOUNCED = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

	private Eta() {
	}

	public static void start(Pairing p, FireCalculator.Solution s, long gameTime) {
		startTicks(p, s.totalTicksRounded(), (int) Math.ceil(s.payloadTicks), gameTime);
	}

	public static void startTicks(Pairing p, int totalTicks, int markTicks, long gameTime) {
		p.etaStart = gameTime;
		p.etaTotalTicks = totalTicks;
		p.etaPayloadTicks = markTicks;
		ANNOUNCED.remove(p);
		Store.save();
	}

	public static void stop(Pairing p) {
		p.etaStart = -1;
		Store.save();
	}

	public static boolean running(Pairing p, long gameTime) {
		return p.etaStart >= 0 && gameTime - p.etaStart < p.etaTotalTicks + IMPACT_SHOW_TICKS;
	}

	public static long remaining(Pairing p, long gameTime) {
		return p.etaStart + p.etaTotalTicks - gameTime;
	}

	static void tick(ClientLevel level) {
		long now = level.getGameTime();
		for (Pairing p : FireControl.pairingsHere()) {
			if (p.etaStart < 0) {
				ANNOUNCED.remove(p);
				continue;
			}
			long left = remaining(p, now);
			// game time can jump, so only ring once when crossing zero
			if (left <= 0 && left > -40 && ANNOUNCED.add(p)) {
				Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 0.7f));
			}
			if (left < -IMPACT_SHOW_TICKS) stop(p);
		}
	}
}
