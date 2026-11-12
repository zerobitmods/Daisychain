---
navigation:
  title: For server owners
  icon: minecraft:command_block
  position: 80
---

# For server owners

Running a server? Daisychain was built with you in mind, and it tries hard to be a good guest.

**Pipes don't tick.** Each connected network does its work in one place, and only pull sides ever run. Idle sides take a nap until
something changes.

**There's a budget.** Each dimension gets a set amount of pipe work per tick: 20,000 inventory calls and 10 ms by default. If
players build more fast pipes than that, the extra ones wait a tick and go first next time. Their base runs a touch slower, and
your server keeps humming along.

## Config

Everything lives in `config/daisychain-server.toml`: speeds for every upgrade tier, the tick budget and the idle settings. Changes
apply while the server is running, so there's no restart needed. Every value has a safe range too, so a typo won't take anything
down.

Each player also gets their own `config/daisychain-client.toml` for personal things like hints and which view screens open in.

## Commands

These all need operator permission.

- `/daisychain stats` shows how hard the pipes are working: inventory calls per second, the average and busiest tick, and whether
  the budget is being hit.
- `/daisychain lag [seconds]` finds where the pipe work goes. It measures for 10 seconds (or as many as you give it, up to 60), then
  lists the five networks that cost the most: how much of each tick they take, where they are (click to teleport), their busiest
  pull side, and a plain reason when there is one, like a slow storage block or full destinations. Their busiest pull sides glow
  red through walls for 30 seconds, just for you. It only measures while it runs, so it costs nothing the rest of the time.
- `/daisychain network <x y z>` shows the network a pipe belongs to.
- `/daisychain version` tells you which version you're running.
