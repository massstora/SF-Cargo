# Changelog

## Unreleased

Compared with the current GitHub baseline at `origin/master` (`8408b28`, initial SF-Cargo plugin commit).

### Added

- Added an in-memory cargo move journal.
  - Moves are now tracked through planned, withdrawn, restore/insert, and complete states.
  - Failed or partial moves can be queued for rollback during normal runtime.
  - `/sfcargo tps` now reports journal status: active moves, pending rollbacks, completed moves, and rollback queue count.

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
