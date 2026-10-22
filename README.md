<p align="center"><img src="src/main/resources/assets/firecontrol/icon.png" width="128" alt="FireControl icon"></p>

# FireControl

A client-side Fabric mod for running **CubicMetre's Orbital Strike Cannons** (OSC Mk6, OSC MS and an experimental SFC mode) and **Danda420's [360 Precision Ender Pearl Cannon](https://github.com/Danda420/360_Precision_EnderPearlCannon/tree/v3)** (V3) from inside the game.

Based on CraftedEgg's [Orbital Strike Cannon Helper](https://modrinth.com/mod/orbital-strike-cannon-helper).

## Features

- Finds your control panel and cannon on its own and tells the Mk6 and MS apart
- Reads the cannon's passcode from its composter key
- Target and payload in one command, or from the menu
- Highlights the buttons you need to press, or presses them for you
- Impact countdown on the HUD after you fire
- Works out the ground height when you only have X and Z
- Loads the pearl cannon for you
- Presets for multiple panels and cannons

## Requirements

- Minecraft 1.21.11, 26.1.2, 26.2 or 26.3
- Fabric Loader 0.19.5+ and Fabric API
- Java 21 for 1.21.11, Java 25 for 26.x

It's client only, so it works on any server.

## Installing

Grab the jar for your Minecraft version from [Releases](../../releases) and drop it in your `mods` folder together with Fabric API.

## Orbital Strike Cannon

1. **Pair.** Run `/firecontrol pair` at your cannon and at your control panel, in any order. They can be any distance apart. If the cannon is loaded while you're at the panel, one run does both.
2. **Aim.** Stand on the target and run `/firecontrol target`, then click **[Set payload]** in chat. You can also type it all at once: `/firecontrol target 1200 70 -3400 nuke 12` (`stab <depth>` for a stab, `slot <n>` for a Mk6 magazine slot, `~` works too).
3. **Enter the settings.** Press the highlighted buttons, or stand at the panel and click **[Enter them for me]**. If something interrupts it, click **[Resume]**.
4. **Fire.** Once the panel matches, the Input Settings button lights up cyan. Press it and the countdown starts.

Settings typed in by hand (for example from the website) work too. When you press Input Settings the mod reads the panel and starts the countdown for whatever is on it.

The passcode is read when you pair at the cannon. If you used `pair at` from far away, it's read the next time the cannon is loaded. You can also set it under **More...** in the menu.

## Pearl cannon

1. Run `/firecontrol pair` in front of the cannon's buttons.
2. Run `/firecontrol target <x> <z>`, or `/firecontrol target` while standing on the spot. Overworld coordinates are converted for a cannon in the Nether.
3. Click **[Load it for me]**. It switches the bulbs and moves the right number of counter items into the INPUT, one axis at a time. Big loads go in batches of 64 items per free inventory slot.
4. When the hole opens, step in and throw your pearl straight up.

The buttons show how many charges are loaded and how many are needed. If the cannon still has charges from another shot, the mod tells you where they'd send you instead of loading on top of them.

If you walk off, open a menu or get hurt, loading pauses and **[Resume]** carries on. The cannon keeps working on what it already has, and the time left keeps counting while it does.

On long shots the pearl has to wait for the server to load the chunks on its way, so the landing countdown includes that. It learns how fast your server is from each landing, and shows **Landing…** until you're actually there.

## Targets without a Y

Leave the Y out (`/firecontrol target 1200 -3400`) and the mod uses the ground height there:

- in loaded chunks it uses the actual blocks
- in singleplayer it asks the world generator
- on a server it needs the world seed, which you can enter under **More... > World seed**. One seed covers the Overworld, the Nether and the End, and it's checked against the terrain around you in whichever one you're in.

The Nether has several floors: a pearl lands on the one closest to the cannon's own height (the roof for a cannon on top of it), and a strike from the sky lands on the roof. Worked-out heights are the bare terrain without builds, trees or Nether fungi. Worlds with custom terrain need the Y typed in.

## Commands

| Command | |
|---|---|
| `/firecontrol` | Open the menu |
| `/firecontrol pair` | Pair a panel and a cannon (run it at both) |
| `/firecontrol pair at <x> <y> <z> <mk6\|ms\|sfc>` | Pair a cannon by its origin without going there |
| `/firecontrol pair cancel` | Cancel a half-finished pairing |
| `/firecontrol target [<x> [<y>] <z>] [nuke\|stab] [size] [slot <n>]` | Set the target (your position if you leave out the coordinates) |
| `/firecontrol InputSettings` | Enter the settings on the panel, or load the pearl cannon |

Presets, the passcode, the world seed, the overlay and the input speed are all under **More...** in the menu.

## SFC

The SFC maths is done, but there's no official SFC control panel yet, so panel detection is a best guess. Pair the cannon with `/firecontrol pair at <x> <y> <z> sfc`.

## Building

```
gradlew build
```

This builds for 26.1.2. For the other versions:

```
gradlew build -Pminecraft_version=26.2 -Pfabric_api_version=0.161.0+26.2
gradlew build -Pminecraft_version=26.3 -Pfabric_api_version=0.164.0+26.3
gradlew build -Pminecraft_version=1.21.11 -Pfabric_api_version=0.141.6+1.21.11
```

The jar ends up in `build/libs/`.

## Credits

- **CubicMetre** for the Orbital Strike Cannon and the [fire control maths](https://github.com/cubicmetre/osc-fire-control)
- **CraftedEgg** for the original OSC Helper
- **Danda420** for the [360 Precision Ender Pearl Cannon](https://github.com/Danda420/360_Precision_EnderPearlCannon) and its [calculator](https://danda420.github.io/360_Precision_EnderPearlCannon/)

## License

GPL-3.0-or-later, since the fire control maths is ported from osc-fire-control (GPL-3.0).
