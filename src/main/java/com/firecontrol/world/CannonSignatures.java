package com.firecontrol.world;

/**
 * Block fingerprints from "The Orbital Strike Cannon Mk.6.1.litematic" and "OSC Minimal Signature.litematic",
 * relative to the fire control origin. Format: "dx,dy,dz,block;...". The passcode composters are left out.
 */
final class CannonSignatures {
	private CannonSignatures() {
	}

	static final String COMMON =
		"8,6,-5,sticky_piston;16,40,14,white_concrete;19,11,-16,white_concrete;-4,13,13,white_concrete;-18,23,-2,white_concrete;2,24,-21,observer;" +
		"15,18,16,white_concrete;-14,1,-14,observer;-10,8,-14,white_concrete;-17,3,-13,white_concrete;-25,22,-1,activator_rail;-1,24,16,observer;" +
		"16,19,16,white_concrete;-9,-2,-17,white_concrete;-12,0,-2,white_concrete;-14,-5,-2,white_concrete;-2,24,-20,sticky_piston;0,1,-14,white_concrete;" +
		"-10,19,-10,white_concrete;-1,24,-25,barrel;11,8,2,slime_block;-12,24,5,white_concrete;-24,24,1,observer;-2,23,12,white_concrete;" +
		"6,4,-17,white_concrete;8,6,-11,white_concrete;-17,8,-2,activator_rail;2,24,8,white_concrete;-11,10,-1,white_concrete;-1,24,0,white_concrete;" +
		"-15,11,15,white_concrete;5,8,-12,white_concrete";

	static final String MK6 =
		"22,43,3,observer;-8,10,11,white_concrete;-17,-2,15,white_concrete;-10,15,-19,white_concrete;-24,16,-2,white_concrete;-10,2,-13,white_concrete;" +
		"27,37,3,white_concrete;12,40,9,white_concrete;25,39,-2,white_concrete;22,6,-12,activator_rail;22,37,-2,white_concrete;6,42,-23,white_concrete;" +
		"8,44,-24,sticky_piston;-14,12,18,white_concrete;0,11,-24,activator_rail;24,5,-22,white_concrete;-10,13,-11,activator_rail;26,10,-21,white_concrete;" +
		"-10,16,-11,observer;4,9,2,white_concrete;-8,6,7,observer;-16,6,-1,white_concrete;6,45,-25,white_concrete;23,9,-11,white_concrete;" +
		"-18,8,5,white_concrete;-11,9,3,activator_rail;-18,0,11,observer;0,10,15,white_concrete;21,45,-1,white_concrete;24,7,-6,white_concrete;" +
		"14,39,16,white_concrete;-6,9,5,activator_rail";

	static final String MS =
		"3,8,-1,white_concrete;-10,1,-13,white_concrete;5,15,-16,white_concrete;4,5,11,white_concrete;16,11,-19,white_concrete;-3,17,-18,white_concrete;" +
		"-2,12,-1,white_concrete;13,13,-6,white_concrete;11,9,13,white_concrete;-17,18,-18,hopper;12,16,-12,activator_rail;-10,10,-9,white_concrete;" +
		"11,40,12,white_concrete;-17,10,-3,white_concrete;14,17,-24,activator_rail;16,42,15,white_concrete;7,43,5,white_concrete;-23,17,-2,white_concrete;" +
		"9,14,-17,white_concrete;-9,17,-15,white_concrete;16,42,16,white_concrete;-8,10,0,white_concrete;13,27,11,white_concrete;2,11,18,white_concrete;" +
		"-1,13,13,white_concrete;11,16,12,white_concrete;-16,11,6,white_concrete;-17,17,20,obsidian;-13,17,-18,white_concrete;-11,7,-6,white_concrete;" +
		"12,40,16,white_concrete;12,16,-6,white_concrete";
}
