# NekoFlash 2.4.9 — Xiaomi sparse/split image guard

## Summary

This patch hardens Xiaomi Fastboot ROM handling for real-world ROM archives that contain split or sparse image chunks instead of plain `.img` files.

## Changes

- Added manifest recognition for:
  - `*.img_sparsechunk.N`
  - `*.sparsechunk.N`
  - `*.img.N`
- Added split image group reporting.
- Added sequence warnings for missing chunk indexes, duplicated indexes and sequences that do not start from `0`.
- Added duplicate-basename warnings for images referenced by the selected `flash_all` script.
- Added source-fingerprinted extraction directories so two ROM files with the same basename do not reuse stale extracted images.

## Safety policy

The patch does not make sparse/split chunks a destructive action by itself. It improves preflight visibility and prevents stale extraction conflicts before any fastboot command is executed.
