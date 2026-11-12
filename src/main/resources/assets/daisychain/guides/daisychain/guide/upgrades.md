---
navigation:
  title: Upgrades
  icon: daisychain:diamond_upgrade
  position: 50
item_ids:
  - daisychain:iron_upgrade
  - daisychain:gold_upgrade
  - daisychain:diamond_upgrade
  - daisychain:netherite_upgrade
  - daisychain:nether_star_upgrade
---

# Upgrades

Good news first: every feature works without upgrades. Upgrades just make a pull side **faster**. The same five work on item,
fluid and universal pipes, and the ring around the pull cap changes color so you can tell the tier at a glance. Energy pipes are
the exception: they get faster by crafting a better pipe instead (see [Energy pipe tiers](energy-pipes.md)), and the energy column
below is their speed per tier.

| Tier | Items per tick (per second) | Fluids per tick (per second) | Energy per tick (per second) |
| --- | --- | --- | --- |
| None | 0.4 (8) | 500 mB (10 buckets) | 20,000 FE (400,000 FE) |
| <ItemLink id="daisychain:iron_upgrade" /> | 1.6 (32) | 2,000 mB (40 buckets) | 100,000 FE (2 million FE) |
| <ItemLink id="daisychain:gold_upgrade" /> | 6.4 (128) | 8,000 mB (160 buckets) | 500,000 FE (10 million FE) |
| <ItemLink id="daisychain:diamond_upgrade" /> | 64 (1,280) | 40,000 mB (800 buckets) | 2,500,000 FE (50 million FE) |
| <ItemLink id="daisychain:netherite_upgrade" /> | 512 (10,240) | 160,000 mB (3,200 buckets) | 16,000,000 FE (320 million FE) |
| <ItemLink id="daisychain:nether_star_upgrade" /> | 10,000 (200,000) | 1,280,000 mB (25,600 buckets) | 2,147,483,647 FE (about 43 billion FE) |

The slower tiers move things in batches, so their per-tick number is an average. With no upgrade, an <ItemLink id="daisychain:item_pipe" /> moves 8 items once a second, and a <ItemLink id="daisychain:fluid_pipe" /> moves 5,000 mB (5 buckets) every
half second.

Yes, that last number is real. These are the defaults, so your server or modpack might have tuned them a little.

On a <ItemLink id="daisychain:universal_pipe" />, one upgrade speeds up items, fluids and energy all at once.

Got an upgrade left in an energy pipe from before energy pipes had tiers? It does nothing there. Take it out the usual way.

## Putting them in

- **One side:** right-click a pull side with an upgrade. If there's a lower tier in there, they swap and you get the old one
  back. Upgrades only go up: to put in a lower tier, take the better one out first.
- **A whole network at once:** sneak and right-click any pipe while holding a stack. Every pull side on that network with a lower
  tier gets one, until your stack runs out. Very satisfying.

## Taking them out

Sneak and right-click the pull side with empty hands, or take it out of the upgrade slot in the side's settings.

You'll never lose one, either. Break or blow up a pipe and its upgrades drop right out.

## Switching a side away from Pull

Changed a pull side to Insert or Off, on purpose or by accident? Its upgrade stays put, "parked". It does nothing while the side
isn't pulling, but switch it back to Pull and it's working again, along with the side's filter and amounts. You can spot a parked
upgrade by the colored groove on an insert side, the faded slot in its settings, or the little colored mark in the Pipe sides
screen. Take it out the usual way if you want it back. If you place a pipe where that side points, the side joins the two pipes
and the upgrade pops out, since a pipe-to-pipe side can never pull.

## Recipes

Each tier is made from the one before it, with that tier's material in the corners.

<RecipeFor id="daisychain:iron_upgrade" />

<RecipeFor id="daisychain:gold_upgrade" />

<RecipeFor id="daisychain:diamond_upgrade" />

<RecipeFor id="daisychain:netherite_upgrade" />

<RecipeFor id="daisychain:nether_star_upgrade" />
