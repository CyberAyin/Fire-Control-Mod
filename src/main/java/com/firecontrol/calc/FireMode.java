package com.firecontrol.calc;

import java.util.Locale;

public enum FireMode {
	NUKE("nuke"),
	STAB("stab");

	public final String id;

	FireMode(String id) {
		this.id = id;
	}

	public static FireMode byId(String id) {
		String key = id.toLowerCase(Locale.ROOT);
		for (FireMode m : values()) {
			if (m.id.equals(key)) return m;
		}
		return null;
	}
}
