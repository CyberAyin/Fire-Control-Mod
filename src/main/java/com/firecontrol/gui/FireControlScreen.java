package com.firecontrol.gui;

import com.firecontrol.Actions;
import com.firecontrol.AutoInput;
import com.firecontrol.Eta;
import com.firecontrol.FireControl;
import com.firecontrol.PearlControl;
import com.firecontrol.PearlLoader;
import com.firecontrol.calc.CannonType;
import com.firecontrol.calc.FireCalculator;
import com.firecontrol.calc.FireMode;
import com.firecontrol.calc.PearlCalculator;
import com.firecontrol.command.FireControlCommands;
import com.firecontrol.compat.Compat;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import com.firecontrol.world.GroundHeight;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

// Main menu. Only vanilla widgets so it works on every supported version.
public final class FireControlScreen extends Screen {
	static final int W = 300;
	private static boolean openNextTick;

	private Pairing pairing;
	private EditBox xBox, yBox, zBox, sizeBox, slotBox;
	private CycleButton<FireMode> modeButton;
	private CycleButton<Integer> sfcSizeButton;
	private CycleButton<Boolean> overworldCoords;
	private StringWidget sizeLabel, solutionLine, panelLine;
	private Button enterButton;
	private boolean building;
	private String inputError;
	private String enterTip = "", solutionTip = "", yTip = "";
	private int[] waitingGround;

	public FireControlScreen() {
		super(Component.literal("FireControl"));
	}

	// next tick, so the chat screen can close first
	public static void openNextTick() {
		openNextTick = true;
	}

	public static void clientTick(Minecraft mc) {
		if (!openNextTick) return;
		openNextTick = false;
		if (mc.player != null) Compat.setScreen(mc, new FireControlScreen());
	}

