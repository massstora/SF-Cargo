# Changelog

## 0.1.3

### Changed

- Blocked transfer debug queue IDs are now local to each manager.
  - `/sfcargo manager <uuid> detail <id>` and `purge <id>` already include the manager UUID, so global uniqueness was unnecessary.
  - IDs still stay in the short `1`-`9999` range and are reused after entries clear.

## 0.1.2

### Added

- Added per-manager blocked transfer debug queues.
  - `/sfcargo manager <uuid> queued` lists blocked candidate transfers oldest first.
  - `/sfcargo manager <uuid> detail <id>` shows item, amount, channel, input slot/location, age, and the likely blocked reason.
  - `/sfcargo manager <uuid> purge` clears all current debug queue entries for a manager.
  - `/sfcargo manager <uuid> purge <id>` clears one debug queue entry.
  - Queue IDs are short `1`-`9999` values and can be reused after entries clear.
  - Blocked input slot scanning is capped per manager/channel by `max-blocked-input-slots-per-manager-channel`.
  - Capped manager/channels are only rescanned every `discovery-interval-loops` loop(s).

- Added clickable manager UUID copying to `/sfcargo list`.
  - Player output now includes `[COPY UUID]` next to each manager.
  - Managers with active queue issues are marked with `☠`.

- Added SQLite block storage.
  - Tracked cargo blocks now persist to `plugins/SF-Cargo/blocks.db`.
  - SQLite JDBC is bundled into the plugin jar.

### Changed

- Replaced full YAML block saves with per-record SQLite writes.
  - Placing and breaking cargo blocks writes only that block record.
  - Menu edits flush only changed in-memory records.

- Updated `/sfcargo tps` debug output.
  - Added `Blocked transfer queue entries`.
  - Renamed old queued inventory pressure to `Deferred transport ticks`.
  - Removed completed journal tracking because completed moves are not retained in the live journal.
  - Rollback queue output now reflects current pending rollback work rather than a lifetime counter.

### Migration

- Legacy `plugins/SF-Cargo/blocks.yml` and `plugins/SF-Cargo/blocks.yaml` are imported into `blocks.db` on startup before functional plugin portions are enabled.
- After successful import, the legacy YAML file is removed so future starts only use SQLite.

## 0.1.1

Compared with the current GitHub baseline at `origin/master` (`8408b28`, initial SF-Cargo plugin commit).

### Added

- Added an in-memory cargo move journal.
  - Moves are now tracked through planned, withdrawn, restore/insert, and complete states.
  - Failed or partial moves can be queued for rollback during normal runtime.
  - `/sfcargo tps` now reports journal status: active moves, pending rollbacks, and rollback queue count.

- Added rollback retry support with temporary chunk tickets.
  - Pending rollback work can temporarily load the needed chunk to finish restoring items.
  - Temporary tickets are removed after the restore attempt.
  - SF-Cargo removes its plugin chunk tickets on disable as a cleanup guard.

- Added a configurable network discovery interval.
  - New config option: `discovery-interval-loops`.
  - Default is `10`, so with the default `100ms` cargo loop, network discovery refreshes about once per second.
  - Cached network snapshots are reused between discovery refreshes.

- Added manager creation timestamps.
  - Stored as `created-at` in block storage.
  - Used to decide which manager remains active when multiple managers are connected.

### Changed

- Multiple-manager networks now keep the oldest manager active.
  - Newer connected managers are treated as inactive for routing.
  - Multiple-manager warnings remain visible.
  - Manager GUI now indicates whether the viewed manager is active or inactive.

- Normal cargo routing now pauses while the journal has active moves or pending rollbacks.
  - This keeps temporary chunk loading focused on completing or rolling back already-started moves.
  - Temporary force-loaded chunks are not used as an opportunity to continue processing unloaded networks.

- `/sfcargo tps` now includes discovery cadence and journal information.
  - Shows configured cargo interval and discovery interval.
  - Shows queued inventory move pressure.
  - Shows transport wait time separately from plugin loop time.

- README was expanded with operational notes.
  - Added memory journal behavior.
  - Added temporary rollback chunk-ticket behavior.
  - Added block marker recovery notes.
  - Added scaling/config notes for `tick-interval-ms` and `discovery-interval-loops`.

### Safety

- Cargo movement now has a stronger rollback path if an output or input location becomes unavailable mid-move.
- Active rollback work is prioritized before new cargo moves are started.
- Existing manager conflict warnings remain, but accidental newer managers no longer shut down the older manager's network.

### Assets

- Added project icon assets under `assets/` for publishing pages such as Modrinth.
