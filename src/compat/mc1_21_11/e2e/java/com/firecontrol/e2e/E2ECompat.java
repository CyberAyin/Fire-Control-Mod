package com.firecontrol.e2e;

import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;

final class E2ECompat {
	private E2ECompat() {
	}

	static LevelSettings levelSettings(String name) {
		WorldDataConfiguration data = WorldDataConfiguration.DEFAULT;
		return new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(data.enabledFeatures()), data);
	}
}
