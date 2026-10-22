package com.firecontrol.render;

import com.firecontrol.AutoInput;
import com.firecontrol.Eta;
import com.firecontrol.FireControl;
import com.firecontrol.PearlControl;
import com.firecontrol.PearlLoader;
import com.firecontrol.calc.FireCalculator;
import com.firecontrol.calc.PearlCalculator;
import com.firecontrol.compat.Compat;
import com.firecontrol.data.Pairing;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.util.Mth;

public final class FireControlHud {
	private static final int TEXT_OFFSET = 64;
	private static final int MIN_BAR_WIDTH = 100;

	private static final int AMBER = 0xFFFFB84A;
	private static final int GREEN = 0xFF6BE38A;
	private static final int CYAN = 0xFF55D6FF;
	private static final int RED = 0xFFFF5F5F;
	private static final int GREY = 0xFFA9B1BA;
	private static final int DETAIL = 0xFFD2D6DC;

	private FireControlHud() {
	}

	public static void draw(Compat.Canvas g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || Compat.hudHidden(mc)) return;
		long now = mc.level.getGameTime();
		Pairing eta = runningEta(now);
		if (eta != null) {
			drawEta(g, mc.font, eta, now, delta.getGameTimeDeltaPartialTick(false));
		} else {
			drawStatus(g, mc);
		}
	}

	private static Pairing runningEta(long now) {
		Pairing active = FireControl.active();
		if (active != null && Eta.running(active, now)) return active;
		for (Pairing p : FireControl.pairingsHere()) {
			if (Eta.running(p, now)) return p;
		}
		return null;
	}

	private static void drawEta(Compat.Canvas g, Font font, Pairing p, long now, float partial) {
		double left = Eta.remaining(p, now) - partial;
		double total = Math.max(1, p.etaTotalTicks);
		double done = Mth.clamp(1 - left / total, 0, 1);
		double mark = p.etaPayloadTicks > 0 && p.etaPayloadTicks < total ? p.etaPayloadTicks / total : -1;
		if (p.isPearl()) {
			if (left > 0) panel(g, font, "➶ Landing in " + shortTime(left), "", CYAN, done, mark);
			else if (Eta.waitsForLanding(p)) panel(g, font, "➶ Landing…", "", CYAN, 1, mark);
			else panel(g, font, "✔ Landed", "", GREEN, -1, -1);
		} else if (left <= 0) {
			panel(g, font, "✸ IMPACT", "", (now / 5) % 2 == 0 ? RED : AMBER, -1, -1);
		} else {
			// blink for the last 10 seconds
			int color = left < 200 && (now / 5) % 2 == 0 ? RED : AMBER;
			panel(g, font, "☢ Impact in " + shortTime(left), "", color, done, mark);
		}
	}

	private static void drawStatus(Compat.Canvas g, Minecraft mc) {
		Pairing p = FireControl.active();
		if (p != null && p.isPearl()) {
			drawPearlStatus(g, mc, p);
			return;
		}
		if (p == null || p.panel == null || !p.panel.operatorArea().contains(mc.player.position())) return;
		FireCalculator.Solution s = FireControl.solution();
		Font font = mc.font;
		int wrong = FireControl.wrongCount();
		if (AutoInput.running()) {
			panel(g, font, "Entering settings", AutoInput.progress(), AMBER, smoothed(AutoInput.fraction()), -1);
		} else if (AutoInput.paused()) {
			panel(g, font, "Paused", "click Resume in chat", AMBER, AutoInput.fraction(), -1);
		} else if (p.fired) {
			panel(g, font, "✔ Fired", "set a new target in /firecontrol", GREEN, -1, -1);
		} else if (s == null) {
			panel(g, font, p.type().displayName + " paired", "/firecontrol target", GREY, -1, -1);
		} else if (!s.isValid()) {
			panel(g, font, "✖ Target invalid", "see /firecontrol", RED, -1, -1);
		} else if (wrong == 0) {
			panel(g, font, "✔ Panel ready", "press INPUT SETTINGS", GREEN, -1, -1);
		} else if (wrong > 0) {
			panel(g, font, wrong + " button" + (wrong == 1 ? "" : "s") + " to press", "marked yellow on the panel", AMBER, -1, -1);
		}
	}

	private static void drawPearlStatus(Compat.Canvas g, Minecraft mc, Pairing p) {
		// a paused load stays in view while the cannon still works on it
		if (!p.pearl.operatorArea().contains(mc.player.position()) && !PearlLoader.carriesOn()) return;
		PearlCalculator.Solution s = PearlControl.solution();
		Font font = mc.font;
		if (PearlLoader.running()) {
			if (!PearlLoader.title().isEmpty()) panel(g, font, PearlLoader.title(), PearlLoader.detail(), AMBER, PearlLoader.progress(), -1);
		} else if (PearlLoader.paused()) {
			panel(g, font, "Paused", PearlLoader.carriesOn() ? "cannon still working · " + PearlLoader.timeLeft() : "click Resume in chat", AMBER, PearlLoader.progress(), -1);
		} else if (p.fired) {
			panel(g, font, "✔ Pearl sent", "set a new target in /firecontrol", GREEN, -1, -1);
		} else if (s == null) {
			panel(g, font, "Pearl cannon paired", "/firecontrol target <x> <z>", GREY, -1, -1);
		} else if (PearlControl.ready()) {
			panel(g, font, "✔ Ready", "step into the hole, throw straight up", CYAN, -1, -1);
		} else if (p.loaded && PearlControl.known() && !PearlControl.holeOpen()) {
			panel(g, font, "Loaded", "the hole opens in a moment", AMBER, -1, -1);
		} else if (s.total() > 0) {
			panel(g, font, "To load: " + PearlControl.plan(p, s), "/firecontrol InputSettings", AMBER, -1, -1);
		}
	}

	private static void panel(Compat.Canvas g, Font font, String title, String detail, int accent, double progress, double mark) {
		int w = g.width(), h = g.height();
		String rest = detail.isEmpty() ? "" : "  " + detail;
		int titleW = font.width(title), textW = titleW + font.width(rest);
		boolean bar = progress >= 0;
		int inner = Math.max(textW, bar ? MIN_BAR_WIDTH : 0);
		int x0 = (w - inner) / 2 - 7, x1 = (w + inner) / 2 + 6;
		int textY = h - TEXT_OFFSET;
		int y0 = textY - 4, y1 = textY + (bar ? 16 : 12);

		g.fill(x0, y0, x1, y1, 0xC8101318);
		g.fill(x0, y0, x1, y0 + 1, 0x38FFFFFF);
		g.fill(x0, y1 - 1, x1, y1, 0x60000000);
		g.fill(x0, y0, x0 + 2, y1, accent);

		int tx = (w - textW) / 2;
		g.text(font, title, tx, textY, accent);
		if (!rest.isEmpty()) g.text(font, rest, tx + titleW, textY, DETAIL);

		if (bar) bar(g, (w - inner) / 2, textY + 11, inner, progress, mark, accent);
	}

	private static void bar(Compat.Canvas g, int x0, int y, int width, double progress, double mark, int accent) {
		double fill = width * Mth.clamp(progress, 0, 1);
		g.fill(x0, y, x0 + width, y + 3, 0x2CFFFFFF);
		if (fill > 0) g.fillGradientFine(x0, y, x0 + fill, y + 3, lighten(accent, 0.35f), accent);
		if (mark > 0 && mark < 1) {
			int mx = x0 + (int) Math.round(width * mark);
			g.fill(mx, y - 1, mx + 1, y + 4, 0xFFFFFFFF);
		}
	}

	private static double shown;
	private static long shownAt;

	// smooths the InputSettings bar between presses
	private static double smoothed(double target) {
		long now = net.minecraft.util.Util.getMillis();
		if (target < shown - 0.25 || now - shownAt > 500) {
			shown = target;
		} else {
			shown += (target - shown) * (1 - Math.exp(-(now - shownAt) / 120.0));
		}
		shownAt = now;
		return shown;
	}

	private static int lighten(int argb, float f) {
		int r = (argb >> 16) & 0xFF, gr = (argb >> 8) & 0xFF, b = argb & 0xFF;
		r += (int) ((255 - r) * f);
		gr += (int) ((255 - gr) * f);
		b += (int) ((255 - b) * f);
		return (argb & 0xFF000000) | (r << 16) | (gr << 8) | b;
	}

	// 2:31 or 1:02:31
	private static String shortTime(double ticks) {
		long sec = (long) Math.ceil(ticks / 20.0);
		return sec >= 3600 ? String.format("%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60) : String.format("%d:%02d", sec / 60, sec % 60);
	}
}
