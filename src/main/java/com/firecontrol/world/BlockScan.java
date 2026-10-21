package com.firecontrol.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class BlockScan {
	private BlockScan() {
	}

	// ClientLevel.hasChunk returns true even for unloaded chunks (which then read as air), so ask the chunk source
	public static boolean loaded(ClientLevel level, int chunkX, int chunkZ) {
		return level.getChunkSource().hasChunk(chunkX, chunkZ);
	}

	public static LongOpenHashSet collect(ClientLevel level, BlockPos min, BlockPos max, Predicate<BlockState> match) {
		LongOpenHashSet out = new LongOpenHashSet();
		int minY = Math.max(min.getY(), level.getMinY());
		int maxY = Math.min(max.getY(), level.getMaxY());
		for (int cx = SectionPos.blockToSectionCoord(min.getX()); cx <= SectionPos.blockToSectionCoord(max.getX()); cx++) {
			for (int cz = SectionPos.blockToSectionCoord(min.getZ()); cz <= SectionPos.blockToSectionCoord(max.getZ()); cz++) {
				if (!loaded(level, cx, cz)) continue;
				LevelChunk chunk = level.getChunk(cx, cz);
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection section = sections[i];
					if (section == null || section.hasOnlyAir() || !section.maybeHas(match)) continue;
					int baseY = SectionPos.sectionToBlockCoord(level.getSectionYFromSectionIndex(i));
					if (baseY + 15 < minY || baseY > maxY) continue;
					for (int ly = 0; ly < 16; ly++) {
						int y = baseY + ly;
						if (y < minY || y > maxY) continue;
						for (int lz = 0; lz < 16; lz++) {
							int z = (cz << 4) + lz;
							if (z < min.getZ() || z > max.getZ()) continue;
							for (int lx = 0; lx < 16; lx++) {
								int x = (cx << 4) + lx;
								if (x < min.getX() || x > max.getX()) continue;
								if (match.test(section.getBlockState(lx, ly, lz))) out.add(BlockPos.asLong(x, y, z));
							}
						}
					}
				}
			}
		}
		return out;
	}
}