	@Override
	protected void init() {
		building = true;
		pairing = FireControl.resolveActive();
		int left = width / 2 - W / 2;
		int y = Math.max(6, height / 2 - 100);

		label(Component.literal("FireControl").withStyle(ChatFormatting.BOLD), width / 2, y, true);
		label(pairingLine(), width / 2, y + 14, true);

		// target
		int row = y + 34;
		label(Component.literal("Target"), left, row + 6, false);
		xBox = numberBox(left + 52, row, 52, "X");
		yBox = numberBox(left + 116, row, 52, "Y");
		zBox = numberBox(left + 180, row, 52, "Z");
		label(Component.literal("X").withStyle(ChatFormatting.GRAY), left + 44, row + 6, false);
		label(Component.literal("Y").withStyle(ChatFormatting.GRAY), left + 108, row + 6, false);
		label(Component.literal("Z").withStyle(ChatFormatting.GRAY), left + 172, row + 6, false);
		Button here = addRenderableWidget(Button.builder(Component.literal("My position"), b -> useMyPosition()).bounds(left + 238, row, 62, 20).build());
		here.setTooltip(Tooltip.create(Component.literal("Target the block you're standing on")));

		// payload
		row += 26;
		CannonType type = pairing == null ? CannonType.MS : pairing.type();
		boolean pearl = pairing != null && pairing.isPearl();
		if (pearl) {
			here.setTooltip(Tooltip.create(Component.literal("Land where you're standing")));
			if (com.firecontrol.data.Pairing.NETHER.equals(pairing.dimension)) {
				boolean ow = pairing.hasTarget ? pairing.netherConversion() : false;
				overworldCoords = addRenderableWidget(CycleButton.<Boolean>builder(b -> Component.literal(b ? "Overworld" : "Nether"), ow)
						.withValues(List.of(false, true)).displayOnlyValue()
						.create(left + 52, row, 90, 20, Component.literal("Coordinates"), (b, v) -> apply()));
				overworldCoords.setTooltip(Tooltip.create(Component.literal("Overworld: the coordinates are divided by 8, like the website's Nether Conversion")));
				label(Component.literal("Coords"), left, row + 6, false);
				label(Component.literal("Y can be left empty").withStyle(ChatFormatting.DARK_GRAY), left + 150, row + 6, false);
			} else {
				label(Component.literal("Leave Y empty to land on the ground there").withStyle(ChatFormatting.DARK_GRAY), left, row + 6, false);
			}
		} else {
			label(Component.literal("Payload"), left, row + 6, false);
		}
		if (pearl) {
		} else if (type == CannonType.SFC) {
			sfcSizeButton = addRenderableWidget(CycleButton.<Integer>builder(n -> Component.literal("Size " + n), pairing == null ? 16 : sfcSize(pairing.nukeSize))
					.withValues(List.of(16, 64, 256, 1024)).displayOnlyValue()
					.create(left + 52, row, 90, 20, Component.literal("Payload"), (b, v) -> apply()));
		} else {
			FireMode mode = pairing == null || FireMode.byId(pairing.mode) == null ? FireMode.NUKE : FireMode.byId(pairing.mode);
			modeButton = addRenderableWidget(CycleButton.<FireMode>builder(m -> Component.literal(m == FireMode.NUKE ? "Nuke" : "Stab"), mode)
					.withValues(List.of(FireMode.NUKE, FireMode.STAB)).displayOnlyValue()
					.create(left + 52, row, 64, 20, Component.literal("Mode"), (b, v) -> modeChanged()));
			modeButton.setTooltip(Tooltip.create(Component.literal("Nuke: a crater of the given size. Stab: a shaft down to the given depth.")));
			sizeLabel = label(Component.literal(mode == FireMode.STAB ? "Depth" : "Size"), left + 124, row + 6, false);
			sizeBox = numberBox(left + 160, row, 40, "Size");
			if (type == CannonType.MK6) {
				label(Component.literal("Slot"), left + 212, row + 6, false);
				slotBox = numberBox(left + 238, row, 30, "Magazine slot");
				slotBox.setTooltip(Tooltip.create(Component.literal("Mk6 magazine slot (0 for the first)")));
			}
		}

		// solution and panel state
		solutionLine = label(Component.empty(), width / 2, row + 30, true);
		panelLine = label(Component.empty(), width / 2, row + 44, true);

		// actions
		row += 62;
		enterButton = addRenderableWidget(Button.builder(Component.literal(pearl ? "Load the cannon for me" : "Enter settings on the panel"), b -> enterSettings()).bounds(left, row, W, 20).build());
		row += 24;
		Button pair = addRenderableWidget(Button.builder(Component.literal("Pair here"), b -> pairHere()).bounds(left, row, W / 2 - 2, 20).build());
		pair.setTooltip(Tooltip.create(Component.literal("Press at your panel and at your cannon, in either order. A new panel + cannon combination is saved as a new preset (see More…).")));
		addRenderableWidget(Button.builder(Component.literal("More…"), b -> Compat.setScreen(minecraft, new MoreScreen(this))).bounds(left + W / 2 + 2, row, W / 2 - 2, 20).build());
		row += 24;
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(left, row, W, 20).build());

