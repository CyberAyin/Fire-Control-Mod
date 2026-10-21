package com.firecontrol.world;

import com.firecontrol.calc.FireCalculator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// Finds Mk6/MS panels: 13x5 copper bulbs behind slime blocks with a button each, concrete frame and an
// "Input Settings" sign. Works in any orientation.
public final class PanelScanner {
	public static final int RADIUS = 32;
	private static final int ROWS = FireCalculator.GRID_ROWS;
	private static final int COLS = FireCalculator.GRID_COLS;
	// frame colour per column, left to right
	private static final String[] FRAME_COLORS = {
		"blue", "blue", "blue", "blue", "green", "green", "yellow", "orange", "orange", "orange", "red", "brown", "brown"
	};
	private static final Direction[] POSITIVE = {Direction.EAST, Direction.UP, Direction.SOUTH};

	private PanelScanner() {
	}

	public static List<PanelLayout> scan(ClientLevel level, LocalPlayer player) {
		BlockPos c = player.blockPosition();
		LongOpenHashSet bulbs = BlockScan.collect(level, c.offset(-RADIUS, -RADIUS, -RADIUS), c.offset(RADIUS, RADIUS, RADIUS), Blocks2::isBulb);
		List<PanelLayout> out = new ArrayList<>(findGrids(level, bulbs, player));
		out.addAll(findSfc(level, bulbs, player));
		Vec3 eye = player.position();
		out.sort(Comparator.comparingDouble(p -> p.center().distanceToSqr(eye)));
		return out;
	}

	private static List<PanelLayout> findGrids(ClientLevel level, LongSet bulbs, LocalPlayer player) {
		List<PanelLayout> found = new ArrayList<>();
		Set<Long> used = new HashSet<>();
		for (long l : bulbs) {
			BlockPos base = BlockPos.of(l);
			for (Direction colDir : POSITIVE) {
				for (Direction rowDir : POSITIVE) {
					if (colDir == rowDir || !isRect(bulbs, base, colDir, COLS, rowDir, ROWS)) continue;
					List<BlockPos> cells = rectCells(base, colDir, COLS, rowDir, ROWS);
					if (cells.stream().anyMatch(p -> used.contains(p.asLong()))) continue;
					PanelLayout layout = buildGrid(level, cells, colDir, rowDir, player);
					if (layout == null) continue;
					cells.forEach(p -> used.add(p.asLong()));
					found.add(layout);
				}
			}
		}
		return found;
	}

	private static boolean isRect(LongSet bulbs, BlockPos base, Direction a, int na, Direction b, int nb) {
		for (int i = 0; i < na; i++) {
			for (int j = 0; j < nb; j++) {
				if (!bulbs.contains(base.relative(a, i).relative(b, j).asLong())) return false;
			}
		}
		return true;
	}

	private static List<BlockPos> rectCells(BlockPos base, Direction a, int na, Direction b, int nb) {
		List<BlockPos> cells = new ArrayList<>(na * nb);
		for (int i = 0; i < na; i++) {
			for (int j = 0; j < nb; j++) cells.add(base.relative(a, i).relative(b, j));
		}
		return cells;
	}

