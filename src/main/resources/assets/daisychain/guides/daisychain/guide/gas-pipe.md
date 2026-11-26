---
navigation:
  title: Gas pipe
  icon: daisychain:gas_pipe
  position: 28
item_ids:
  - daisychain:gas_pipe
---

# Gas pipe

The <ItemLink id="daisychain:gas_pipe" /> carries Mekanism's chemicals: gases like hydrogen and oxygen, plus infusion,
pigments and slurries. It only turns up when Mekanism is installed; without Mekanism there is nothing for it to carry.

It works like a fluid pipe. Put a pull side on a block that holds chemicals and the line hands them to every other block it
touches that takes them. Priority, Nearest / Farthest / Round robin, Batch, Keep in source, Fill destination to and Max stock
all work the way they do for fluids, counted in mB ([Side settings](side-settings.md)). The same
[upgrades](upgrades.md) make it faster.

Gas sides have filters too, so one line can carry hydrogen to one machine and oxygen to the next. Click a filter slot with a
filled chemical tank, or drag the chemical in from JEI, and pick whether the entry matches that chemical, a tag or a whole mod.

The universal pipe carries chemicals as well, on its own Chemicals tab (the flask). Universal pipes that were already
placed before your world got this update start with chemicals switched off on every side, so nothing in a working build
changes until you turn them on.

## Recipe

Six steel ingots, two glass and a Basic Control Circuit make 8 pipes: steel along the top and bottom, glass on both sides of
the circuit in the middle. Steel and basic circuits from any mod work.

## Good to know

- Mekanism machines hand out and take chemicals only on the sides their side configuration allows. If a pipe won't reach a
  machine, that screen is the first place to look.
- If a Mekanism update ever changes something Daisychain relies on, gas pipes stop carrying anything until the game
  restarts, the server log says why, and everything else in Daisychain keeps working.
- Server owners can switch gas pipes off or change their speed in the server config (`chemicals`, `chemical_upgrades`).
