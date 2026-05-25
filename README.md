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
- Moves are tracked by an in-memory journal so partial moves can be completed or rolled back during normal runtime failures.
- Rollback retries may temporarily load the needed chunk, then release it after the pending move is resolved.
- Block records are stored in SQLite at `plugins/SF-Cargo/blocks.db`.
- Placed cargo heads are marked with a small SF-Cargo block tag so they can still drop the correct SF-Cargo item if the block database is lost.

## Requirements

- Java 21
- Paper or Folia 1.21+
- Optional: CoreProtect for container transaction logging

## Build

```bash
env GRADLE_USER_HOME=/tmp/gradle-home ./gradlew build
```

The plugin jar is written to `build/libs/SF-cargo-0.1.3.jar`.

## Commands

```text
/sfcargo give <manager|connector|input|output>
/sfcargo reload
/sfcargo list
/sfcargo manager <uuid> queued
/sfcargo manager <uuid> purge [id]
/sfcargo manager <uuid> detail <id>
/sfcargo tps
```

All `/sfcargo` commands require `sfcargo.admin`.

### `/sfcargo list`

Lists known cargo managers, their owner, UUID, location, and clickable player actions. `[TP]` teleports to the manager. `[COPY UUID]` copies the manager UUID to the player's clipboard for use with the manager debug commands. Managers with active blocked-transfer issues are marked with `☠`.

Example:

```text
Tracked cargo blocks: 42
Cargo managers: 2
[TP] world -15035 255 11645 [COPY UUID] UUID: 7fa2b5b4-9ec6-4c62-94f5-93fbb7b74221 Owner: samos ☠
[TP] world_nether 128 64 -88 [COPY UUID] UUID: 4d46d2d8-947e-441f-9c7f-0bc3a6512c4c Owner: Alex
```

### `/sfcargo manager <uuid> queued`

Shows the current debug queue for one cargo manager, sorted oldest first. These entries represent currently blocked candidate transfers, not items already removed from an input inventory.

Each queued entry has a short manager-local numeric ID from `1` to `9999`. IDs are reused after entries are cleared or purged, and the same ID may exist under different managers.

Blocked scanning is capped per manager and channel by `max-blocked-input-slots-per-manager-channel`. Once the cap is reached, SF-Cargo stops scanning that manager/channel on normal loops and only rescans it every `discovery-interval-loops` loop(s). Existing blocked entries are checked on those rescans so they can clear.

Example:

```text
Queued cargo moves for 7fa2b5b4-9ec6-4c62-94f5-93fbb7b74221: 2
#12 age 43s channel 3 64x cobblestone reason: output has no space
#13 age 18s channel 0 16x oak_log reason: output unloaded
☠ channel 3 blocked scan capped at 520 slots; more blocked inputs may exist
```

### `/sfcargo manager <uuid> detail <id>`

Shows details for one queued debug entry, including age, item, amount, channel, input inventory location, input slot, and the current reason SF-Cargo believes the transfer is blocked.

Possible reasons include:

- `output has no space`
- `output unreachable`
- `output unloaded`
- `input unreachable`
- `input unloaded`
- `no output on channel`

### `/sfcargo manager <uuid> purge [id]`

Clears debug queue entries for one manager.

Without an ID, this clears every current queued debug entry for the manager:

```text
/sfcargo manager 7fa2b5b4-9ec6-4c62-94f5-93fbb7b74221 purge
```

With an ID, this clears only that one queued debug entry:

```text
/sfcargo manager 7fa2b5b4-9ec6-4c62-94f5-93fbb7b74221 purge 12
```

Purging a debug queue entry does not delete items from inventories. If the same transfer remains blocked, SF-Cargo may report it again later with a new recycled ID.

### `/sfcargo tps`

Shows the health of the SF-Cargo worker loop, live blocked-transfer debug queue size, and whether inventory work is backing up behind Folia/Paper scheduling.

Example:

```text
SF-Cargo TPS: 10 / 10
Plugin loop time: avg 0.18ms, last 0.11ms
Blocked transfer queue entries: 2
Deferred transport ticks: 18
Journal: active 0, pending rollback 0, rollback queued 0
Transport wait: avg 3.42ms, last 2.95ms
Configured interval: 100ms, discovery every 10 loop(s)
Tracked blocks: 42, managers: 2
```

