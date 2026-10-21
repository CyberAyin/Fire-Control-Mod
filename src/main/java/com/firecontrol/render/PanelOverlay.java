package com.firecontrol.render;

import com.firecontrol.AutoInput;
import com.firecontrol.Eta;
import com.firecontrol.FireControl;
import com.firecontrol.calc.FireCalculator;
import com.firecontrol.data.Pairing;
import com.firecontrol.world.Blocks2;
import com.firecontrol.world.PanelLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// yellow = press, green = correct, cyan = Input Settings once everything matches
public final class PanelOverlay {
	private static final int WRONG_FILL = 0x90FFC800;
	private static final int WRONG_STROKE = 0xFFFFD400;
	private static final int RIGHT_FILL = 0x3830FF50;
	private static final int UNKNOWN_FILL = 0x40A0A0A0;
	private static final int INPUT_READY = 0xFF30E0FF;
	private static final int INPUT_IDLE = 0x8030E0FF;

	private PanelOverlay() {
	}

	public static void draw() {
		Minecraft mc = Minecraft.getInstance();
		Pairing p = FireControl.active();
		FireCalculator.Solution s = FireControl.solution();
		if (!FireControl.overlayEnabled || mc.level == null || p == null || p.panel == null || !FireControl.playerNearPanel()) return;
		PanelLayout panel = p.panel;
		Direction front = panel.frontDir();
		ClientLevel level = mc.level;

		if (s != null && !p.fired && panel.matchesType(p.type())) {
			for (int i = 0; i < panel.size(); i++) {
				BlockPos bulb = panel.bulb(i);
				if (bulb == null) continue;
				int fill = !FireControl.isKnown(i) ? UNKNOWN_FILL : FireControl.isWrong(i) ? WRONG_FILL : RIGHT_FILL;
				AABB face = faceBlock(level, panel, i, bulb);
				Gizmos.rect(new Vec3(face.minX, face.minY, face.minZ), new Vec3(face.maxX, face.maxY, face.maxZ), front, GizmoStyle.fill(fill)).setAlwaysOnTop();
				if (FireControl.isWrong(i)) {
					BlockPos button = panel.button(i);
					if (button != null) Gizmos.cuboid(buttonBox(level, button), GizmoStyle.stroke(WRONG_STROKE, 2.5f)).setAlwaysOnTop();
				}
			}
		}

		BlockPos input = panel.inputButtonPos();
		if (input != null) {
			boolean ready = FireControl.panelReady() && s != null && s.isValid();
			int color = INPUT_IDLE;
			if (ready) {
				float pulse = (float) (0.55 + 0.45 * Math.sin(Util.getMillis() / 150.0));
				color = ((int) (pulse * 255) << 24) | (INPUT_READY & 0xFFFFFF);
			}
			int fill = ready ? (((color >>> 24) / 3) << 24) | (color & 0xFFFFFF) : 0;
			Gizmos.cuboid(buttonBox(level, input).inflate(0.06), GizmoStyle.strokeAndFill(color, ready ? 3f : 1.5f, fill)).setAlwaysOnTop();
			if (ready) {
				Gizmos.billboardText("INPUT SETTINGS", Vec3.atCenterOf(input).add(0, 0.55, 0), TextGizmo.Style.forColorAndCentered(INPUT_READY).withScale(0.35f)).setAlwaysOnTop();
			}
		}

		Vec3 top = topCenter(panel);
		String label = statusLabel(p, s);
		if (label != null) {
			Gizmos.billboardText(label, top, TextGizmo.Style.forColorAndCentered(0xFFFFE070).withScale(0.4f)).setAlwaysOnTop();
		}
	}

	private static String statusLabel(Pairing p, FireCalculator.Solution s) {
		if (AutoInput.running()) return "Entering settings…";
		if (AutoInput.paused()) return "Paused · Resume in chat";
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null && Eta.running(p, level.getGameTime())) {
			long left = Eta.remaining(p, level.getGameTime());
			return left > 0 ? "Fired · impact in " + FireCalculator.formatTicks(left) : "Impact!";
		}
		if (p.fired) return "Fired · set a new target";
		if (s == null) return p.type().displayName + " · no target";
		if (!s.isValid()) return "Invalid target";
		int wrong = FireControl.wrongCount();
		if (wrong < 0) return null;
		if (wrong == 0) return "✔ Ready: press INPUT SETTINGS";
		return wrong + " button" + (wrong == 1 ? "" : "s") + " to press";
	}

	private static AABB faceBlock(ClientLevel level, PanelLayout panel, int i, BlockPos bulb) {
		BlockPos button = panel.button(i);
		BlockPos target = bulb;
		if (button != null) {
			BlockState st = level.getBlockState(button);
			if (Blocks2.isButton(st)) target = Blocks2.buttonSupport(button, st);
		}
		return new AABB(target).inflate(0.004);
	}

	private static AABB buttonBox(ClientLevel level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		var shape = st.getShape(level, pos);
		return shape.isEmpty() ? new AABB(pos).deflate(0.3) : shape.bounds().move(pos).inflate(0.02);
	}

	private static Vec3 topCenter(PanelLayout panel) {
		AABB b = panel.bounds();
		Direction f = panel.frontDir();
		return new Vec3((b.minX + b.maxX) / 2 + f.getStepX() * 0.6, b.maxY + 0.6, (b.minZ + b.maxZ) / 2 + f.getStepZ() * 0.6);
	}
}
