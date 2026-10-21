package com.firecontrol;

import com.firecontrol.compat.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Msg {
	private static final Logger LOG = LoggerFactory.getLogger("firecontrol");

	private Msg() {
	}

	public static MutableComponent prefix() {
		return Component.literal("[FireControl] ").withStyle(ChatFormatting.GOLD);
	}

	public static MutableComponent text(String s, ChatFormatting... f) {
		return Component.literal(s).withStyle(f);
	}

	public static MutableComponent button(String label, String command, String hover) {
		return Component.literal("[" + label + "]").withStyle(s -> s.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	public static void send(Component c) {
		Minecraft mc = Minecraft.getInstance();
		LOG.info("[FireControl] {}", c.getString());
		if (mc.player != null) Compat.showMessage(mc.player, prefix().append(c));
	}

	public static void info(String s) {
		send(text(s, ChatFormatting.GRAY));
	}

	public static void ok(String s) {
		send(text(s, ChatFormatting.GREEN));
	}

	public static void warn(String s) {
		send(text(s, ChatFormatting.YELLOW));
	}

	public static void error(String s) {
		send(text(s, ChatFormatting.RED));
	}
}
