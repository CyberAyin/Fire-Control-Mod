package com.firecontrol.world;

import com.firecontrol.compat.Compat;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

public final class Blocks2 {
	private Blocks2() {
	}

	public static boolean isBulb(BlockState state) {
		return state.getBlock() instanceof CopperBulbBlock;
	}

	public static boolean isLit(BlockState state) {
		return isBulb(state) && state.getValue(CopperBulbBlock.LIT);
	}

	public static boolean isButton(BlockState state) {
		return state.getBlock() instanceof ButtonBlock;
	}

	public static boolean isPressed(BlockState state) {
		return isButton(state) && state.getValue(ButtonBlock.POWERED);
	}

	// same as vanilla getConnectedDirection
	public static Direction buttonOutward(BlockState state) {
		AttachFace face = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
		return switch (face) {
			case CEILING -> Direction.DOWN;
			case FLOOR -> Direction.UP;
			default -> state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
		};
	}

	public static BlockPos buttonSupport(BlockPos buttonPos, BlockState state) {
		return buttonPos.relative(buttonOutward(state).getOpposite());
	}

	public static String id(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
	}

	public static String signText(Level level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof SignBlockEntity sign)) return "";
		StringBuilder sb = new StringBuilder();
		for (Component line : Compat.signLines(sign)) sb.append(line.getString()).append(' ');
		return sb.toString().replace("\"", "").toLowerCase(Locale.ROOT);
	}

	public static String colorFamily(BlockState state) {
		String id = id(state);
		if (!(id.contains("concrete") || id.contains("wool") || id.contains("glass") || id.contains("terracotta"))) return null;
		if (id.startsWith("light_blue") || id.startsWith("blue") || id.startsWith("cyan")) return "blue";
		if (id.startsWith("lime") || id.startsWith("green")) return "green";
		if (id.startsWith("yellow")) return "yellow";
		if (id.startsWith("orange")) return "orange";
		if (id.startsWith("red")) return "red";
		if (id.startsWith("brown")) return "brown";
		return null;
	}
}