- `SF-Cargo TPS` is completed cargo discovery/planning loops in the last second out of the configured target.
- `Plugin loop time` is the cost of SF-Cargo's own worker logic.
- `Blocked transfer queue entries` is the total number of current per-manager debug queue entries.
- `Deferred transport ticks` increments when a cargo move batch is ready but the previous inventory batch is still waiting/running.
- `Journal` shows active in-memory moves and current pending rollback work.
- `Transport wait` is time spent waiting for Folia region tasks or Paper main-thread inventory work.

Some deferred transport ticks are normal, especially on active servers. If `Deferred transport ticks` keeps climbing, stays unusually high for your server, or cargo movement starts visibly falling behind, increase `tick-interval-ms` in `config.yml`. The default `100ms` interval is an optimistic goal; busy Folia regions or a loaded Paper main thread may need a longer interval so inventory moves have time to complete cleanly.

Network discovery is also configurable with `discovery-interval-loops`. The default is `10`, which means a server running the default `100ms` cargo interval refreshes cargo network discovery about once per second and reuses the cached network snapshot between refreshes.

## Safety Behavior

Before an input node removes an item, SF-Cargo checks destination capacity on the matching output channel. It withdraws only the amount that can fit, so a partial destination stack should only pull a partial amount from the source.

Each move is tracked in an in-memory journal:

```text
planned -> withdrawn -> inserted/restored -> complete
```

If an output becomes unavailable after withdrawal, SF-Cargo attempts to restore the leftover to the input inventory. If the input side is unavailable, the move is kept in the in-memory rollback queue and retried. Rollback retries may temporarily add a plugin chunk ticket so the pending move can finish, then the ticket is removed.

While the journal has active moves or pending rollbacks, SF-Cargo pauses normal item routing and works on journal cleanup first. Temporary chunk loading is only used to complete or roll back already-started moves; it is not used to keep cargo networks processing in unloaded areas.

## Usage

Right-click a cargo block to open its UI.

Input nodes have filters, whitelist/blacklist mode, lore and durability matching, smart-fill, round-robin, and channel controls.

Output nodes choose the channel they accept items from.

Manager and connector blocks show network status. They do not spawn holograms or invisible armor stands.

Recipes are vanilla crafting-table recipes and can be customized in `config.yml`.

Useful config values:

```yaml
tick-interval-ms: 100
discovery-interval-loops: 10
max-nodes-per-network: 512
max-blocked-input-slots-per-manager-channel: 520
```

## Data And Recovery

SF-Cargo stores tracked cargo blocks in `plugins/SF-Cargo/blocks.db`, a SQLite database. This database contains block locations, owners, manager UUIDs, channels, node settings, and input filters.

Older releases used `plugins/SF-Cargo/blocks.yml`. On startup, SF-Cargo imports legacy `blocks.yml` or `blocks.yaml` data into `blocks.db` before enabling listeners, recipes, or the cargo worker. After a successful import, the legacy YAML file is removed so future starts only use the database.

Network connections are rediscovered from those saved block records at runtime. The full network graph is not permanently stored.

Newly placed cargo heads also receive a small block-level SF-Cargo marker containing their cargo type. If `blocks.db` is lost, those marked heads can still drop the correct SF-Cargo item when broken. Re-place the item to register a fresh block record. Old settings such as filters, channels, ownership, and manager UUIDs are not recovered from the marker.

## Attribution

SF-Cargo is inspired by and reimplements the cargo-management behavior from Slimefun4.

Slimefun4 is developed by the Slimefun project and contributors:

https://github.com/Slimefun/Slimefun4

This project uses the same Slimefun cargo head texture hashes for the cargo manager, connector, input node, and output node so the cargo blocks remain visually familiar.

SF-Cargo is not official, endorsed, or maintained by the Slimefun project.

## License

SF-Cargo is distributed under the GNU General Public License version 3. See [LICENSE](LICENSE).

Because Slimefun4 is GPLv3 and this project intentionally reimplements a subset of Slimefun cargo behavior, redistributed builds should include source code and preserve the GPLv3 terms.
