# Archi SharePoint Sync Plugin

**Autosave ArchiMate models directly to SharePoint Online, with Word-style concurrent access.**

Plugin ID: `com.archimatetool.sharepoint` · Version 1.2.0 · Requires Archi **5.10+** (Java 21 runtime, ships with Archi).

## Features

- **M365 Office-style sign-in** — interactive OAuth2 with your system browser (localhost loopback),
  automatic fallback to device-code flow. Token cache persists across restarts, and a background
  warm-up at startup restores your session silently (no dialogs) so autosave and sync resume
  automatically for previously connected models.
- **Autosave to SharePoint Online** — every N seconds (default 60), dirty models are saved locally
  and uploaded via Microsoft Graph with ETag optimistic concurrency.
- **Concurrent access, Word-on-SharePoint style** —
  polls the remote file (default 30 s):
  - remote changed & local clean → reloads the remote version (auto or after confirmation);
  - remote changed & local dirty → conflict dialog: *Keep mine / Load theirs / Save my copy & load theirs / Ask me later*;
  - optional **check-out on edit / check-in on close** (SharePoint locking) via preferences.
- **Discard check-out** — the **Check out** menu item toggles to **Discard Check-out** while the
  file is checked out to you, letting you abandon the server-side changes and release the lock
  (after a confirmation).
- Bindings are remembered per model file and restored on restart — reopen the model file and
  it reconnects and resumes syncing automatically (after the silent session restore).
  The connect wizard also remembers the last used site URL and folder.

## One-time setup: Entra ID app registration

The plugin needs a Microsoft Entra ID (Azure AD) app registration in your tenant
(this is what identifies the "app" to Microsoft 365, like Office has its own):

1. Entra portal → **App registrations** → **New registration**
   - Name: `Archi SharePoint Sync` · Supported account types: *this org* (or multi-tenant).
   - Redirect URI: platform **Mobile and desktop applications** → `http://localhost`
2. In the app → **Authentication** → enable **Allow public client flows** (needed for device-code fallback).
3. **API permissions** → add **delegated** Microsoft Graph permissions:
   `Sites.ReadWrite.All`, `Files.ReadWrite.All`, `User.Read` (+ `offline_access`).
   Grant admin consent if your org requires it.
4. Copy the **Application (client) ID** (and tenant ID if single-tenant).

In Archi: **Window → Preferences → SharePoint** → paste the Client ID (and Tenant ID, or leave
`organizations` for multi-tenant). Set autosave/poll intervals and check-out behavior there.

## Installation

1. In Archi: **Help → Manage Plug-ins… → Install New…** → choose
   `com.archimatetool.sharepoint_1.2.0.archiplugin` → restart Archi.
   (Manual alternative: unzip and drop `com.archimatetool.sharepoint_1.2.0.jar` into Archi's
   `dropins` folder, e.g. `%APPDATA%\Archi\dropins`, `~/Library/Application Support/Archi/dropins`,
   or `~/.archi/dropins`.)
2. **SharePoint → Sign in to Microsoft 365** — a browser tab opens (or a device code is shown);
   sign in with your work account.
3. Open or create a model and **save it to a local file** (the plugin syncs that file).
4. **SharePoint → Connect to SharePoint…** → enter the site URL
   (`https://<tenant>.sharepoint.com/sites/<site>`), pick a document library and folder,
   confirm the file name. Done — autosave and sync are now active.

## Usage

| Menu (SharePoint)        | Action                                                        |
|--------------------------|---------------------------------------------------------------|
| Sign in / Sign out       | M365 interactive auth, token cache persists                   |
| Connect to SharePoint…   | Bind the active model to a SharePoint file                    |
| Disconnect               | Remove the binding (file stays in SharePoint)                 |
| Sync now                 | Immediate save + upload                                       |
| Check out / Discard Check-out | Toggles: checks the file out, or discards your check-out (abandoning server-side changes) after confirmation |
| Check in                  | Check the file back in with a comment (also automatic on close if enabled in prefs) |

**Conflict handling:** if someone else uploads while you have unsaved changes, you get the
conflict dialog; "Save my copy & load theirs" writes
`<name>.conflict-<timestamp>.archimate` next to your model file before reloading.

## How it works (technical)

- Microsoft Graph API v1.0 (`/sites`, `/drives`, `/items`), upload with `If-Match: <eTag>`;
  HTTP 412 triggers the conflict flow; eTag polling detects remote edits.
- Archi hooks: `IEditorModelManager` property-change events (`COMMAND_STACK_CHANGED` dirty flag,
  model opened/removed/saved) drive the sync engine; reload uses the proven
  `closeModel → replace file → openModel` pattern (same as the coArchi git plugin).
- Auth: MSAL4J public client, loopback `http://localhost:<port>` redirect, device-code fallback,
  MSAL token cache persisted in the Archi state location.
- Embedded runtime deps (inside the bundle's `lib/`): msal4j, azure-json, gson, slf4j-api/nop.

## Limitations (v1.2.0)

- Sync granularity is the **whole file** (SharePoint cannot merge ArchiMate XML server-side the way
  Word merges documents). Concurrent edits are reconciled via conflict resolution, not merged.
- Requires each user to save the model to a local file path (the binding is keyed by it).
- Delegated auth only (user sign-in); no app-only/daemon mode.

## Build from source

See `build.sh` (needs JDK 21 + the Archi 5.10.0 plugin jars + MSAL4J deps; the script expects the
toolchain layout from this repository). Output: `com.archimatetool.sharepoint_1.2.0.archiplugin`.
