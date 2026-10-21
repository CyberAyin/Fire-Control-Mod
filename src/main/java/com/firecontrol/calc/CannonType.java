package com.firecontrol.calc;

import java.util.Locale;

// CubicMetre's Orbital Strike Cannons (set from a control panel) and Danda420's 360 pearl cannon
public enum CannonType {
	MK6("mk6", "OSC Mk6"),
	MS("ms", "OSC MS"),
	SFC("sfc", "OSC SFC"),
	PEARL("pearl", "Pearl Cannon");

	public final String id;
	public final String displayName;

	CannonType(String id, String displayName) {
		this.id = id;
		this.displayName = displayName;
	}

	// Mk6 and MS share the 13x5 copper bulb encoding, SFC uses two 15-bit channels
	public boolean usesGridPanel() {
		return this == MK6 || this == MS;
	}

	public boolean isPearl() {
		return this == PEARL;
	}

	public boolean hasPasscode() {
		return this == MK6 || this == MS;
	}

	public static CannonType byId(String id) {
		String key = id.toLowerCase(Locale.ROOT);
		for (CannonType t : values()) {
			if (t.id.equals(key) || t.name().equalsIgnoreCase(key)) return t;
		}
		return null;
	}
}
