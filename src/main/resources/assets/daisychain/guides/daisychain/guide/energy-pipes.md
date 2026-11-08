---
navigation:
  title: Energy pipe tiers
  icon: daisychain:diamond_energy_pipe
  position: 25
item_ids:
  - daisychain:iron_energy_pipe
  - daisychain:gold_energy_pipe
  - daisychain:diamond_energy_pipe
  - daisychain:netherite_energy_pipe
  - daisychain:nether_star_energy_pipe
---

# Energy pipe tiers

Energy pipes don't take upgrades. To move more power, you craft a faster pipe: eight pipes of one tier around the matching upgrade
make eight pipes of the next. The band along the pipe and the ring on its pull caps show the tier.

| Pipe | Up to, per tick |
| --- | --- |
| <ItemLink id="daisychain:energy_pipe" /> | 20,000 FE |
| <ItemLink id="daisychain:iron_energy_pipe" /> | 100,000 FE |
| <ItemLink id="daisychain:gold_energy_pipe" /> | 500,000 FE |
| <ItemLink id="daisychain:diamond_energy_pipe" /> | 2,500,000 FE |
| <ItemLink id="daisychain:netherite_energy_pipe" /> | 16,000,000 FE |
| <ItemLink id="daisychain:nether_star_energy_pipe" /> | 2,147,483,647 FE (all of it) |

These are the defaults; your server or modpack might have tuned them.

## Mixing tiers

Energy pipes of every tier connect to each other and make one network. Energy going to a machine moves at the speed of the
**slowest pipe on its way there**:

- A cheap branch only slows the machines on that branch. The main line stays fast.
- One slow pipe in the middle of a fast line slows everything past it.
- Each pull side sends at most what its fastest route can carry, and each machine gets at most what the route to it can carry.

So you can upgrade a long line one stretch at a time and it keeps working the whole way.

## Blocks that push power

Some blocks never hand their power over when a pipe asks for it. They push it out on their own instead: a **Flux Point**
is the classic, and plenty of generators and cables work the same way. Point a **pull side** at one and it catches what
gets pushed in, then sends it straight on to your machines. That works on energy pipes and universal pipes.

The pipe still keeps nothing. Power only goes in when a machine down the line wants it, at the pull side's speed, and the
rest stays in the block that pushed it. Priority, Send to and Fill to work as usual. A side set to **once per pulse** won't
take pushed power.

## Swapping in place

Hold a faster energy pipe, sneak and right-click a slower one: it's swapped on the spot. Its sides, pull settings and
filters all stay, and you get the old pipe back. It only goes up a tier, never down, so a sneak-click with a slower or equal
pipe just places it next to the one you clicked, like any block.

Got **FTB Ultimine**? Hold the Ultimine key and right-click with the faster pipe instead: every energy pipe in the
selection is swapped at once, one pipe from your stack each, until you run out.

## Recipes

<RecipeFor id="daisychain:iron_energy_pipe" />

<RecipeFor id="daisychain:gold_energy_pipe" />

<RecipeFor id="daisychain:diamond_energy_pipe" />

<RecipeFor id="daisychain:netherite_energy_pipe" />

<RecipeFor id="daisychain:nether_star_energy_pipe" />
