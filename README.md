# Daisychain

Fast, lag-friendly pipes for items, fluids, energy, redstone signals and Mekanism chemicals, built for big modpacks and busy servers.

Minecraft 1.21.1 · NeoForge 21.1.49+ · MIT license · version 1.6.0

## What it adds

- **Item, Fluid, Energy, Universal, Redstone and Gas pipes.** The Universal pipe carries items, fluids and energy at once (and Mekanism chemicals), each at its own speed. The Redstone pipe carries an on/off signal with no loss. The Gas pipe (with Mekanism) carries gases, infusion types, pigments and slurries.
- **Pull and insert sides.** Sneak and right-click a side with the Bloom Wrench (or any other wrench) to make it pull. Everything else on the network is a destination.
- **Send to** Near, Far or Robin (an even split), with a priority on every destination. Energy always splits evenly.
- **Send every:** a pull side can send less often than its upgrade allows, down to once every 2 minutes.
- **Filters:** 54 slots per side, matching by item, item + data, tag or mod, as a whitelist or blacklist; fluid and chemical filters too. JEI drag-and-drop is supported.
- **Stock control:** Batch size, Keep in source, Fill destination to and Max stock, per side or per filter entry.
- **Redstone control** on pull and insert sides.
- **Five upgrades** (iron to nether star) and a **Settings Copier** that pastes onto one side or a whole network, with undo.
- **Facades:** one block + one item pipe makes 8. A covered pipe looks like its block and works as before; hold the wrench to see the pipes inside. Glass pane + item pipe makes transparent facades.
- **In-game guide** (with [GuideME](https://modrinth.com/mod/guideme) installed): hold G over any Daisychain item to open its page.

## Built to stay light

- Pipe blocks never tick. Each connected network is one object, rebuilt only when a pipe or connection changes.
- Neighbor inventories are cached, idle sides sleep, and chunks are never loaded by pipes.
- Every transfer simulates first, then moves only what both sides accept, so nothing is duplicated or lost.
- Each dimension has a per-tick work budget (inventory calls and milliseconds). When it runs out, pipes wait a tick instead of lagging the server.
- Every speed and limit is in `config/daisychain-server.toml`.

## Compatibility

Daisychain uses NeoForge's standard item, fluid and energy capabilities, so it works with any mod that uses them. [GuideME](https://modrinth.com/mod/guideme) is optional: install it to get the in-game guide and its guide book. JEI, Jade and FTB Ultimine are optional too. Mekanism is optional: with it installed you get the gas pipe, which uses Mekanism's own chemical capability.

## Links

- Download: [Modrinth](https://modrinth.com/mod/daisychain)
- Bugs and ideas: [GitHub issues](https://github.com/zerobitmods/Daisychain/issues)

## Installing

Put the Daisychain jar in the `mods` folder of the server and of every client. For the in-game guide, add GuideME in the same places.

## Building from source

You need Java 21.

```sh
./gradlew build       # the jar is written to build/libs/daisychain-<version>.jar
./gradlew runClient   # starts a development game with the mod loaded
```

## Commands

Operators (permission level 2) can use `/daisychain stats` to see how much work pipes are doing, `/daisychain lag [seconds]` to find the networks that cost the most, `/daisychain network <x y z>` to inspect a network and `/daisychain version` to check the version. Every player can use `/daisychain undo` and `/daisychain redo` for the Settings Copier.

## License and credits

Code: MIT, see `LICENSE.txt`. Made by zerobit.
