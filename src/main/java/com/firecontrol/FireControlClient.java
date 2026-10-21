package com.firecontrol;

import com.firecontrol.command.FireControlCommands;
import com.firecontrol.compat.Compat;
import com.firecontrol.gui.FireControlScreen;
import com.firecontrol.render.FireControlHud;
import com.firecontrol.render.PanelOverlay;
import com.firecontrol.render.PearlOverlay;
import com.firecontrol.world.GroundHeight;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;

public class FireControlClient implements ClientModInitializer {
	public static final String MOD_ID = "firecontrol";

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> FireControlCommands.register(dispatcher));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			FireControl.tick(mc);
			AutoInput.tick(mc);
			PearlLoader.tick(mc);
			FireControlScreen.clientTick(mc);
		});
		Compat.onLevelChange(() -> {
			AutoInput.stop(null);
			PearlLoader.stop(null);
			FireControl.invalidate();
			GroundHeight.onLevelChange();
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level instanceof ClientLevel client && player == Minecraft.getInstance().player) FireControl.onUseBlock(client, hit.getBlockPos());
			return InteractionResult.PASS;
		});

		Compat.onBeforeGizmos(() -> {
			PanelOverlay.draw();
			PearlOverlay.draw();
		});
		// after chat so it stays visible
		Compat.addHudAfterChat(Identifier.fromNamespaceAndPath(MOD_ID, "eta"), FireControlHud::draw);
	}
}
