---
navigation:
  title: Redstone pipe
  icon: daisychain:redstone_pipe
  position: 27
item_ids:
  - daisychain:redstone_pipe
---

# Redstone pipe

Redstone dust gets tired after 15 blocks. The <ItemLink id="daisychain:redstone_pipe" /> doesn't: a signal goes in one end and
comes out the other at full strength, however long the line is.

It's on or off. If any input of a line is powered, the whole line is on, and every output gives full power. Its middle line
glows bright red while it's on and goes dark when it's off, so you can tell from across a cave.

## Inputs and outputs

- **Inputs** are the sides with a pull plate. They read the signal of whatever they touch.
- **Outputs** are the sides with an insert cap. They power the block they touch strongly, like a repeater, so redstone on the
  far side of that block reacts too.
- The pipe reaches out on its own to redstone parts: levers, buttons, dust, repeaters, lamps, pistons, doors and friends. Sides
  that touch something that only *gives* a signal (a lever, a button, a redstone block, a daylight detector, an observer...)
  start as inputs; everything else starts as an output.
- Sneak and use the [Bloom Wrench](bloom-wrench.md) on a side to change it: Output, Input or off. Next to a plain block (stone,
  wool, anything) the wrench gives you Output and Input too.

## Driving your other pipes

Put a redstone pipe next to an item, fluid, energy or universal pipe and it gives that pipe an output. Set the pipe's side to
*On with signal* or *Off with signal* ([Side settings](side-settings.md)) and your whole factory can follow one lever.

## Good to know

- A line that feeds its own input (an output into dust that runs back to an input) holds itself on, even after you take the
  lever away. Handy as a memory cell; to switch it off, break the loop.
- A signal from a part of the line in an unloaded chunk doesn't count until that chunk is loaded again.
- It has no settings screen and takes no upgrades. A line sits idle until one of its inputs changes; only then is it worked
  out again.

<RecipeFor id="daisychain:redstone_pipe" />
