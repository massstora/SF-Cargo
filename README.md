# SF-Cargo

## Important Scope Notice

**SF-Cargo is only a cargo management plugin.**

This project is a small standalone reimplementation/tweak of the Slimefun-style cargo network concept. It is **not Slimefun**, does **not include the rest of Slimefun**, and does **not attempt to provide machines, research, energy, items, categories, guide content, or any other Slimefun gameplay systems**.

If you want the full Slimefun experience, install the official Slimefun4 plugin instead:

https://github.com/Slimefun/Slimefun4

SF-Cargo is a standalone Paper/Folia plugin that ports the core Slimefun-style cargo network idea into a smaller plugin:

- Cargo Manager blocks discover connected networks.
- Cargo Connector blocks extend the network.
- Cargo Input Nodes pull from the block they are attached to.
- Cargo Output Nodes insert into the block they are attached to.
- Nodes can be up to 6 blocks apart on a straight axis, matching Slimefun's `RANGE = 5` discovery behavior.
- Each manager gets its own persistent UUID.
- Channels are 0-15 per node.
- Network discovery and tick coordination run on a dedicated worker thread.
- Bukkit world and inventory access is always scheduled back to the owning Folia region, with a Paper main-thread fallback.
- Output nodes only insert into supported storage blocks: chests, trapped chests, barrels, shulker boxes, hoppers, droppers, and dispensers.
- Shulker boxes cannot be inserted into other shulker boxes.

## Requirements

- Java 21
- Paper or Folia 1.21+
- Optional: CoreProtect for container transaction logging

## Build

```bash
env GRADLE_USER_HOME=/tmp/gradle-home ./gradlew build
```

The plugin jar is written to `build/libs/SF-cargo-0.1.0.jar`.

## Commands

```text
/sfcargo give <manager|connector|input|output>
/sfcargo reload
/sfcargo list
/sfcargo tps
```

All `/sfcargo` commands require `sfcargo.admin`.

### `/sfcargo list`

Lists known cargo managers, their owner, UUID, location, and a clickable teleport link for players.

Example:

```text
Tracked cargo blocks: 42
Cargo managers: 2
[TP] world -15035 255 11645 UUID: 7fa2b5b4-9ec6-4c62-94f5-93fbb7b74221 Owner: samos
[TP] world_nether 128 64 -88 UUID: 4d46d2d8-947e-441f-9c7f-0bc3a6512c4c Owner: Alex
```

### `/sfcargo tps`

Shows the health of the SF-Cargo worker loop and whether inventory movement is backing up behind Folia/Paper scheduling.

Example:

```text
SF-Cargo TPS: 10 / 10
Plugin loop time: avg 0.18ms, last 0.11ms
Queued inventory moves: 18
Transport wait: avg 3.42ms, last 2.95ms
Configured interval: 100ms
Tracked blocks: 42, managers: 2
```

- `SF-Cargo TPS` is completed cargo discovery/planning loops in the last second out of the configured target.
- `Plugin loop time` is the cost of SF-Cargo's own worker logic.
- `Queued inventory moves` increments when a cargo move batch is ready but the previous inventory batch is still waiting/running.
- `Transport wait` is time spent waiting for Folia region tasks or Paper main-thread inventory work.

Some queued inventory moves are normal, especially on active servers. If `Queued inventory moves` keeps climbing, stays unusually high for your server, or cargo movement starts visibly falling behind, increase `tick-interval-ms` in `config.yml`. The default `100ms` interval is an optimistic goal; busy Folia regions or a loaded Paper main thread may need a longer interval so inventory moves have time to complete cleanly.

## Usage

Right-click a cargo block to open its UI.

Input nodes have filters, whitelist/blacklist mode, lore and durability matching, smart-fill, round-robin, and channel controls.

Output nodes choose the channel they accept items from.

Manager and connector blocks show network status. They do not spawn holograms or invisible armor stands.

Recipes are vanilla crafting-table recipes and can be customized in `config.yml`.

## Attribution

SF-Cargo is inspired by and reimplements the cargo-management behavior from Slimefun4.

Slimefun4 is developed by the Slimefun project and contributors:

https://github.com/Slimefun/Slimefun4

This project uses the same Slimefun cargo head texture hashes for the cargo manager, connector, input node, and output node so the cargo blocks remain visually familiar.

SF-Cargo is not official, endorsed, or maintained by the Slimefun project.

## License

SF-Cargo is distributed under the GNU General Public License version 3. See [LICENSE](LICENSE).

Because Slimefun4 is GPLv3 and this project intentionally reimplements a subset of Slimefun cargo behavior, redistributed builds should include source code and preserve the GPLv3 terms.
