package com.firecontrol.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

// 26.2: screens and HUD like 26.3, swing and signs like 26.1
public final class Compat {
	private Compat() {
	}

	public static Screen screen(Minecraft mc) {
		return mc.gui.screen();
	}

	public static void setScreen(Minecraft mc, Screen screen) {
		mc.gui.setScreen(screen);
	}

	public static RenderTarget mainRenderTarget(Minecraft mc) {
		return mc.gameRenderer.mainRenderTarget();
	}

	public static boolean hudHidden(Minecraft mc) {
		return mc.gui.hud.isHidden();
	}

	public static void swing(LocalPlayer player) {
		player.swing(InteractionHand.MAIN_HAND);
	}

	// button 0/1 = left/right click, quickMove = shift-click
	public static void clickSlot(Minecraft mc, int containerId, int slot, int button, boolean quickMove) {
		mc.gameMode.handleContainerInput(containerId, slot, button, quickMove ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
	}

	public static boolean isWindCharge(Entity e) {
		return e.getType() == EntityTypes.WIND_CHARGE || e.getType() == EntityTypes.BREEZE_WIND_CHARGE;
	}

	public static boolean isEnderPearl(Entity e) {
		return e.getType() == EntityTypes.ENDER_PEARL;
	}

	public static List<Component> signLines(SignBlockEntity sign) {
		List<Component> out = new ArrayList<>(8);
		for (int i = 0; i < 4; i++) {
			out.add(sign.getFrontText().getMessage(i, false));
			out.add(sign.getBackText().getMessage(i, false));
		}
		return out;
	}

	public static void showMessage(LocalPlayer player, Component message) {
		player.sendSystemMessage(message);
	}

	public static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
		return ClientCommands.literal(name);
	}

	public static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(String name, ArgumentType<T> type) {
		return ClientCommands.argument(name, type);
	}

	public static CommandDispatcher<FabricClientCommandSource> activeDispatcher() {
		return ClientCommands.getActiveDispatcher();
	}

	// only used for heights, so no biomes
	public static NoiseBasedChunkGenerator noiseGenerator(Holder<NoiseGeneratorSettings> settings) {
		return new NoiseBasedChunkGenerator(new NoBiomes(), settings);
	}

	// surface rules reference biomes in 26.2, so this needs the full set
	public static HolderLookup.Provider worldgenRegistries() {
		return VanillaRegistries.createLookup();
	}

	public static RandomState randomState(HolderLookup.Provider worldgen, ResourceKey<NoiseGeneratorSettings> settings, long seed) {
		return RandomState.create(worldgen, settings, seed);
	}

	private static final class NoBiomes extends BiomeSource {
		@Override
		protected MapCodec<? extends BiomeSource> codec() {
			throw new UnsupportedOperationException();
		}

		@Override
		protected Stream<Holder<Biome>> collectPossibleBiomes() {
			return Stream.empty();
		}

		@Override
		public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
			throw new UnsupportedOperationException("only used for heights");
		}
	}

	public static void onLevelChange(Runnable action) {
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((mc, level) -> action.run());
	}

	public static void onBeforeGizmos(Runnable action) {
		LevelRenderEvents.BEFORE_GIZMOS.register(context -> action.run());
	}

	public static void addHudAfterChat(Identifier id, BiConsumer<Canvas, DeltaTracker> hud) {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, id, (graphics, delta) -> hud.accept(new Canvas(graphics), delta));
	}

	public static final class Canvas {
		private final GuiGraphicsExtractor g;

		Canvas(GuiGraphicsExtractor g) {
			this.g = g;
		}

		public int width() {
			return g.guiWidth();
		}

		public int height() {
			return g.guiHeight();
		}

		public void fill(int x0, int y0, int x1, int y1, int color) {
			g.fill(x0, y0, x1, y1, color);
		}

		public void fillGradient(int x0, int y0, int x1, int y1, int top, int bottom) {
			g.fillGradient(x0, y0, x1, y1, top, bottom);
		}

		// drawn at real screen pixels so a moving edge doesn't jump a whole GUI pixel
		public void fillGradientFine(double x0, int y0, double x1, int y1, int top, int bottom) {
			int s = Minecraft.getInstance().getWindow().getGuiScale();
			g.pose().pushMatrix();
			g.pose().scale(1f / s, 1f / s);
			g.fillGradient((int) Math.round(x0 * s), y0 * s, (int) Math.round(x1 * s), y1 * s, top, bottom);
			g.pose().popMatrix();
		}

		public void text(Font font, String text, int x, int y, int color) {
			g.text(font, text, x, y, color);
		}

		public void centeredText(Font font, String text, int x, int y, int color) {
			g.centeredText(font, text, x, y, color);
		}
	}
}