		fillFields();
		GroundHeight.warmUp();
		boolean paired = pairing != null;
		for (var w : new net.minecraft.client.gui.components.AbstractWidget[] {xBox, yBox, zBox, sizeBox, slotBox, modeButton, sfcSizeButton, overworldCoords, here}) {
			if (w != null) w.active = paired;
		}
		building = false;
		refresh();
	}

	private void fillFields() {
		if (pairing == null) return;
		if (pairing.hasTarget) {
			xBox.setValue(String.valueOf(pairing.targetX));
			if (!pairing.autoY) yBox.setValue(String.valueOf(pairing.targetY));
			zBox.setValue(String.valueOf(pairing.targetZ));
		}
		if (sizeBox != null && modeButton != null) sizeBox.setValue(String.valueOf(modeButton.getValue() == FireMode.STAB ? pairing.stabDepth : pairing.nukeSize));
		if (slotBox != null) slotBox.setValue(String.valueOf(pairing.magazineSlot));
	}

	private static int sfcSize(int n) {
		return Actions.isSfcSize(n) ? n : 16;
	}

	private StringWidget label(Component text, int x, int y, boolean centered) {
		int w = Math.max(1, font.width(text));
		StringWidget s = new StringWidget(centered ? x - w / 2 : x, y, w, 9, text, font);
		addRenderableWidget(s);
		return s;
	}

	private void setCentered(StringWidget s, Component text) {
		int w = Math.max(1, font.width(text));
		s.setMessage(text);
		s.setWidth(w);
		s.setX(width / 2 - w / 2);
	}

	private EditBox numberBox(int x, int y, int w, String name) {
		EditBox box = new EditBox(font, x, y, w, 20, Component.literal(name));
		box.setMaxLength(9);
		box.setResponder(v -> apply());
		addRenderableWidget(box);
		return box;
	}

	private static Integer parse(EditBox box) {
		if (box == null) return null;
		try {
			return Integer.parseInt(box.getValue().trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private void useMyPosition() {
		if (minecraft.player == null) return;
		BlockPos p = minecraft.player.blockPosition();
		building = true;
		xBox.setValue(String.valueOf(p.getX()));
		yBox.setValue(String.valueOf(p.getY()));
		zBox.setValue(String.valueOf(p.getZ()));
		building = false;
		apply();
	}

	private void modeChanged() {
		if (pairing == null) return;
		boolean stab = modeButton.getValue() == FireMode.STAB;
		building = true;
		sizeBox.setValue(String.valueOf(stab ? pairing.stabDepth : pairing.nukeSize));
		building = false;
		if (sizeLabel != null) sizeLabel.setMessage(Component.literal(stab ? "Depth" : "Size"));
		apply();
	}

	private void apply() {
		if (building || pairing == null) return;
		inputError = null;
		waitingGround = null;
		if (pairing.isPearl()) {
			applyPearl();
			refresh();
			return;
		}
		boolean autoY = yBox.getValue().isBlank();
		Integer x = parse(xBox), y = autoY ? null : parse(yBox), z = parse(zBox);
		boolean sfc = pairing.type() == CannonType.SFC;
		FireMode mode = sfc ? FireMode.NUKE : modeButton.getValue();
		Integer size = sfc ? sfcSizeButton.getValue() : parse(sizeBox);
		Integer slot = slotBox == null ? 0 : parse(slotBox);
		if (x == null || z == null || (!autoY && y == null)) {
			if (!xBox.getValue().isBlank() || !yBox.getValue().isBlank() || !zBox.getValue().isBlank()) inputError = "Type whole numbers for X and Z (and Y, or leave it empty).";
		} else if (size == null || size < 0 || size > 1024) {
			inputError = "Type a payload " + (mode == FireMode.STAB ? "depth." : "size.");
		} else if (slot == null || slot < 0 || slot > 64) {
			inputError = "Type a magazine slot from 0.";
		} else {
			if (autoY) {
				GroundHeight.Answer a = GroundHeight.at(pairing.dimension, x, z);
				if (a.status() == GroundHeight.Status.WORKING) waitingGround = new int[] {x, z};
				else if (!a.ready()) inputError = "No ground height here (" + a.why() + "). Type a Y.";
				else y = a.y();
			}
			if (y != null && changed(x, y, z, mode, size, slot)) Actions.setTarget(pairing, new BlockPos(x, y, z), mode, size, slot);
			if (y != null) pairing.autoY = autoY;
		}
		refresh();
	}

	private void applyPearl() {
		boolean autoY = yBox.getValue().isBlank();
		Integer x = parse(xBox), y = autoY ? null : parse(yBox), z = parse(zBox);
		if (x == null || z == null || (!autoY && y == null)) {
			if (!xBox.getValue().isBlank() || !yBox.getValue().isBlank() || !zBox.getValue().isBlank()) inputError = "Type whole numbers for X and Z (and Y if you like).";
			return;
		}
		String dim = overworldCoords == null ? pairing.dimension : overworldCoords.getValue() ? "minecraft:overworld" : pairing.dimension;
		if (autoY) {
			String ground = pairing.groundDimension(dim);
			GroundHeight.Answer a = ground == null || Pairing.NETHER.equals(ground) ? null : GroundHeight.at(ground, x, z);
			if (a != null && a.status() == GroundHeight.Status.WORKING) {
				waitingGround = new int[] {x, z};
				return;
			}
			y = a != null && a.ready() ? a.y() : pairing.originY;
		}
		if (!pairing.hasTarget || pairing.targetX != x || pairing.targetY != y || pairing.targetZ != z || !dim.equals(pairing.targetDimension)) {
			Actions.setPearlTarget(pairing, new BlockPos(x, y, z), dim);
		}
		pairing.autoY = autoY;
	}

	private boolean changed(int x, int y, int z, FireMode mode, int size, int slot) {
		Pairing p = pairing;
		int current = mode == FireMode.STAB ? p.stabDepth : p.nukeSize;
		return !p.hasTarget || p.targetX != x || p.targetY != y || p.targetZ != z || !mode.id.equals(p.mode) || current != size
				|| (p.type() == CannonType.MK6 && p.magazineSlot != slot);
	}

	private void enterSettings() {
		onClose();
		if (pairing != null && pairing.isPearl()) PearlLoader.start();
		else AutoInput.start();
	}

	private void pairHere() {
		// pairing reports in chat, close the menu so it can be seen
		onClose();
		FireControlCommands.pair(null, null);
	}

	private MutableComponent pairingLine() {
		if (pairing != null) {
			BlockPos o = pairing.origin();
			MutableComponent c = Component.literal("Preset '" + pairing.name + "' · " + pairing.type().displayName + " · " + (pairing.isPearl() ? "origin " : "cannon ") + o.getX() + " " + o.getY() + " " + o.getZ()).withStyle(ChatFormatting.GRAY);
			if (pairing.type().hasPasscode()) {
				c.append(Component.literal(" · passcode " + pairing.passcode + (pairing.passcodeKnown() ? "" : " (not read yet)"))
						.withStyle(pairing.passcodeKnown() ? ChatFormatting.GRAY : ChatFormatting.YELLOW));
			}
			return c;
		}
		Pairing half = Store.pending();
		if (half != null && half.hasCannon()) return Component.literal("Cannon saved. Now press Pair here at your control panel.").withStyle(ChatFormatting.YELLOW);
		if (half != null) return Component.literal("Panel saved. Now press Pair here at your cannon.").withStyle(ChatFormatting.YELLOW);
		return Component.literal("Not paired. Stand at your panel or cannon and press Pair here.").withStyle(ChatFormatting.YELLOW);
	}

	private void refresh() {
		if (solutionLine == null) return;
		if (pairing == null) {
			setCentered(solutionLine, Component.empty());
			setCentered(panelLine, Component.empty());
			enterButton.active = false;
			return;
		}
		if (FireControl.resolveActive() != pairing) {
			rebuildWidgets();
			return;
		}
		refreshY();
		if (pairing.isPearl()) {
			refreshPearl();
			return;
		}
		FireCalculator.Solution s = pairing.solve();
		MutableComponent sol;
		if (inputError != null) {
			sol = Component.literal(inputError).withStyle(ChatFormatting.RED);
		} else if (waitingGround != null) {
			sol = workingLine();
		} else if (s == null) {
			sol = Component.literal("Type the target's X and Z (and Y if you know it), or press My position.").withStyle(ChatFormatting.GRAY);
		} else if (!s.isValid()) {
			sol = Component.literal("✖ " + s.errors.get(0)).withStyle(ChatFormatting.RED);
		} else {
			sol = Component.literal(String.format("Lands at %.0f %.0f %.0f · ETA ", s.landX, s.landY, s.landZ)).withStyle(ChatFormatting.GRAY)
					.append(Component.literal(FireCalculator.formatTicks(s.totalTicks)).withStyle(ChatFormatting.WHITE));
			if (!s.warnings.isEmpty()) sol.append(Component.literal(" ⚠").withStyle(ChatFormatting.YELLOW));
		}
		setCentered(solutionLine, sol);
		String tip = s != null && s.isValid() ? String.join("\n", s.warnings) : "";
		if (!tip.equals(solutionTip)) {
			solutionTip = tip;
			solutionLine.setTooltip(tip.isEmpty() ? null : Tooltip.create(Component.literal(tip)));
		}

		boolean valid = inputError == null && waitingGround == null && s != null && s.isValid();
		boolean atPanel = pairing.panel != null && minecraft.player != null && pairing.panel.operatorArea().contains(minecraft.player.position());
		int wrong = FireControl.active() == pairing ? FireControl.wrongCount() : -1;
		MutableComponent pl;
		long now = minecraft.level == null ? 0 : minecraft.level.getGameTime();
		if (Eta.running(pairing, now) && Eta.remaining(pairing, now) > 0) {
			pl = Component.literal("Fired · impact in " + FireCalculator.formatTicks(Eta.remaining(pairing, now))).withStyle(ChatFormatting.GOLD);
		} else if (pairing.fired) {
			pl = Component.literal("✔ Fired. Change the target for the next shot.").withStyle(ChatFormatting.GREEN);
		} else if (!valid) {
			pl = Component.empty();
		} else if (!atPanel) {
			pl = Component.literal("Go to your panel to enter these settings.").withStyle(ChatFormatting.GRAY);
		} else if (wrong == 0) {
			pl = Component.literal("✔ Panel ready: press INPUT SETTINGS").withStyle(ChatFormatting.GREEN);
		} else if (wrong > 0) {
			pl = Component.literal(wrong + " button" + (wrong == 1 ? "" : "s") + " to press on the panel").withStyle(ChatFormatting.YELLOW);
		} else {
			pl = Component.empty();
		}
		setCentered(panelLine, pl);
		enterLabel(AutoInput.paused() ? "Resume entering settings" : "Enter settings on the panel");
		enterButton.active = valid && atPanel && wrong != 0 && !pairing.fired && !AutoInput.running();
		String why = enterButton.active ? "" : !valid ? "Set a valid target first" : !atPanel ? "Stand at your control panel"
				: pairing.fired ? "Already fired; change the target first" : "The panel already matches";
		if (!why.equals(enterTip)) {
			enterTip = why;
			enterButton.setTooltip(why.isEmpty() ? null : Tooltip.create(Component.literal(why)));
		}
	}

	private void refreshPearl() {
		PearlCalculator.Solution s = pairing.solvePearl();
		MutableComponent sol;
		if (inputError != null) {
			sol = Component.literal(inputError).withStyle(ChatFormatting.RED);
		} else if (waitingGround != null) {
			sol = workingLine();
		} else if (s == null) {
			sol = Component.literal("Type the target's X and Z, or press My position.").withStyle(ChatFormatting.GRAY);
		} else if (s.total() == 0) {
			sol = Component.literal("That's the cannon itself.").withStyle(ChatFormatting.YELLOW);
		} else {
			double flight = PearlControl.IN_CANNON_TICKS + s.flightTicks;
			sol = Component.literal(PearlControl.plan(pairing, s)).withStyle(ChatFormatting.WHITE)
					.append(Component.literal(String.format(" · lands %.0f %.0f%s (±%.0f) · flight %s", s.landX, s.landZ,
							pairing.netherConversion() ? " (nether)" : "", s.error, FireCalculator.formatTicks(flight))).withStyle(ChatFormatting.GRAY));
		}
		setCentered(solutionLine, sol);
		boolean valid = inputError == null && waitingGround == null && s != null && s.total() > 0;
		boolean atCannon = minecraft.player != null && pairing.pearl.operatorArea().contains(minecraft.player.position());
		boolean ready = FireControl.active() == pairing && PearlControl.ready();
		long now = minecraft.level == null ? 0 : minecraft.level.getGameTime();
		MutableComponent pl;
		if (Eta.running(pairing, now) && Eta.remaining(pairing, now) > 0) {
			pl = Component.literal("Pearl on its way · lands in " + FireCalculator.formatTicks(Eta.remaining(pairing, now))).withStyle(ChatFormatting.AQUA);
		} else if (pairing.fired) {
			pl = Component.literal("✔ Pearl sent. Change the target for the next one.").withStyle(ChatFormatting.GREEN);
		} else if (!valid) {
			pl = Component.empty();
		} else if (PearlLoader.running()) {
			pl = Component.literal(PearlLoader.status()).withStyle(ChatFormatting.YELLOW);
		} else if (PearlLoader.paused()) {
			pl = Component.literal("Loading paused").withStyle(ChatFormatting.YELLOW);
		} else if (ready) {
			pl = Component.literal("✔ Ready: step into the hole and throw your pearl straight up").withStyle(ChatFormatting.AQUA);
		} else if (!atCannon) {
			pl = Component.literal("Go to your pearl cannon to load it.").withStyle(ChatFormatting.GRAY);
		} else {
			pl = Component.literal("Not loaded yet").withStyle(ChatFormatting.YELLOW);
		}
		setCentered(panelLine, pl);
		enterLabel(PearlLoader.paused() ? "Resume loading" : "Load the cannon for me");
		enterButton.active = valid && atCannon && !ready && !pairing.fired && !PearlLoader.running();
		String why = enterButton.active ? "" : !valid ? "Set a target first" : !atCannon ? "Stand at the pearl cannon's buttons"
				: pairing.fired ? "Pearl already sent; change the target first" : ready ? "Already loaded" : "Loading…";
		if (!why.equals(enterTip)) {
			enterTip = why;
			enterButton.setTooltip(why.isEmpty() ? null : Tooltip.create(Component.literal(why)));
		}
	}

	private void enterLabel(String text) {
		if (!enterButton.getMessage().getString().equals(text)) enterButton.setMessage(Component.literal(text));
	}

	private MutableComponent workingLine() {
		return Component.literal("Working out the ground height at " + waitingGround[0] + " " + waitingGround[1] + "…").withStyle(ChatFormatting.GRAY);
	}

	private void refreshY() {
		boolean empty = yBox.getValue().isBlank();
		String hint = !empty ? "" : waitingGround != null ? "auto …" : pairing.hasTarget && pairing.autoY ? "auto " + pairing.targetY : "auto";
		yBox.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));

		String dim = pairing.isPearl() ? pairing.groundDimension(pairing.hasTarget ? pairing.targetDimension : pairing.dimension) : pairing.dimension;
		boolean floorOnly = pairing.isPearl() && (dim == null || Pairing.NETHER.equals(dim));
		String tip;
		if (floorOnly) {
			tip = "Leave Y empty to land at the cannon's floor height.";
		} else {
			tip = "Leave Y empty for the ground there: the top block if that spot is loaded, otherwise worked out from the world's terrain"
					+ " (on a server, from the seed set in More…). A worked-out height is the bare ground, without trees or builds."
					+ (pairing.isPearl() ? " If there's none, the pearl lands at the cannon's floor height." : "");
			if (empty && pairing.hasTarget && pairing.autoY && waitingGround == null) {
				GroundHeight.Answer a = GroundHeight.at(dim, pairing.targetX, pairing.targetZ);
				if (a.ready() && a.y() == pairing.targetY) tip += "\n\nNow: Y " + a.y() + ", " + a.source().label + ".";
				else if (a.status() == GroundHeight.Status.NONE && pairing.isPearl()) tip += "\n\nNow: the cannon's floor height (" + a.why() + ").";
			}
			Store.Seed seed = Store.seed();
			if (seed != null && Boolean.FALSE.equals(seed.matched) && Minecraft.getInstance().getSingleplayerServer() == null) {
				tip += "\n\n⚠ The seed in More… didn't fit the ground when it was checked.";
			}
		}
		if (!tip.equals(yTip)) {
			yTip = tip;
			yBox.setTooltip(Tooltip.create(Component.literal(tip)));
		}
	}

	@Override
	public void tick() {
		if (waitingGround != null) apply();
		else refresh();
	}

	@Override
	public void removed() {
		Store.save();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
