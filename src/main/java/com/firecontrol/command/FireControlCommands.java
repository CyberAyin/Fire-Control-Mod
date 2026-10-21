package com.firecontrol.command;

import static com.firecontrol.compat.Compat.argument;
import static com.firecontrol.compat.Compat.literal;

import com.firecontrol.Actions;
import com.firecontrol.AutoInput;
import com.firecontrol.FireControl;
import com.firecontrol.Msg;
import com.firecontrol.PearlControl;
import com.firecontrol.PearlLoader;
import com.firecontrol.calc.CannonType;
import com.firecontrol.calc.FireCalculator;
import com.firecontrol.calc.FireMode;
import com.firecontrol.calc.Passcodes;
import com.firecontrol.calc.PearlCalculator;
import com.firecontrol.data.Pairing;
import com.firecontrol.data.Store;
import com.firecontrol.gui.FireControlScreen;
import com.firecontrol.world.CannonScanner;
import com.firecontrol.world.GroundHeight;
import com.firecontrol.world.PanelLayout;
import com.firecontrol.world.PanelScanner;
import com.firecontrol.world.PearlLayout;
import com.firecontrol.world.PearlScanner;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;

public final class FireControlCommands {
	private FireControlCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> d) {
		LiteralArgumentBuilder<FabricClientCommandSource> root = literal("firecontrol").executes(ctx -> {
			FireControlScreen.openNextTick();
			return 1;
		});

		root.then(literal("pair")
				.executes(ctx -> pair(null, null))
				.then(literal("cancel").executes(ctx -> pairCancel()))
				.then(literal("at").then(coords("x", "y", "z", argument("type", StringArgumentType.word())
						.suggests((ctx, b) -> SharedSuggestionProvider.suggest(List.of("mk6", "ms", "sfc"), b))
						.executes(ctx -> pair(pos(ctx), StringArgumentType.getString(ctx, "type")))))));

		RequiredArgumentBuilder<FabricClientCommandSource, String> z = argument("z", CoordArgument.coord());
		payload(z, (ctx, mode, size, slot) -> target(pos(ctx), mode, size, slot, false, null));
		RequiredArgumentBuilder<FabricClientCommandSource, String> second = argument("y", CoordArgument.coord());
		payload(second, FireControlCommands::targetXZ);
		root.then(literal("target")
				.executes(ctx -> target(ctx.getSource().getPlayer().blockPosition(), null, null, null, true, null))
				.then(argument("x", CoordArgument.coord()).then(second.then(z))));

		// loads a pearl cannon, or enters the settings at a panel
		root.then(literal("InputSettings").executes(ctx -> {
			Pairing p = FireControl.resolveActive();
			if (p != null && p.isPearl()) PearlLoader.start();
			else AutoInput.start();
			return 1;
		}));

		d.register(root);
	}

	private interface TargetCommand {
		int run(CommandContext<FabricClientCommandSource> ctx, FireMode mode, Integer size, Integer slot);
	}

	// [nuke|stab [size [slot n]]]
	private static void payload(RequiredArgumentBuilder<FabricClientCommandSource, String> node, TargetCommand run) {
		node.executes(ctx -> run.run(ctx, null, null, null));
		for (FireMode mode : FireMode.values()) {
			node.then(literal(mode.id)
					.executes(ctx -> run.run(ctx, mode, null, null))
					.then(argument("size", IntegerArgumentType.integer(0, 1024))
							.executes(ctx -> run.run(ctx, mode, IntegerArgumentType.getInteger(ctx, "size"), null))
							.then(literal("slot").then(argument("slot", IntegerArgumentType.integer(0, 64))
									.executes(ctx -> run.run(ctx, mode, IntegerArgumentType.getInteger(ctx, "size"), IntegerArgumentType.getInteger(ctx, "slot")))))));
		}
	}

	private static RequiredArgumentBuilder<FabricClientCommandSource, String> coords(String x, String y, String z, RequiredArgumentBuilder<FabricClientCommandSource, String> tail) {
		return argument(x, CoordArgument.coord()).then(argument(y, CoordArgument.coord()).then(argument(z, CoordArgument.coord()).then(tail)));
	}

	private static BlockPos pos(CommandContext<FabricClientCommandSource> ctx) {
		LocalPlayer p = ctx.getSource().getPlayer();
		return new BlockPos(
				CoordArgument.resolve(CoordArgument.get(ctx, "x"), p.getX()),
				CoordArgument.resolve(CoordArgument.get(ctx, "y"), p.getY()),
				CoordArgument.resolve(CoordArgument.get(ctx, "z"), p.getZ()));
	}

	// Two steps in either order, the first half is kept in Store.pending until the other one is done.
	public static int pair(BlockPos typedOrigin, String typedType) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) return 0;
		String dim = FireControl.dimensionId(level);

		Pairing previous = Store.pending();
		Pairing pending = previous != null && dim.equals(previous.dimension) ? previous : null;
		Pairing pendingCannon = pending != null && pending.hasCannon() ? pending : null;
		PanelLayout pendingPanel = pending != null ? pending.panel : null;

		if (typedOrigin == null) {
			PearlScanner.Result pearl = PearlScanner.scan(level, player.blockPosition()).stream()
					.filter(r -> r.layout().operatorArea().inflate(8).contains(player.position())).findFirst().orElse(null);
			if (pearl != null) return pairPearl(pearl, dim);
		}

		Pairing cannon = null;
		String how = null;
		if (typedOrigin != null) {
			CannonType type = CannonType.byId(typedType);
			if (type == null) {
				Msg.error("Unknown cannon type '" + typedType + "'. Use mk6, ms or sfc.");
				return 0;
			}
			if (type.isPearl()) {
				Msg.error("Pearl cannons are paired by standing at their buttons and running /firecontrol pair.");
				return 0;
			}
			cannon = typedCannon(level, typedOrigin, type);
			if (cannon == null) return 0;
			how = "typed by you";
		}

		CannonType want = cannon != null ? cannon.type() : pendingCannon != null ? pendingCannon.type() : null;
		List<PanelLayout> panels = PanelScanner.scan(level, player);
		PanelLayout panel = panels.stream().filter(l -> want == null || l.matchesType(want)).findFirst().orElse(null);
		if (panel == null && !panels.isEmpty()) {
			Msg.error("The panel here is " + panelKind(panels.get(0)) + ", but the cannon is an " + want.displayName + "."
					+ (cannon == null ? " Run /firecontrol pair cancel to forget the saved cannon." : ""));
			return 0;
		}

		if (cannon == null && panel != null && pendingCannon != null) {
			cannon = pendingCannon;
			how = "saved earlier";
		}
		PanelLayout panelSoFar = panel != null ? panel : pendingPanel;
		List<CannonScanner.Result> alsoFound = List.of();
		if (cannon == null && (panelSoFar == null || panelSoFar.isGrid())) {
			BlockPos around = panel != null ? BlockPos.containing(panel.center()) : player.blockPosition();
			int radius = Math.max(64, mc.options.getEffectiveRenderDistance() * 16);
			List<CannonScanner.Result> found = CannonScanner.scan(level, around, radius);
			if (!found.isEmpty()) {
				CannonType hint = panelSoFar != null && panelSoFar.signHint != null ? CannonType.byId(panelSoFar.signHint) : null;
				CannonScanner.Result best = pickCannon(found, hint, around);
				cannon = new Pairing();
				CannonScanner.PasscodeRead read = CannonScanner.readPasscode(level, best.origin());
				cannon.setCannon(best.type(), best.origin(), read != null ? read.passcode() : Passcodes.DEFAULT, read != null ? "cannon" : "default");
				how = Math.round(best.confidence() * 100) + "% block match";
				alsoFound = found.stream().filter(r -> r != best).toList();
			}
		}

		if (panel == null && cannon != null && pendingPanel != null) {
			if (!pendingPanel.matchesType(cannon.type())) {
				Msg.error("This is an " + cannon.type().displayName + ", but the panel you saved is " + panelKind(pendingPanel)
						+ ". Run /firecontrol pair cancel to forget that panel.");
				return 0;
			}
			panel = pendingPanel;
		}

		if (panel == null && cannon == null) {
			if (pendingPanel != null && !pendingPanel.isGrid()) {
				Msg.error("Your SFC panel is saved. SFC cannons can't be found automatically, so type the origin (works from anywhere):");
				Msg.send(Msg.text("/firecontrol pair at <x> <y> <z> sfc", ChatFormatting.AQUA));
			} else if (pendingPanel != null) {
				Msg.error("Your panel is saved, but there's no OSC Mk6 / MS cannon loaded around you.");
				Msg.info("Go to the cannon (it only has to be in render distance) and run /firecontrol pair, or type its origin from anywhere: /firecontrol pair at <x> <y> <z> <mk6|ms|sfc>");
			} else if (pendingCannon != null) {
				Msg.error("No control panel within " + PanelScanner.RADIUS + " blocks.");
				Msg.send(Msg.text("Your cannon is saved (", ChatFormatting.GRAY).append(FireControl.describePending(pendingCannon))
						.append(Msg.text("). Stand at your control panel and run /firecontrol pair.", ChatFormatting.GRAY)));
			} else {
				Msg.error("No control panel within " + PanelScanner.RADIUS + " blocks and no OSC Mk6 / MS cannon in render distance.");
				Msg.info("Run /firecontrol pair at your panel and at your cannon, in either order. Or type the cannon's origin: /firecontrol pair at <x> <y> <z> <mk6|ms|sfc>");
			}
			return 0;
		}
		if (panel == null) return saveCannon(cannon, how, dim, previous, alsoFound);
		if (cannon == null) return savePanel(panel, dim, previous);

		boolean usedPending = pending != null && (cannon == pendingCannon || panel == pendingPanel);
		int before = Store.pairings().size();
		Pairing p = finish(cannon, panel, dim);
		if (usedPending) Store.setPending(null);
		printPaired(p, Store.pairings().size() > before, how, alsoFound);
		return 1;
	}

	private static int pairPearl(PearlScanner.Result found, String dim) {
		PearlLayout l = found.layout();
		List<Pairing> all = Store.pairings();
		Pairing p = null;
		for (Pairing q : all) {
			if (dim.equals(q.dimension) && q.isPearl() && q.origin().equals(l.origin())) p = q;
		}
		boolean added = p == null;
		if (added) {
			p = new Pairing();
			p.name = uniqueName("pearl");
			p.dimension = dim;
			all.add(p);
		}
		p.cannonType = CannonType.PEARL.id;
		p.originX = l.x;
		p.originY = l.y;
		p.originZ = l.z;
		p.passcodeSource = "default";
		p.pearl = l;
		Actions.select(p);
		String turned = l.rotation == 0 ? "" : " · turned " + (l.rotation * 90) + "°";
		Msg.send(Msg.text("✔ " + (added ? "Saved preset" : "Updated preset") + " '" + p.name + "' · Pearl Cannon", ChatFormatting.GREEN)
				.append(Msg.text(" · origin " + l.x + " " + l.y + " " + l.z + (l.extendedInput ? " · ExtendedInput" : "") + turned, ChatFormatting.GRAY))
				.append(Msg.text(" (" + Math.round(found.confidence() * 100) + "% block match)", ChatFormatting.DARK_GRAY)));
		if (l.rotation != 0) {
			Msg.warn("Heads up: this cannon is built turned " + (l.rotation * 90) + "° from Danda's schematic. Loading works, but the design is only known to launch"
					+ " when it faces the schematic's way (buttons on its north side): in a test, a copy turned 90° dropped the pearl inside.");
		}
		if (p.hasTarget) {
			Msg.info("Kept the previous target " + p.targetX + " " + p.targetY + " " + p.targetZ + ".");
		} else {
			Msg.send(Msg.text("Next: ", ChatFormatting.GRAY).append(Msg.text("/firecontrol target <x> <z>", ChatFormatting.AQUA))
					.append(Msg.text(" (or stand on the spot and run /firecontrol target), then load it from the menu.", ChatFormatting.GRAY)));
		}
		return 1;
	}

	private static Pairing typedCannon(ClientLevel level, BlockPos origin, CannonType type) {
		Pairing c = new Pairing();
		c.setCannon(type, origin, Passcodes.DEFAULT, "default");
		if (type == CannonType.SFC) return c;
		if (origin.getX() % 16 != 0 || origin.getY() % 16 != 0 || origin.getZ() % 16 != 0) {
			Msg.error("An " + type.displayName + " origin is a multiple of 16 on every axis (like 0 16 -32), but you typed "
					+ origin.getX() + " " + origin.getY() + " " + origin.getZ() + ".");
			return null;
		}
		CannonScanner.Result match = CannonScanner.matchAt(level, origin);
		if (match != null && match.confidence() < CannonScanner.MIN_CONFIDENCE) {
			Msg.warn("Heads up: the blocks at " + origin.getX() + " " + origin.getY() + " " + origin.getZ() + " don't look like an OSC Mk6 / MS origin. Double-check the coordinates.");
		} else if (match != null && match.type() != type) {
			Msg.warn("Heads up: the cannon at that origin looks like an " + match.type().displayName + ", not an " + type.displayName + ".");
		}
		CannonScanner.PasscodeRead read = CannonScanner.readPasscode(level, origin);
		if (read != null) {
			c.passcode = read.passcode();
			c.passcodeSource = "cannon";
		}
		return c;
	}

	private static int saveCannon(Pairing cannon, String how, String dim, Pairing previous, List<CannonScanner.Result> alsoFound) {
		cannon.dimension = dim;
		Store.setPending(cannon);
		BlockPos o = cannon.origin();
		Msg.send(Msg.text("✔ Step 1 of 2: saved the " + cannon.type().displayName + " at " + o.getX() + " " + o.getY() + " " + o.getZ(), ChatFormatting.GREEN)
				.append(passcodePart(cannon)).append(Msg.text(" (" + how + ")", ChatFormatting.DARK_GRAY)));
		passcodeNotice(cannon);
		alsoFoundLine(alsoFound);
		replacedLine(previous, cannon);
		Msg.send(Msg.text("Next: ", ChatFormatting.GRAY).append(Msg.text("go to your control panel and run ", ChatFormatting.WHITE))
				.append(Msg.text("/firecontrol pair", ChatFormatting.AQUA)));
		return 1;
	}

	private static int savePanel(PanelLayout panel, String dim, Pairing previous) {
		Pairing half = new Pairing();
		half.dimension = dim;
		half.panel = panel;
		Store.setPending(half);
		if (!panel.isGrid()) {
			Msg.ok("✔ Step 1 of 2: saved this SFC panel.");
			replacedLine(previous, half);
			Msg.send(Msg.text("Next: ", ChatFormatting.GRAY).append(Msg.text("/firecontrol pair at <x> <y> <z> sfc", ChatFormatting.AQUA))
					.append(Msg.text(" from anywhere (SFC cannons can't be found automatically).", ChatFormatting.GRAY)));
			return 1;
		}
		Msg.ok("✔ Step 1 of 2: saved this panel. No cannon is loaded from here.");
		replacedLine(previous, half);
		Msg.send(Msg.text("Next: ", ChatFormatting.GRAY).append(Msg.text("run ", ChatFormatting.WHITE)).append(Msg.text("/firecontrol pair", ChatFormatting.AQUA))
				.append(Msg.text(" at your cannon (it only has to be in render distance), or type its origin: ", ChatFormatting.WHITE))
				.append(Msg.text("/firecontrol pair at <x> <y> <z> <mk6|ms>", ChatFormatting.AQUA)));
		return 1;
	}

	private static void replacedLine(Pairing previous, Pairing now) {
		if (previous == null) return;
		boolean sameCannon = previous.hasCannon() && now.hasCannon() && previous.origin().equals(now.origin());
		boolean samePanel = previous.panel != null && now.panel != null && previous.panel.sameBulbsAs(now.panel);
		if (sameCannon || samePanel) return;
		Msg.send(Msg.text("  (This replaces the ", ChatFormatting.DARK_GRAY).append(FireControl.describePending(previous))
				.append(Msg.text(" you saved before.)", ChatFormatting.DARK_GRAY)));
	}

	private static Pairing finish(Pairing cannon, PanelLayout panel, String dim) {
		List<Pairing> all = Store.pairings();
		Pairing p = null;
		for (Pairing q : all) {
			if (dim.equals(q.dimension) && q.panel != null && q.panel.sameBulbsAs(panel)
					&& q.hasCannon() && q.type() == cannon.type() && q.origin().equals(cannon.origin())) p = q;
		}
		boolean added = p == null;
		if (added) {
			p = new Pairing();
			p.name = uniqueName(cannon.type().id);
			p.dimension = dim;
			all.add(p);
		}
		// keep a known passcode unless the key was just read
		boolean keep = !added && !"cannon".equals(cannon.passcodeSource) && p.passcodeKnown();
		p.setCannon(cannon.type(), cannon.origin(), keep ? p.passcode : cannon.passcode, keep ? p.passcodeSource : cannon.passcodeSource);
		p.panel = panel;
		Actions.select(p);
		return p;
	}

	private static void printPaired(Pairing p, boolean added, String how, List<CannonScanner.Result> alsoFound) {
		PanelLayout panel = p.panel;
		CannonType type = p.type();
		BlockPos o = p.origin();
		Msg.send(Msg.text("✔ " + (added ? "Saved preset" : "Updated preset") + " '" + p.name + "' · " + type.displayName, ChatFormatting.GREEN)
				.append(Msg.text(" · cannon " + o.getX() + " " + o.getY() + " " + o.getZ(), ChatFormatting.GRAY))
				.append(passcodePart(p)).append(Msg.text(" (" + how + ")", ChatFormatting.DARK_GRAY)));
		passcodeNotice(p);
		alsoFoundLine(alsoFound);
		CannonType hint = panel.signHint == null ? null : CannonType.byId(panel.signHint);
		if (hint != null && hint != type && type != CannonType.SFC) {
			Msg.warn("Heads up: the panel's signs look like an " + hint.displayName + " panel, but the cannon is an " + type.displayName + ".");
		}
		int buttons = 0;
		for (int i = 0; i < panel.size(); i++) if (panel.button(i) != null) buttons++;
		if (buttons < panel.size()) Msg.warn("  Found " + buttons + "/" + panel.size() + " panel buttons; InputSettings can't press the missing ones.");
		if (panel.inputButtonPos() == null) Msg.warn("  Couldn't find the Input Settings button, so the ETA won't start by itself. Start it from /firecontrol → More.");
		if (p.hasTarget) {
			Msg.info("Kept the previous target " + p.targetX + " " + p.targetY + " " + p.targetZ + ".");
		} else {
			Msg.send(Msg.text("Next: stand on your target and run ", ChatFormatting.GRAY).append(Msg.text("/firecontrol target", ChatFormatting.AQUA))
					.append(Msg.text(", or type it in the ", ChatFormatting.GRAY)).append(Msg.button("menu", "/firecontrol", "Open the FireControl menu")));
		}
	}

	private static MutableComponent passcodePart(Pairing p) {
		if (!p.type().hasPasscode()) return Msg.text("");
		String note = switch (p.passcodeSource) {
			case "cannon" -> "";
			case "manual" -> " (yours)";
			default -> " (not read yet)";
		};
		return Msg.text(" · passcode " + p.passcode + note, p.passcodeKnown() ? ChatFormatting.GRAY : ChatFormatting.YELLOW);
	}

	private static void passcodeNotice(Pairing p) {
		if (!p.type().hasPasscode()) return;
		if (!p.passcodeKnown()) {
			Msg.warn("  The cannon isn't loaded, so its passcode is read the next time you're near it. Or set it in /firecontrol → More.");
		} else if (!Passcodes.isValid(p.passcode)) {
			Msg.warn("  Passcode " + p.passcode + " isn't in CubicMetre's good-code list; it may misfire.");
		}
	}

	private static void alsoFoundLine(List<CannonScanner.Result> alsoFound) {
		if (alsoFound.isEmpty()) return;
		StringBuilder sb = new StringBuilder("Also loaded: ");
		for (int i = 0; i < alsoFound.size(); i++) {
			BlockPos o = alsoFound.get(i).origin();
			sb.append(i == 0 ? "" : ", ").append(alsoFound.get(i).type().displayName).append(" at ").append(o.getX()).append(' ').append(o.getY()).append(' ').append(o.getZ());
		}
		Msg.send(Msg.text(sb.append(". Use 'pair at' to pick another.").toString(), ChatFormatting.DARK_GRAY));
	}

	private static String panelKind(PanelLayout panel) {
		return panel.isGrid() ? "a Mk6 / MS panel" : "an SFC panel";
	}

	private static int pairCancel() {
		Pairing half = Store.pending();
		if (half == null) {
			Msg.info("Nothing is waiting to be paired.");
			return 1;
		}
		Store.setPending(null);
		Msg.ok("Forgot the " + FireControl.describePending(half).getString() + " you saved.");
		return 1;
	}

	// among cannons that match about as well as the best, prefer the model the panel's signs say, then the closest
	private static CannonScanner.Result pickCannon(List<CannonScanner.Result> cannons, CannonType hint, BlockPos around) {
		double top = cannons.stream().mapToDouble(CannonScanner.Result::confidence).max().orElse(0);
		return cannons.stream()
				.filter(r -> r.confidence() >= top - 0.1)
				.min(java.util.Comparator.<CannonScanner.Result>comparingInt(r -> hint != null && r.type() != hint ? 1 : 0)
						.thenComparingDouble(r -> r.origin().distSqr(around)))
				.orElse(cannons.get(0));
	}

	private static String uniqueName(String base) {
		List<Pairing> all = Store.pairings();
		for (int i = 1; ; i++) {
			String n = base + "-" + i;
			if (all.stream().noneMatch(p -> p.name.equals(n))) return n;
		}
	}

	private static Pairing require() {
		Pairing p = FireControl.resolveActive();
		if (p == null) {
			Msg.error("Nothing paired here yet. Run /firecontrol pair at your control panel and at your cannon, in either order (or once at a pearl cannon).");
			pendingLine();
		}
		return p;
	}

	private static void pendingLine() {
		Pairing half = Store.pending();
		if (half == null) return;
		Msg.send(Msg.text("Waiting to be paired: ", ChatFormatting.YELLOW).append(FireControl.describePending(half))
				.append(Msg.text(half.hasCannon() ? ". Run /firecontrol pair at your panel. " : ". Run /firecontrol pair at your cannon. ", ChatFormatting.GRAY))
				.append(Msg.button("cancel", "/firecontrol pair cancel", "Forget this half")));
	}

	private static Pairing requireForTarget() {
		Pairing p = FireControl.resolveActive();
		if (p != null) return p;
		p = FireControl.netherPearlFromOverworld();
		return p != null ? p : require();
	}

	private static int targetXZ(CommandContext<FabricClientCommandSource> ctx, FireMode mode, Integer size, Integer slot) {
		Pairing p = requireForTarget();
		if (p == null) return 0;
		LocalPlayer pl = ctx.getSource().getPlayer();
		int x = CoordArgument.resolve(CoordArgument.get(ctx, "x"), pl.getX());
		int z = CoordArgument.resolve(CoordArgument.get(ctx, "y"), pl.getZ());
		String dim = p.groundDimension(FireControl.dimensionId(Minecraft.getInstance().level));
		// the Nether has several floors, so use the cannon floor
		if (p.isPearl() && (dim == null || Pairing.NETHER.equals(dim))) return target(new BlockPos(x, p.originY, z), mode, size, slot, false, null);
		GroundHeight.whenReady(dim, x, z, a -> {
			if (a.ready() || p.isPearl()) {
				target(new BlockPos(x, a.ready() ? a.y() : p.originY, z), mode, size, slot, false, a);
			} else {
				Msg.error("No ground height for " + x + " " + z + " (" + a.why() + "). Type the Y too: /firecontrol target <x> <y> <z>");
			}
		});
		return 1;
	}

	private static int target(BlockPos t, FireMode mode, Integer size, Integer slot, boolean here, GroundHeight.Answer ground) {
		Pairing p = requireForTarget();
		if (p == null) return 0;
		if (p.isPearl()) {
			if (mode != null || size != null || slot != null) Msg.warn("A pearl cannon has no payload; ignoring it.");
			return targetPearl(p, t, here, ground);
		}
		CannonType type = p.type();
		if (slot != null && type != CannonType.MK6) Msg.warn("Magazine slots only apply to the Mk6; ignoring slot " + slot + ".");
		Actions.setTarget(p, t, mode, size, slot);
		p.autoY = ground != null;
		Store.save();

		FireCalculator.Solution s = p.solve();
		FireCalculator.Request r = s.request;
		String payload = type == CannonType.SFC ? "payload " + r.nukeSize() : r.mode() == FireMode.NUKE ? "nuke " + r.nukeSize() : "stab " + r.stabDepth();
		String slotText = type == CannonType.MK6 && r.magazineSlot() != 0 ? " · slot " + r.magazineSlot() : "";
		MutableComponent head = Msg.text(here ? "Target set to where you stand: " : "Target ", ChatFormatting.GRAY)
				.append(Msg.text(r.x() + " " + r.y() + " " + r.z(), ChatFormatting.WHITE))
				.append(groundPart(ground))
				.append(Msg.text(" · " + payload + slotText, ChatFormatting.GRAY));
		if (s.isValid()) head.append(Msg.text(" · ETA ", ChatFormatting.GRAY)).append(Msg.text(FireCalculator.formatTicks(s.totalTicks), ChatFormatting.WHITE));
		head.append(" ").append(here ? Msg.button("Set payload", "/firecontrol", "Choose nuke or stab and the size")
				: Msg.button("Menu", "/firecontrol", "Open the FireControl menu"));
		Msg.send(head);
		if (!s.isValid()) {
			for (String e : s.errors) Msg.error("✖ " + e);
			return 1;
		}
		if (type != CannonType.SFC && !p.passcodeKnown()) {
			Msg.warn("⚠ The cannon's passcode hasn't been read yet, so this uses " + p.passcode + ". Go near the cannon once, or set it in /firecontrol → More.");
		}
		int wrong = countWrong(p, s);
		if (wrong > 0) {
			Msg.send(Msg.text(wrong + " button" + (wrong == 1 ? "" : "s") + " to press. ", ChatFormatting.YELLOW)
					.append(Msg.button("Enter them for me", "/firecontrol InputSettings", "Presses them for you (stand at the panel)")));
		} else if (wrong == 0) {
			Msg.ok("The panel already matches. Press INPUT SETTINGS to fire.");
		}
		return 1;
	}

	private static MutableComponent groundPart(GroundHeight.Answer ground) {
		String text = ground == null ? "" : ground.ready() ? " (Y: " + ground.source().label + ")" : " (Y: cannon floor; " + ground.why() + ")";
		return Msg.text(text, ChatFormatting.DARK_GRAY);
	}

	private static int targetPearl(Pairing p, BlockPos t, boolean here, GroundHeight.Answer ground) {
		Actions.setPearlTarget(p, t, null);
		p.autoY = ground != null;
		Store.save();
		PearlCalculator.Solution s = p.solvePearl();
		MutableComponent head = Msg.text(here ? "Target set to where you stand: " : "Target ", ChatFormatting.GRAY)
				.append(Msg.text(t.getX() + " " + t.getY() + " " + t.getZ(), ChatFormatting.WHITE))
				.append(groundPart(ground));
		if (FireControl.resolveActive() != p) head.append(Msg.text(" for '" + p.name + "' in the nether", ChatFormatting.GRAY));
		if (p.netherConversion()) head.append(Msg.text(" (overworld → nether " + s.targetX + " " + s.targetZ + ")", ChatFormatting.GRAY));
		if (s.total() == 0) {
			Msg.send(head.append(Msg.text(" · that's the cannon itself; nothing to load.", ChatFormatting.YELLOW)));
			return 1;
		}
		head.append(Msg.text(" · " + PearlControl.plan(p, s), ChatFormatting.WHITE))
				.append(Msg.text(String.format(" · ±%.0f blocks · flight %s ", s.error, FireCalculator.formatTicks(PearlControl.IN_CANNON_TICKS + s.flightTicks)), ChatFormatting.GRAY))
				.append(Msg.button("Load it for me", "/firecontrol InputSettings", "Sets the axis bulbs and fills the INPUT for you (stand at the cannon)"));
		Msg.send(head);
		return 1;
	}

	private static int countWrong(Pairing p, FireCalculator.Solution s) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || p.panel == null || !p.panel.matchesType(p.type())) return -1;
		int wrong = 0;
		for (int i = 0; i < p.panel.size(); i++) {
			BlockPos b = p.panel.bulb(i);
			if (b == null || !level.isLoaded(b)) return -1;
			var st = level.getBlockState(b);
			if (com.firecontrol.world.Blocks2.isBulb(st) && com.firecontrol.world.Blocks2.isLit(st) != s.bits[i]) wrong++;
		}
		return wrong;
	}
}
