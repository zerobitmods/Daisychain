---
navigation:
  title: Settings Copier
  icon: daisychain:settings_copier
  position: 60
item_ids:
  - daisychain:settings_copier
---

# Settings Copier

Made the perfect filter? Nobody wants to build it twenty more times, and with the <ItemLink id="daisychain:settings_copier" /> you
won't have to. It copies one side's whole setup (filter, amounts, redstone, Send to, priority, even the upgrade tier) so you can
paste it wherever you like.

- **Copy:** right-click a pull or insert side. The copier starts to glow.
- **Paste:** sneak and right-click another side on the same kind of pipe, then do it once more within 3 seconds to confirm.
  The double click keeps a stray click from wiping a side you already set up. On a roll? For the next 5 seconds, every
  side you sneak and right-click pastes in one go, so a whole row of machines takes about as long as saying "row".
- **Paste everywhere:** sneak and right-click a pipe's center (twice, to confirm) and every pull side on that network
  gets the settings, or every insert side if you copied an insert side.
- **Undo:** changed your mind? Press **Z** while holding the copier to undo your newest paste (you can change the key in
  Controls). Press it again for the one before, up to your last 5 pastes. Sides that were changed in the meantime are left
  alone, and the message above your hotbar says how many. Each paste gets a number above your hotbar, and
  `/daisychain undo <number>` undoes that one specifically.
- **Redo:** undid one too many? Press **R** while holding the copier to bring it back. Redo works until you paste
  something new.

While you hold the copier, it shows you what it's up to, through walls too (handy for a big network):

- **White:** the side you copied from.
- **Gold:** the sides your next click will change. It fades when the 3 seconds to confirm run out.
- **Green:** your latest paste, which is exactly what Undo would put back.
- **Blue:** the sides your last undo put back.
- **Clear:** sneak and right-click the air.

Hover over the copier any time to see what it's holding.

## A couple of house rules

- Pull settings paste onto pull sides, and insert settings onto insert sides. The copier changes how a side is set up, not what
  kind of side it is, so if you need to switch a side's type, grab the <ItemLink id="daisychain:bloom_wrench" /> first.
- **Upgrades:** pasting takes the copied upgrade from your inventory and hands back the one it replaced. Don't have the right one
  on you? No worries, everything else still pastes, and a message tells you which upgrade was missing.

<RecipeFor id="daisychain:settings_copier" />
