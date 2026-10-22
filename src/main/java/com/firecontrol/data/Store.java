package com.firecontrol.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// config/firecontrol/pairings.json
public final class Store {
	private static final Logger LOG = LoggerFactory.getLogger("firecontrol");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("firecontrol").resolve("pairings.json");

	private static Data data;

	public static final class Data {
		public Map<String, List<Pairing>> worlds = new LinkedHashMap<>();
		public Map<String, String> selected = new LinkedHashMap<>();
		public Map<String, Pairing> pending = new LinkedHashMap<>();
		// ticks between presses, 1 = fastest
		public int pressInterval = 1;
		public Map<String, Seed> seeds = new LinkedHashMap<>();
		// per server: game ticks a flying pearl waits for each chunk it has to load, learned from landings
		public Map<String, Double> chunkWait = new LinkedHashMap<>();
		// per server: game ticks a pearl cannon takes per charge, learned from the batches it has run
		public Map<String, Double> loadRate = new LinkedHashMap<>();
	}

	public static final class Seed {
		public String text;
		// overworld terrain: "overworld", "large_biomes" or "amplified". null until checked there ("overworld" is assumed).
		public String terrain;
		// null = not checked yet
		public Boolean matched;
	}

	private Store() {
	}

	public static Data data() {
		if (data == null) load();
		return data;
	}

	private static void load() {
		data = new Data();
		if (!Files.exists(FILE)) return;
		try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			Data read = GSON.fromJson(r, Data.class);
			if (read != null) data = read;
			if (data.worlds == null) data.worlds = new LinkedHashMap<>();
			if (data.selected == null) data.selected = new LinkedHashMap<>();
			if (data.pending == null) data.pending = new LinkedHashMap<>();
			if (data.seeds == null) data.seeds = new LinkedHashMap<>();
			if (data.chunkWait == null) data.chunkWait = new LinkedHashMap<>();
			if (data.loadRate == null) data.loadRate = new LinkedHashMap<>();
		} catch (Exception e) {
			LOG.error("[FireControl] Could not read {}", FILE, e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				GSON.toJson(data(), w);
			}
		} catch (IOException e) {
			LOG.error("[FireControl] Could not save {}", FILE, e);
		}
	}

	public static String worldKey() {
		Minecraft mc = Minecraft.getInstance();
		ServerData server = mc.getCurrentServer();
		if (server != null) return "server:" + server.ip;
		if (mc.getSingleplayerServer() != null) return "local:" + mc.getSingleplayerServer().getWorldData().getLevelName();
		return "unknown";
	}

	public static List<Pairing> pairings() {
		return data().worlds.computeIfAbsent(worldKey(), k -> new ArrayList<>());
	}

	public static Pairing pending() {
		return data().pending.get(worldKey());
	}

	public static Seed seed() {
		return data().seeds.get(worldKey());
	}

	public static Seed setSeed(String text) {
		String t = text == null ? "" : text.trim();
		Seed s = seed();
		if (t.isEmpty()) {
			data().seeds.remove(worldKey());
			s = null;
		} else if (s == null || !t.equals(s.text)) {
			s = new Seed();
			s.text = t;
			data().seeds.put(worldKey(), s);
		}
		save();
		return s;
	}

	public static void setPending(Pairing half) {
		if (half == null) data().pending.remove(worldKey());
		else data().pending.put(worldKey(), half);
		save();
	}
}
