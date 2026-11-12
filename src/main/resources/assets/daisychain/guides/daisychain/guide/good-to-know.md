---
navigation:
  title: Good to know
  icon: minecraft:spyglass
  position: 70
---

# Good to know

A few quirks that are nice to know about before they surprise you.

**The face you touch matters.** Blocks decide what each of their faces offers. A pull side on the side of a <ItemLink id="minecraft:furnace" /> takes its fuel, while one underneath takes the smelted goodies, following the same face rules
hoppers do.

**When everything's full,** a pull side starts checking less and less often (up to four times its normal wait) instead of knocking
on full doors all day. An empty source still gets checked at least once a second, and the moment anything moves, it's back to full
speed.

**Huge storages and amounts.** Keep, Fill to and Max stock need to count what's inside a block. Really big
storages (over 512 slots by default) don't get counted, so Daisychain leaves them alone rather than guessing. You'll see a red **!**
on the setting so you know why.

**Unloaded chunks.** If part of a network is in a chunk that isn't loaded, that part takes a little nap until it is. Daisychain never
loads chunks by itself.

**Perfect ties.** Two destinations at exactly the same distance on the same pipe? Nearest always picks the same one, in the order
bottom, top, north, south, west, east.

**Round robin** starts fresh from the nearest destination after the world reloads.

**One double chest as both source and destination** will keep shuffling items between its own two halves. Nothing gets lost, it's
just keeping itself busy. Two separate chests work better.