	private static PanelLayout buildGrid(ClientLevel level, List<BlockPos> cells, Direction colDir, Direction rowDir, LocalPlayer player) {
		Direction.Axis normalAxis = null;
		for (Direction.Axis axis : Direction.Axis.values()) {
			if (axis != colDir.getAxis() && axis != rowDir.getAxis()) normalAxis = axis;
		}
		Direction pos = Direction.get(Direction.AxisDirection.POSITIVE, normalAxis);
		Direction neg = pos.getOpposite();
		int posScore = buttonScore(level, cells, pos);
		int negScore = buttonScore(level, cells, neg);
		Direction front;
		if (posScore != negScore) {
			front = posScore > negScore ? pos : neg;
		} else {
			Vec3 toPlayer = player.position().subtract(Vec3.atCenterOf(cells.get(cells.size() / 2)));
			double d = toPlayer.x * pos.getStepX() + toPlayer.y * pos.getStepY() + toPlayer.z * pos.getStepZ();
			front = d >= 0 ? pos : neg;
		}

		Frame frame = Frame.of(front, player);
		List<BlockPos> sorted = sortIntoGrid(cells, frame, ROWS, COLS);
		if (sorted == null) return null; // 13 tall and 5 wide, not an OSC panel

		PanelLayout layout = new PanelLayout();
		layout.kind = "grid";
		layout.front = front.getName();
		layout.bulbs = new long[ROWS * COLS];
		layout.buttons = new long[ROWS * COLS];
		for (int i = 0; i < sorted.size(); i++) {
			BlockPos p = sorted.get(i);
			layout.bulbs[i] = p.asLong();
			BlockPos button = findButtonFor(level, p, front);
			layout.buttons[i] = button == null ? PanelLayout.NONE : button.asLong();
		}

		if (frameScore(level, layout, frame, true) > frameScore(level, layout, frame, false) + 2) layout.flip();
		findInputButtonAndHint(level, layout);
		return layout;
	}

	private static int buttonScore(ClientLevel level, List<BlockPos> cells, Direction side) {
		int n = 0;
		for (BlockPos p : cells) {
			if (findButtonFor(level, p, side) != null) n++;
		}
		return n;
	}

	static BlockPos findButtonFor(ClientLevel level, BlockPos bulb, Direction side) {
		for (int k = 1; k <= 2; k++) {
			BlockPos q = bulb.relative(side, k);
			BlockState st = level.getBlockState(q);
			if (!Blocks2.isButton(st)) continue;
			BlockPos support = Blocks2.buttonSupport(q, st);
			if (support.equals(bulb) || support.distManhattan(bulb) == 1 || q.distManhattan(bulb) == 1) return q;
		}
		return null;
	}

	record Frame(Direction front, Direction up, Direction right) {
		static Frame of(Direction front, LocalPlayer player) {
			if (front.getAxis().isHorizontal()) {
				return new Frame(front, Direction.UP, front.getOpposite().getClockWise());
			}
			Direction facing = player.getDirection(); // floor/ceiling panel: top edge is the side away from the player
			return new Frame(front, facing, facing.getClockWise());
		}

		int u(BlockPos p) {
			return dot(p, up.getUnitVec3i());
		}

		int r(BlockPos p) {
			return dot(p, right.getUnitVec3i());
		}

		private static int dot(BlockPos p, Vec3i v) {
			return p.getX() * v.getX() + p.getY() * v.getY() + p.getZ() * v.getZ();
		}
	}

	private static List<BlockPos> sortIntoGrid(List<BlockPos> cells, Frame frame, int rows, int cols) {
		long distinctU = cells.stream().mapToInt(frame::u).distinct().count();
		long distinctR = cells.stream().mapToInt(frame::r).distinct().count();
		if (distinctU != rows || distinctR != cols) return null;
		List<BlockPos> sorted = new ArrayList<>(cells);
		sorted.sort(Comparator.<BlockPos>comparingInt(p -> -frame.u(p)).thenComparingInt(frame::r));
		return sorted;
	}

	private static int frameScore(ClientLevel level, PanelLayout layout, Frame frame, boolean mirrored) {
		int score = 0;
		for (int c = 0; c < COLS; c++) {
			String expected = FRAME_COLORS[mirrored ? COLS - 1 - c : c];
			BlockPos top = layout.bulb(c);
			BlockPos bottom = layout.bulb((ROWS - 1) * COLS + c);
			boolean hit = false;
			for (int k = 0; k <= 2 && !hit; k++) {
				hit = expected.equals(Blocks2.colorFamily(level.getBlockState(top.relative(frame.up(), 1).relative(frame.front(), k))))
						|| expected.equals(Blocks2.colorFamily(level.getBlockState(bottom.relative(frame.up(), -1).relative(frame.front(), k))));
			}
			if (hit) score++;
		}
		return score;
	}

