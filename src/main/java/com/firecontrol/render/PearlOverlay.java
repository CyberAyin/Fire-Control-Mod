package com.firecontrol.render;

import com.firecontrol.Eta;
import com.firecontrol.FireControl;
import com.firecontrol.PearlControl;
import com.firecontrol.PearlLoader;
import com.firecontrol.calc.FireCalculator;
import com.firecontrol.calc.PearlCalculator;
import com.firecontrol.calc.PearlCalculator.Axis;
import com.firecontrol.data.Pairing;
import com.firecontrol.world.PearlLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.Util;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class PearlOverlay {
	private static final int SHORT = 0xFFFFD400;
	private static final int RIGHT = 0xFF60FF80;
	private static final int OVER = 0xFFFF5050;
	private static final int READY = 0xFF30E0FF;

	private PearlOverlay() {
	}

	public static void draw() {
		Minecraft mc = Minecraft.getInstance();
		Pairing p = FireControl.active();
		if (!FireControl.overlayEnabled || mc.level == null || p == null || !p.isPearl() || !FireControl.playerNearPanel() || !PearlControl.known()) return;
		PearlLayout l = p.pearl;
		PearlCalculator.Solution s = PearlControl.solution();

		if (s != null && !p.fired) {
			for (Axis label : Axis.values()) {
				int want = s.charges(l.worldFor(label)), have = PearlControl.stacked(label);
				if (want == 0 && have == 0) continue;
				int color = have < want ? SHORT : have == want ? RIGHT : OVER;
				String text = label == PearlLoader.loadingAxis() ? label.label + " " + PearlLoader.loadingProgress() : label.label + " " + have + "/" + want;
				Vec3 at = Vec3.atCenterOf(l.sign(label)).add(0, 0.6, 0);
				Gizmos.billboardText(text, at, TextGizmo.Style.forColorAndCentered(color).withScale(0.3f)).setAlwaysOnTop();
			}
		}

		boolean ready = PearlControl.ready();
		AABB hole = l.hole().inflate(0.02);
		if (ready) {
			float pulse = (float) (0.55 + 0.45 * Math.sin(Util.getMillis() / 150.0));
			int stroke = ((int) (pulse * 255) << 24) | (READY & 0xFFFFFF);
			Gizmos.cuboid(hole, GizmoStyle.strokeAndFill(stroke, 3f, ((int) (pulse * 80) << 24) | (READY & 0xFFFFFF))).setAlwaysOnTop();
			Gizmos.billboardText("Step in · throw straight up", hole.getCenter().add(0, 1.1, 0), TextGizmo.Style.forColorAndCentered(READY).withScale(0.35f)).setAlwaysOnTop();
		}

		String label = statusLabel(p, s, ready);
		if (label != null) {
			Vec3 top = l.point(4, 4.6, -1.6);
			Gizmos.billboardText(label, top, TextGizmo.Style.forColorAndCentered(0xFFFFE070).withScale(0.4f)).setAlwaysOnTop();
		}
	}

	private static String statusLabel(Pairing p, PearlCalculator.Solution s, boolean ready) {
		if (PearlLoader.running()) return PearlLoader.status();
		if (PearlLoader.paused()) return "Paused · Resume in chat";
		Minecraft mc = Minecraft.getInstance();
		if (Eta.running(p, mc.level.getGameTime())) {
			long left = Eta.remaining(p, mc.level.getGameTime());
			return left > 0 ? "Landing in " + FireCalculator.formatTicks(left) : "Landed";
		}
		if (s == null) return "Pearl cannon · no target";
		if (ready) return "✔ Ready";
		if (p.loaded && !PearlControl.holeOpen()) return "Loaded · the hole opens in a moment";
		return "To load: " + PearlControl.plan(p, s);
	}
}
