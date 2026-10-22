package com.firecontrol.gui;

import com.firecontrol.Actions;
import com.firecontrol.Eta;
import com.firecontrol.FireControl;
import com.firecontrol.calc.Passcodes;
import com.firecontrol.compat.Compat;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import com.firecontrol.world.GroundHeight;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class MoreScreen extends Screen {
	private static final int W = FireControlScreen.W;
	private final Screen parent;
	private Pairing pairing;
	private CycleButton<Pairing> presetButton;
	private EditBox passcodeBox;
	private StringWidget feedback;
	private boolean building, confirmDelete;
	private int seedTypedTicks = -1;

	public MoreScreen(Screen parent) {
		super(Component.literal("FireControl · More"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		building = true;
		List<Pairing> presets = FireControl.pairingsHere();
		if (pairing == null || !presets.contains(pairing)) pairing = FireControl.resolveActive();
		int left = width / 2 - W / 2;
		int half = W / 2 - 2;
		int y = Math.max(6, height / 2 - 116);

		centered(Component.literal("FireControl · More").withStyle(ChatFormatting.BOLD), y);
		int row = y + 18;

		// presets
		if (pairing == null) {
			label(Component.literal("No presets yet. Pair at your panel and your cannon to add one.").withStyle(ChatFormatting.GRAY), left, row + 6);
			row += 24;
		} else {
			label(Component.literal("Preset"), left, row + 6);
			presetButton = addRenderableWidget(CycleButton.<Pairing>builder(MoreScreen::presetLabel, pairing)
					.withValues(presets).displayOnlyValue()
					.create(left + 48, row, W - 48, 20, Component.literal("Preset"), (b, p) -> {
						Actions.select(p);
						pairing = p;
						confirmDelete = false;
						rebuildWidgets();
					}));
			presetButton.setTooltip(Tooltip.create(Component.literal(
					"One preset per panel + cannon. Pairing a new combination adds one. At a panel with several presets, the one picked here is used.")));
			row += 24;

			label(Component.literal("Name"), left, row + 6);
			EditBox name = new EditBox(font, left + 48, row, 140, 20, Component.literal("Name"));
			name.setMaxLength(24);
			name.setValue(pairing.name);
			name.setResponder(this::renamed);
			addRenderableWidget(name);
			Button delete = addRenderableWidget(Button.builder(deleteLabel(), b -> {
				if (!confirmDelete) {
					confirmDelete = true;
					b.setMessage(deleteLabel());
					return;
				}
				Actions.deletePreset(pairing);
				pairing = null;
				confirmDelete = false;
				rebuildWidgets();
				say("Preset deleted.", ChatFormatting.GREEN);
			}).bounds(left + 192, row, W - 192, 20).build());
			delete.setTooltip(Tooltip.create(Component.literal("Removes this preset and its target. Pair again to bring it back.")));
			row += 28;
		}

		if (pairing != null && pairing.type().hasPasscode()) {
			label(Component.literal("Passcode"), left, row + 6);
			passcodeBox = new EditBox(font, left + 56, row, 50, 20, Component.literal("Passcode"));
			passcodeBox.setMaxLength(4);
			passcodeBox.setValue(String.valueOf(pairing.passcode));
			passcodeBox.setResponder(this::passcodeTyped);
			addRenderableWidget(passcodeBox);
			Button read = addRenderableWidget(Button.builder(Component.literal("Read from cannon"), b -> readPasscode()).bounds(left + 112, row, W - 112, 20).build());
			read.setTooltip(Tooltip.create(Component.literal("Reads the composter key. The cannon has to be loaded (within render distance).")));
			row += 24;
		}

		// world seed
		label(Component.literal("World seed"), left, row + 6);
		EditBox seedBox = new EditBox(font, left + 60, row, W - 60, 20, Component.literal("World seed"));
		seedBox.setMaxLength(64);
		MinecraftServer local = minecraft.getSingleplayerServer();
		if (local != null) {
			seedBox.setValue(String.valueOf(local.overworld().getSeed()));
			seedBox.setEditable(false);
			seedBox.setTooltip(Tooltip.create(Component.literal("Singleplayer: a target without a Y uses this world's own terrain, so there's nothing to set here.")));
		} else {
			Store.Seed seed = Store.seed();
			seedBox.setValue(seed == null ? "" : seed.text);
			seedBox.setHint(Component.literal("lets you leave a target's Y empty").withStyle(ChatFormatting.DARK_GRAY));
			seedBox.setTooltip(Tooltip.create(Component.literal("The server's world seed (/seed shows it if you're allowed to use it, otherwise ask an admin)."
					+ " One seed covers the Overworld, the Nether and the End. With it, FireControl works out the ground height at any X Z,"
					+ " so a target needs no Y. It's checked against the terrain loaded around you, in whichever of them you're in.")));
			seedBox.setResponder(this::seedTyped);
			// a seed confirmed in the Nether or the End doesn't tell which overworld terrain the server uses
			boolean overworld = minecraft.level != null && "minecraft:overworld".equals(FireControl.dimensionId(minecraft.level));
			if (seed != null && (seed.matched == null || overworld && Boolean.TRUE.equals(seed.matched) && seed.terrain == null)) seedTypedTicks = 19;
		}
		addRenderableWidget(seedBox);
		row += 24;

		boolean rescan = pairing != null && (pairing.panel != null || pairing.isPearl());
		if (rescan && pairing.isPearl()) {
			Button b = addRenderableWidget(Button.builder(Component.literal("Rescan cannon"), x -> {
				if (Actions.rescanPearl(pairing)) say("Cannon rescanned.", ChatFormatting.GREEN);
				else say("No pearl cannon within " + com.firecontrol.world.PearlScanner.RADIUS + " blocks.", ChatFormatting.RED);
			}).bounds(left, row, half, 20).build());
			b.setTooltip(Tooltip.create(Component.literal("Re-detects the pearl cannon after you rebuilt or changed it")));
		} else if (rescan) {
			Button b = addRenderableWidget(Button.builder(Component.literal("Rescan panel"), x -> {
				if (Actions.rescanPanel(pairing)) say("Panel rescanned.", ChatFormatting.GREEN);
				else say("No matching panel within 32 blocks.", ChatFormatting.RED);
			}).bounds(left, row, half, 20).build());
			b.setTooltip(Tooltip.create(Component.literal("Re-detects the panel after you rebuilt or changed it")));
		}
		addRenderableWidget(CycleButton.onOffBuilder(FireControl.overlayEnabled)
				.create(rescan ? left + half + 4 : left, row, rescan ? half : W, 20, Component.literal("Panel overlay"), (b, on) -> FireControl.overlayEnabled = on));
		row += 24;

		if (pairing != null) {
			boolean pearl = pairing.isPearl();
			Button start = addRenderableWidget(Button.builder(Component.literal("Start ETA now"), b -> {
				boolean ok = minecraft.level != null && (pearl ? com.firecontrol.PearlControl.startCountdown(pairing, minecraft.level.getGameTime())
						: FireControl.onInputSettingsPressed(minecraft.level, null));
				if (ok) say("Countdown started.", ChatFormatting.GREEN);
				else say(pearl ? "No countdown: stand where the cannon is loaded." : "No countdown: set a target or stand where the panel is loaded.", ChatFormatting.RED);
			}).bounds(left, row, half, 20).build());
			start.setTooltip(Tooltip.create(Component.literal(pearl ? "For when the mod missed your pearl going up the cannon" : "For when the mod missed your Input Settings press")));
			addRenderableWidget(Button.builder(Component.literal("Clear ETA"), b -> {
				Eta.stop(pairing);
				say("Countdown cleared.", ChatFormatting.GREEN);
			}).bounds(left + half + 4, row, half, 20).build());
			row += 24;
		}

		CycleButton<Integer> speed = addRenderableWidget(CycleButton.<Integer>builder(t -> Component.literal(t == 1 ? "1 tick (fastest)" : t + " ticks"), Store.data().pressInterval)
				.withValues(List.of(1, 2, 3, 4, 5, 8, 10))
				.create(left, row, W, 20, Component.literal("Button press speed"), (b, t) -> {
					Store.data().pressInterval = t;
					Store.save();
				}));
		speed.setTooltip(Tooltip.create(Component.literal("Time between button presses when entering settings. Raise it if a server's anti-cheat complains.")));
		row += 24;

		Pairing pendingHalf = Store.pending();
		if (pendingHalf != null) {
			addRenderableWidget(Button.builder(Component.literal("Forget the saved " + (pendingHalf.hasCannon() ? "cannon" : "panel") + " (half-finished pairing)"), b -> {
				Store.setPending(null);
				rebuildWidgets();
				say("Forgot it.", ChatFormatting.GREEN);
			}).bounds(left, row, W, 20).build());
			row += 24;
		}

		feedback = label(Component.empty(), left, row + 4);
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(left, row + 18, W, 20).build());
		building = false;
	}

	private static Component presetLabel(Pairing p) {
		BlockPos o = p.origin();
		return Component.literal(p.name + " · " + p.type().displayName + " @ " + o.getX() + " " + o.getY() + " " + o.getZ());
	}

	private Component deleteLabel() {
		return Component.literal(confirmDelete ? "Click again to delete" : "Delete preset").withStyle(ChatFormatting.RED);
	}

	private void renamed(String v) {
		if (building || pairing == null) return;
		String error = Actions.rename(pairing, v);
		if (error != null) {
			say(error, ChatFormatting.RED);
			return;
		}
		say("", ChatFormatting.GRAY);
		presetButton.setValue(pairing);
	}

	private void passcodeTyped(String v) {
		if (building || pairing == null) return;
		int code;
		try {
			code = Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			say("Type a number from 0 to 1023.", ChatFormatting.RED);
			return;
		}
		if (code < 0 || code > 1023) {
			say("Type a number from 0 to 1023.", ChatFormatting.RED);
			return;
		}
		if (code == pairing.passcode && "manual".equals(pairing.passcodeSource)) return;
		Actions.setPasscode(pairing, code);
		if (Passcodes.isValid(code)) say("Passcode set to " + code + ".", ChatFormatting.GREEN);
		else say("Passcode " + code + " isn't in CubicMetre's good-code list; it may misfire.", ChatFormatting.YELLOW);
	}

	private void readPasscode() {
		if (!Actions.readPasscode(pairing)) {
			say("Couldn't read it: the cannon isn't loaded. It's read by itself when you're near it.", ChatFormatting.RED);
			return;
		}
		building = true;
		passcodeBox.setValue(String.valueOf(pairing.passcode));
		building = false;
		say("Read passcode " + pairing.passcode + " from the cannon.", ChatFormatting.GREEN);
	}

	private void seedTyped(String v) {
		if (building) return;
		Store.setSeed(v);
		GroundHeight.forget();
		seedTypedTicks = v.isBlank() ? -1 : 0;
		say(v.isBlank() ? "Seed cleared." : "", ChatFormatting.GRAY);
	}

	@Override
	public void tick() {
		if (seedTypedTicks >= 0 && ++seedTypedTicks >= 20) {
			seedTypedTicks = -1;
			checkSeed();
		}
	}

	private void checkSeed() {
		Store.Seed seed = Store.seed();
		if (seed == null) return;
		String text = seed.text;
		say("Checking the seed against the ground…", ChatFormatting.GRAY);
		GroundHeight.checkSeed(text, c -> {
			Store.Seed now = Store.seed();
			if (now == null || !text.equals(now.text)) return;
			if (c.match() != null) now.matched = c.match();
			if (Boolean.TRUE.equals(c.match()) && c.preset() != null) now.terrain = c.preset();
			Store.save();
			GroundHeight.forget();
			say(c.message(), Boolean.TRUE.equals(c.match()) ? ChatFormatting.GREEN : c.match() == null ? ChatFormatting.YELLOW : ChatFormatting.RED);
		});
	}

	private void say(String text, ChatFormatting color) {
		Component c = Component.literal(text).withStyle(color);
		feedback.setMessage(c);
		feedback.setWidth(Math.max(1, font.width(c)));
	}

	private StringWidget label(Component text, int x, int y) {
		StringWidget s = new StringWidget(x, y, Math.max(1, font.width(text)), 9, text, font);
		addRenderableWidget(s);
		return s;
	}

	private void centered(Component text, int y) {
		int w = font.width(text);
		addRenderableWidget(new StringWidget(width / 2 - w / 2, y, w, 9, text, font));
	}

	@Override
	public void onClose() {
		Compat.setScreen(minecraft, parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
