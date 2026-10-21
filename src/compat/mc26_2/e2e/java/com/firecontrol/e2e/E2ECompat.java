package com.firecontrol.e2e;

import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;

final class E2ECompat {
	private E2ECompat() {
	}

	static LevelSettings levelSettings(String name) {
		return new LevelSettings(name, GameType.CREATIVE,
				new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
	}
}
