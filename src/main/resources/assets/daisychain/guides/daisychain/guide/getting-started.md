---
navigation:
  title: Getting started
  icon: minecraft:chest
  position: 10
---

# Getting started

Let's build our first network! A network is any group of connected pipes of the same type. We'll send some <ItemLink id="minecraft:cobblestone" /> from one chest to another, because every great logistics empire starts with a pile of cobble.

1. Place two <ItemLink id="minecraft:chest" />s a few blocks apart.
2. Connect them with a line of <ItemLink id="daisychain:item_pipe" />s, touching both chests.
3. Grab a <ItemLink id="daisychain:bloom_wrench" /> and sneak and right-click the pipe right where it meets the first chest. That end grows a wide cap
   with a ring around it. Say hi to your first **pull side**!
4. Toss some cobblestone into the first chest and watch it pop out in the second.

Nice work, you've got a network running!

## Pull and insert

Everywhere a pipe touches a block, it has a **side**, and each side has a mode:

- **Pull:** grabs things from the block it's touching and sends them down the line. These are the hard workers; nothing moves
  without one.
- **Insert:** the plain arm with a little groove. Things get delivered here. Any side touching a block that can hold things (an
  inventory, tank or battery) starts out as an insert side.
- **Off:** not connected at all. Great for a pipe that needs to sneak past a chest without bothering it.

Put another way: **pull sides send, and everything else on the line is a place things can go.** Hook a pull side to your mob farm,
and every chest down the line is ready to catch the loot. Need some more options? [Side settings](side-settings.md) has you covered.

## Opening a side's settings

Right-click a pipe with an empty hand (or holding the <ItemLink id="daisychain:bloom_wrench" />, or pretty much anything that isn't a pipe, an upgrade or the <ItemLink id="daisychain:settings_copier" />):

- Click the **arm** of a pull or insert side to open that side's settings.
- Click the **middle** of the pipe to see all six sides at once.

Want to place a block against a pipe? Sneak while you place it, same as with a chest. Pipes go on pipes with a plain click,
except on a pull side's cap: a plain click there opens its settings, so you never lose a pull side by accident. Sneak to place a
pipe on a pull cap.

<RecipeFor id="daisychain:item_pipe" />