	private static void findInputButtonAndHint(ClientLevel level, PanelLayout layout) {
		Set<Long> cellButtons = new HashSet<>();
		for (long b : layout.buttons) cellButtons.add(b);
		BlockPos min = BlockPos.containing(layout.bounds().minX - 8, layout.bounds().minY - 6, layout.bounds().minZ - 8);
		BlockPos max = BlockPos.containing(layout.bounds().maxX + 8, layout.bounds().maxY + 6, layout.bounds().maxZ + 8);

		LongOpenHashSet signs = BlockScan.collect(level, min, max, s -> s.getBlock() instanceof SignBlock);
		LongOpenHashSet buttons = BlockScan.collect(level, min, max, Blocks2::isButton);
		buttons.removeIf(cellButtons::contains);

		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (long s : signs) {
			BlockPos sp = BlockPos.of(s);
			String text = Blocks2.signText(level, sp);
			if (text.contains("magazine slot")) layout.signHint = "mk6";
			else if (layout.signHint == null && (text.contains("comms") || text.contains("remote control"))) layout.signHint = "ms";
			if (!text.contains("input")) continue;
			for (long b : buttons) {
				double d = BlockPos.of(b).distSqr(sp);
				if (d <= 9 && d < bestDist) {
					bestDist = d;
					best = BlockPos.of(b);
				}
			}
		}
		if (best == null && layout.isGrid()) {
			// no sign, use the single loose button on the operator's side
			Direction f = layout.frontDir();
			BlockPos anyCellButton = null;
			for (int i = 0; i < layout.size() && anyCellButton == null; i++) anyCellButton = layout.button(i);
			List<BlockPos> loose = new ArrayList<>();
			for (long b : buttons) {
				BlockPos bp = BlockPos.of(b);
				if (anyCellButton != null && onSamePlane(bp, anyCellButton, f) && bp.distSqr(BlockPos.containing(layout.center())) < 14 * 14) loose.add(bp);
			}
			if (loose.size() == 1) best = loose.get(0);
		}
		layout.inputButton = best == null ? PanelLayout.NONE : best.asLong();
	}

	private static boolean onSamePlane(BlockPos a, BlockPos b, Direction normal) {
		return switch (normal.getAxis()) {
			case X -> a.getX() == b.getX();
			case Y -> a.getY() == b.getY();
			case Z -> a.getZ() == b.getZ();
		};
	}

	// SFC (experimental)

	private record Line(List<BlockPos> cells, Direction front, String channel) {
	}

	private static List<PanelLayout> findSfc(ClientLevel level, LongSet bulbs, LocalPlayer player) {
		List<Line> orange = new ArrayList<>();
		List<Line> blue = new ArrayList<>();
		for (long l : bulbs) {
			BlockPos base = BlockPos.of(l);
			for (Direction dir : POSITIVE) {
				if (bulbs.contains(base.relative(dir, -1).asLong()) || bulbs.contains(base.relative(dir, 15).asLong())) continue;
				List<BlockPos> cells = new ArrayList<>(15);
				for (int i = 0; i < 15; i++) cells.add(base.relative(dir, i));
				if (!cells.stream().allMatch(p -> bulbs.contains(p.asLong()))) continue;
				Line line = classifyLine(level, cells, dir);
				if (line == null) continue;
				("orange".equals(line.channel()) ? orange : blue).add(line);
			}
		}
		List<PanelLayout> out = new ArrayList<>();
		for (Line o : orange) {
			Line nearest = null;
			double best = 48 * 48;
			for (Line b : blue) {
				double d = o.cells().get(7).distSqr(b.cells().get(7));
				if (d < best) {
					best = d;
					nearest = b;
				}
			}
			if (nearest != null) out.add(buildSfc(level, o, nearest, bulbs, player));
		}
		return out;
	}

