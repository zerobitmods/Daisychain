---
navigation:
  title: Side settings
  icon: minecraft:comparator
  position: 40
---

# Side settings

Here's where things get fun. Every pull and insert side has its own settings. Right-click a side's arm with an empty hand to open
them (or with the <ItemLink id="daisychain:bloom_wrench" />, or pretty much anything that isn't a pipe, an upgrade or the <ItemLink id="daisychain:settings_copier" />).

The tag at the top says whether you're looking at a **PULL** side or an **INSERT** side, next to the block it's touching.

## Status

Pull sides have a small dot at the top right that tells you how their last turn went. Hover over it for the details.

- **Green, Moving:** things are on their way.
- **Amber, Waiting:** there's something to move, but nothing took it. The destinations may be full, filtered or at a
  limit, or Keep is holding it back.
- **Amber, Nowhere to send:** the network has no insert sides yet.
- **Grey, Nothing to pull:** the block here is empty, or has nothing the filter lets through. **Nothing to pull from**
  means there's no block with an inventory, tank or battery here at all.
- **Red, Off by redstone** or **Switched off:** the Redstone setting or the power key is keeping it off.
- **Violet, Waiting for a pulse:** set to Once per pulse and ready for the next one.

With Jade installed, the same status shows when you look at a pull side.

## Simple and Advanced

There are two views, and the pipe remembers which one you picked:

- **Simple** shows one row of filter entries and the keys you'll reach for most.
- **Advanced** shows three rows of entries (with a second page for even more) and opens a page on the right with everything
  else.

The sliders key at the end of the key row flips between them. If something only Advanced can show is in use, that key gets a small
dot, so nothing hides from you.

## The key row

Right under the filter, from left to right:

- **Upgrade slot** (pull sides): drop an upgrade in or take it out. Hover over it to see how fast this side moves things.
- **Page keys** (Advanced): switch between the two pages of filter entries.
- **Whitelist / Blacklist:** the check mark lets only listed things through, and the crossed circle lets everything through
  except what's listed.
- **Redstone** (pull sides): Always, On with signal, Off with signal, or Once per pulse.
- **Priority** (insert sides): Low, Normal or High. Need an exact number? Advanced view has it.

Left-click a key for the next option, right-click for the previous one. Hover any key and its tooltip tells you what it's set to.

## Where things go: Send to

A pull side gets to choose the order it fills things up in. You'll find it at the top of the page on the right (Advanced view):

- **Near:** the closest spot along the pipes gets served first. It's the default, and it's what you'll want most of the time.
- **Far:** the other way around. Lovely for filling a long storage wall from the far end.
- **Robin:** short for round robin. It shares things out evenly, so everyone gets a turn.

Out of the box, a side won't send things back into the block it just pulled from (that would be a bit silly). **Send back** right
below lets the side send things back into the block they came from, for the rare setup that needs it.

## Who goes first: Priority

Insert sides have a **priority**, anywhere from -999 to 999. Higher numbers get filled first, no matter how far away they are. When
two priorities tie, the pull side's 'Send to' breaks the tie. The key row's Low, Normal and High are -10, 0 and 10.

## Filters

Filters decide what a side pulls, or what it's happy to accept. They're ghost slots, so nothing you put in them gets used up.

- To add something, click an empty filter slot while holding it on your cursor, or shift-click it in your inventory. An empty
  filter lets everything through.
- Click an entry to open its own settings page. There you choose how it matches: **Item**, **+Data** (the item including its
  data, like enchantments or damage), **Tag** (pick one of its tags, or search for any tag) or **Mod** (everything from that mod).
- Shift-click an entry to remove it. That's the only way to remove one, so a carefully built filter is safe from stray clicks.
  **Clear filter** at the bottom of Advanced view empties the whole thing, after a second click to make sure.
- Got JEI? Drag items or fluids straight from it into the filter, or shift-click them in JEI's list.

Little badges on an entry tell you how it matches: **D** for item + data, **#** for a tag, **M** for a whole mod. A **+** in the
bottom-right corner means the entry has its own amounts.

The <ItemLink id="daisychain:energy_pipe" /> skips filters. Energy is energy, after all.

## Amounts

Four numbers for when you want to get precise, all in Advanced view:

| Setting | Where | What it does |
| --- | --- | --- |
| Batch | Pull | The most it moves at once (never more than the upgrade allows) |
| Keep | Pull | Always leaves at least this many in the source |
| Fill to | Pull | Stops once a destination has this many |
| Max stock | Insert | This block never holds more than this many |

Items count in items, fluids in mB, and energy in FE.

Any filter entry can have its own amounts too. Open the entry in Advanced and set them right there. A dim number means the entry
just follows the side's settings.

For example: put **Keep: 64** on a pull side from your storage, and it'll happily feed the network without ever taking your last
stack.

## Redstone

- **Pull sides:** Always, On with signal, Off with signal, or Once per pulse (in the key row).
- **Insert sides:** Always, On with signal, or Off with signal (in Advanced view). Perfect for a block that should only take
  deliveries while a <ItemLink id="minecraft:lever" /> is on.

## Number fields

- Left-click goes up and right-click goes down. Hold Shift for bigger steps.
- Alt (Option on a Mac) jumps straight to the lowest or highest value.
- The mouse wheel works too.
- Ctrl-click (Cmd-click on a Mac) to type a number in. Simple math works, so `64*9` is also fine.

## Universal Pipes

A <ItemLink id="daisychain:universal_pipe" /> side handles items, fluids and energy, each with its own filter and amounts. Pick one
with the tabs on the left (a box, a drop and a lightning bolt). The power key in the key row switches the one you're looking at
on or off for this side, and a switched-off tab gets a red slash. Redstone, Send to, priority and the upgrade are shared.

## All six sides at once

Click the middle of a pipe (or the cube tab) to see every side of it. The squares are laid out around you: **F**ront, **L**eft,
**R**ight, **B**ack, **U**p and **D**own, each showing the block it touches. The border color tells you what each side does:

- **Purple:** pull. **Teal:** insert. **Light gray:** linked to the next pipe. **Red:** off.

Click a square to select it, then pick **Pull**, **Insert**, **Link** or **Off** underneath. The sliders key opens that side's
settings. Prefer to see it for real? The **3D** key shows the pipe and its neighbors in 3D: drag to turn it, scroll to zoom, and
click a side to select it.
