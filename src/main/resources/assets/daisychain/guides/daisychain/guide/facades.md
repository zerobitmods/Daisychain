---
navigation:
  title: Facades
  icon: daisychain:facade
  icon_nbt: '{"daisychain:facade":{Name:"minecraft:oak_planks"}}'
  position: 35
item_ids:
  - daisychain:facade
---

# Facades

Pipes are wonderful. Pipes running across your lovely oak hallway are less wonderful. Facades let a pipe dress up as almost
any block, while it keeps working exactly as before.

## Making them

Put **one block and one item pipe** in a crafting grid, any arrangement: you get **8 facades** of that block. Any full block
works, glass included. Blocks with odd shapes (stairs, slabs, fences) or a block entity (chests, furnaces, barrels) don't.

## Putting them on

- **Right-click** a pipe with a facade: covered.
- **Right-click a covered pipe** with a different facade: swapped, and the old one comes back to you.
- **Sneak + right-click with an empty hand**: the cover pops off into your hand (or onto a matching stack). If that side has
  an upgrade, the cover comes off first and the upgrade on the next click.

## Covering a whole line

Sneak while holding facades and look at a pipe: an outline shows every linked pipe that would get covered, with a count.
**Sneak + right-click** covers them all, one facade each. Pipes in claims you can't build in are skipped.

## Living with covered pipes

- A covered pipe is a full block: walk on it, build walls around it. Glass covers let light through. (Transparent
  ones play by their own rules, see below.)
- Every side still connects, pulls and inserts. With an empty hand, click the face of the side you mean. With a Settings
  Copier or an upgrade you aim at the pipe inside, so click the arm itself.
- **Hold the Bloom Wrench** and every covered pipe nearby turns see-through, so you can see (and fix) what's inside.
- Breaking the pipe drops the pipe and its cover. Swapping a pipe's tier keeps the cover.

## Transparent facades

**Glass pane + item pipe → 8 transparent facades.** A pipe wearing one isn't there: you walk right through it, and clicks
land on whatever is behind it. Pick up a wrench and it shows as a cyan hologram. To grab the cover itself, sneak or hold a
facade (sneak + empty hand takes it off). Nothing can stand on it either, so torches and redstone need a real block. Perfect
for that "how does this even work" factory. Server owners can turn them off; pipes already wearing one keep it.