	private static Line classifyLine(ClientLevel level, List<BlockPos> cells, Direction lineDir) {
		Map<Direction, Integer> votes = new EnumMap<>(Direction.class);
		for (BlockPos p : cells) {
			for (Direction d : Direction.values()) {
				if (d.getAxis() == lineDir.getAxis()) continue;
				if (findButtonFor(level, p, d) != null) votes.merge(d, 1, Integer::sum);
			}
		}
		Direction front = null;
		int max = 0;
		for (Map.Entry<Direction, Integer> e : votes.entrySet()) {
			// prefer a horizontal side, the SFC test world has extra buttons above and below each bulb
			int v = e.getValue() * 2 + (e.getKey().getAxis().isHorizontal() ? 1 : 0);
			if (v > max) {
				max = v;
				front = e.getKey();
			}
		}
		if (front == null || votes.get(front) < 10) return null;
		int o = 0, b = 0;
		for (BlockPos p : cells) {
			for (BlockPos q : BlockPos.betweenClosed(p.offset(-3, -3, -3), p.offset(3, 3, 3))) {
				String fam = Blocks2.colorFamily(level.getBlockState(q));
				if ("orange".equals(fam)) o++;
				else if ("blue".equals(fam)) b++;
			}
		}
		if (o == b) return null;
		return new Line(cells, front, o > b ? "orange" : "blue");
	}

	private static PanelLayout buildSfc(ClientLevel level, Line orange, Line blue, LongSet bulbs, LocalPlayer player) {
		PanelLayout layout = new PanelLayout();
		layout.kind = "sfc";
		layout.signHint = "sfc";
		layout.front = orange.front().getName();
		layout.bulbs = new long[FireCalculator.SFC_BITS];
		layout.buttons = new long[FireCalculator.SFC_BITS];
		java.util.Arrays.fill(layout.bulbs, PanelLayout.NONE);
		java.util.Arrays.fill(layout.buttons, PanelLayout.NONE);
		fillLine(level, layout, orange, 0, player);
		fillLine(level, layout, blue, 15, player);

		// sign bulbs: the two loose bulbs with a button closest to the middle of the two rows
		Set<Long> lineCells = new HashSet<>();
		orange.cells().forEach(p -> lineCells.add(p.asLong()));
		blue.cells().forEach(p -> lineCells.add(p.asLong()));
		Vec3 mid = Vec3.atCenterOf(orange.cells().get(7)).add(Vec3.atCenterOf(blue.cells().get(7))).scale(0.5);
		List<BlockPos[]> loose = new ArrayList<>();
		for (long l : bulbs) {
			if (lineCells.contains(l)) continue;
			BlockPos p = BlockPos.of(l);
			if (Vec3.atCenterOf(p).distanceToSqr(mid) > 24 * 24) continue;
			for (Direction d : Direction.values()) {
				BlockPos button = findButtonFor(level, p, d);
				if (button != null) {
					loose.add(new BlockPos[] {p, button});
					break;
				}
			}
		}
		loose.sort(Comparator.comparingDouble(a -> Vec3.atCenterOf(a[0]).distanceToSqr(mid)));
		if (loose.size() >= 2) {
			BlockPos[] a = loose.get(0), b = loose.get(1);
			boolean aIsOrange = a[0].distSqr(orange.cells().get(7)) <= b[0].distSqr(orange.cells().get(7));
			BlockPos[] os = aIsOrange ? a : b, bs = aIsOrange ? b : a;
			layout.bulbs[FireCalculator.SFC_ORANGE_SIGN] = os[0].asLong();
			layout.buttons[FireCalculator.SFC_ORANGE_SIGN] = os[1].asLong();
			layout.bulbs[FireCalculator.SFC_BLUE_SIGN] = bs[0].asLong();
			layout.buttons[FireCalculator.SFC_BLUE_SIGN] = bs[1].asLong();
		}
		findInputButtonAndHint(level, layout);
		return layout;
	}

	private static void fillLine(ClientLevel level, PanelLayout layout, Line line, int offset, LocalPlayer player) {
		Frame frame = Frame.of(line.front(), player);
		List<BlockPos> sorted = new ArrayList<>(line.cells());
		sorted.sort(Comparator.comparingInt(frame::r)); // MSB on the operator's left
		for (int i = 0; i < 15; i++) {
			BlockPos p = sorted.get(i);
			layout.bulbs[offset + i] = p.asLong();
			BlockPos button = findButtonFor(level, p, line.front());
			layout.buttons[offset + i] = button == null ? PanelLayout.NONE : button.asLong();
		}
	}
}
