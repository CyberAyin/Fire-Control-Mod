package com.firecontrol.e2e;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

// Pastes a .litematic into a server level (optionally rotated) without block updates.
final class LitematicPaster {
	// no neighbour updates or onPlace, so pasted redstone stays as saved
	static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

	private LitematicPaster() {
	}

	static BlockPos transform(BlockPos at, Rotation rot, int x, int y, int z) {
		return switch (rot) {
			case CLOCKWISE_90 -> at.offset(-z, y, x);
			case CLOCKWISE_180 -> at.offset(-x, y, -z);
			case COUNTERCLOCKWISE_90 -> at.offset(z, y, -x);
			default -> at.offset(x, y, z);
		};
	}

	static int paste(ServerLevel level, Path file, BlockPos at, Rotation rot) throws Exception {
		CompoundTag root;
		try (InputStream in = Files.newInputStream(file)) {
			root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
		}
		CompoundTag regions = root.getCompoundOrEmpty("Regions");
		var blockLookup = level.holderLookup(Registries.BLOCK);
		int placed = 0;
		List<Runnable> blockEntities = new ArrayList<>();
		for (String name : regions.keySet()) {
			CompoundTag reg = regions.getCompoundOrEmpty(name);
			CompoundTag size = reg.getCompoundOrEmpty("Size");
			CompoundTag pos = reg.getCompoundOrEmpty("Position");
			int sx = size.getIntOr("x", 0), sy = size.getIntOr("y", 0), sz = size.getIntOr("z", 0);
			int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
			int mx = pos.getIntOr("x", 0) + (sx < 0 ? sx + 1 : 0);
			int my = pos.getIntOr("y", 0) + (sy < 0 ? sy + 1 : 0);
			int mz = pos.getIntOr("z", 0) + (sz < 0 ? sz + 1 : 0);

			ListTag paletteTag = reg.getListOrEmpty("BlockStatePalette");
			BlockState[] palette = new BlockState[paletteTag.size()];
			for (int i = 0; i < palette.length; i++) {
				// Litematica writes Name/Properties, 26.3 reads id/properties
				CompoundTag entry = paletteTag.getCompoundOrEmpty(i).copy();
				entry.getString("Name").ifPresent(n -> entry.putString("id", n));
				entry.getCompound("Properties").ifPresent(p -> entry.put("properties", p.copy()));
				palette[i] = NbtUtils.readBlockState(blockLookup, entry).rotate(rot);
			}
			long[] data = reg.getLongArray("BlockStates").orElse(new long[0]);
			int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
			long mask = (1L << bits) - 1;
			long volume = (long) ax * ay * az;
			for (long i = 0; i < volume; i++) {
				long start = i * bits;
				int li = (int) (start >>> 6);
				int off = (int) (start & 63);
				long v = data[li] >>> off;
				if (off + bits > 64) v |= data[li + 1] << (64 - off);
				v &= mask;
				BlockState st = palette[(int) v];
				if (st.isAir()) continue;
				int x = (int) (i % ax), z = (int) ((i / ax) % az), y = (int) (i / ((long) ax * az));
				level.setBlock(transform(at, rot, mx + x, my + y, mz + z), st, FLAGS);
				placed++;
			}

			ListTag tiles = reg.getListOrEmpty("TileEntities");
			for (int i = 0; i < tiles.size(); i++) {
				CompoundTag t = tiles.getCompoundOrEmpty(i).copy();
				BlockPos wp = transform(at, rot, mx + t.getIntOr("x", 0), my + t.getIntOr("y", 0), mz + t.getIntOr("z", 0));
				blockEntities.add(() -> {
					BlockState st = level.getBlockState(wp);
					t.putInt("x", wp.getX());
					t.putInt("y", wp.getY());
					t.putInt("z", wp.getZ());
					try {
						BlockEntity be = BlockEntity.loadStatic(wp, st, t, level.registryAccess());
						if (be != null) level.setBlockEntity(be);
					} catch (Exception ignored) {
						// only some block entities matter here
					}
				});
			}
		}
		blockEntities.forEach(Runnable::run);
		return placed;
	}

	static void fill(ServerLevel level, BlockPos a, BlockPos b, BlockState state) {
		for (BlockPos p : BlockPos.betweenClosed(a, b)) level.setBlock(p, state, FLAGS);
	}

	static BlockState air() {
		return Blocks.AIR.defaultBlockState();
	}
}
