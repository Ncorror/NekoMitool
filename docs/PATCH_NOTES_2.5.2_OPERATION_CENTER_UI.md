# NekoFlash 2.5.2 — Operation Center UI

This release continues the Classic Recovery UI layer with an Operation Center card on the home dashboard.

## Added

- Home-screen Operation Center with recovery-style status states:
  - idle
  - running
  - completed
  - failed
  - warning
- Last-event panel that follows the visible log and highlights the most recent meaningful event.
- Fast action buttons:
  - Console
  - Reports
  - Log actions
  - Forum ZIP
  - Cancel operation
- Cancel button is disabled when no operation is active and becomes active during long-running flash operations.

## Safety impact

This is a UI/UX release. It does not change Fastboot/ADB write logic, Xiaomi ROM parsing, ARB guard, fastbootd resume, update-super handling, or Magisk/Recovery helpers.

The goal is to make dangerous operations easier to monitor and to reduce user confusion after failures by keeping the last event and report actions visible on the main screen.
