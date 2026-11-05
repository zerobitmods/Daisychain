# Daisychain

Fast, lag-friendly pipes for items, fluids and energy, built for big modpacks and busy servers.

Minecraft 1.20.1 · Forge 47.2.0+ (this branch; 1.21.1 NeoForge is on main) · MIT license · version 1.0.0

## What it adds

- **Item, Fluid, Energy and Universal pipes.** The Universal pipe carries all three at once, each at its own speed.
- **Pull and insert sides.** Sneak and right-click a side with the Bloom Wrench (or any other wrench) to make it pull. Everything else on the network is a destination.
- **Send to** Nearest, Farthest or Round robin, with a priority on every destination.
- **Filters:** 54 slots per side, matching by item, item + data, tag or mod, as a whitelist or blacklist. JEI drag-and-drop is supported.
- **Stock control:** Batch size, Keep in source, Fill destination to and Max stock, per side or per filter entry.
- **Redstone control** on pull and insert sides.
- **Five upgrades** (iron to nether star) and a **Settings Copier**.
- **In-game guide:** hold G over any Daisychain item to open its page.

## Built to stay light

- Pipe blocks never tick. Each connected network is one object, rebuilt only when a pipe or connection changes.
- Neighbor inventories are cached, idle sides sleep, and chunks are never loaded by pipes.
- Every transfer simulates first, then moves only what both sides accept, so nothing is duplicated or lost.
- Each dimension has a per-tick work budget (inventory calls and milliseconds). When it runs out, pipes wait a tick instead of lagging the server.
- Every speed and limit is in `config/daisychain-server.toml`.

## Compatibility

Daisychain uses only NeoForge's standard item, fluid and energy capabilities, so it works with any mod that uses them. It requires [GuideME](https://modrinth.com/mod/guideme), which runs the in-game guide. JEI is optional.

## Links

- Download: [Modrinth](https://modrinth.com/mod/daisychain)
- Bugs and ideas: [GitHub issues](https://github.com/zerobitmods/Daisychain/issues)

## Installing

Put the Daisychain and GuideME jars in the `mods` folder of the server and of every client.

## Building from source

You need Java 21.

```sh
./gradlew build       # the jar is written to build/libs/daisychain-<version>.jar
./gradlew runClient   # starts a development game with the mod loaded
```

## Commands

Operators (permission level 2) can use `/daisychain stats` to see how much work pipes are doing, `/daisychain network <x y z>` to inspect a network and `/daisychain version` to check the version.

## License and credits

Code: MIT, see `LICENSE.txt`. Made by zerobit.

Font: Flowline, a bitmap font based on VT323 © 2011 The VT323 Project Authors, under the SIL Open Font License 1.1 (the license text ships in `assets/daisychain/font/`). The screens use Minecraft's own font.
