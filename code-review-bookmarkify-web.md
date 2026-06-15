# Code Review — bookmarkify-web

**Date:** 2026-05-27  
**Scope:** `bookmarkify-web/` directory (commits `6ef3e65` → `HEAD`)  
**Effort:** High — 3 finder angles × up to 6 candidates each, verified with targeted agent passes  
**Methodology:** Line-by-line diff scan (Angle A), removed-behavior audit (Angle B), cross-file call-site trace (Angle C), then a 1-vote verifier per surviving candidate.

---

## Summary

Three verified findings survive. Two are confirmed regressions introduced by the current diff; one is a latent gap exposed by it. No security vulnerabilities found. No findings in doc-only or whitespace changes.

---

## Finding 1 — **HIGH** · Confirmed

### WebSocket reconnect race: new socket is clobbered after every token change

**File:** `bookmarkify-web/stores/websocket.store.ts`  
**Lines:** 44–68 (the `connect()` / `onclose` block)

#### What the diff changed

Before: `connect()` had a simple `if (this.socket) return` guard — it refused to connect if any socket was already open. Token changes had no code path.

After: `connect()` now handles token changes by closing the old socket and opening a new one:

```typescript
// token-change path (lines 43–54)
if (this.socket) {
  this.manualClose = true
  try { this.socket.close() } catch { /* noop */ }
  this.socket = undefined
}
this.manualClose = false      // ← reset synchronously  (line 49)
this.currentToken = token     // ← new token recorded
this.socket = new WebSocket(url)  // ← new socket created

// handler registered on NEW socket
this.socket.onclose = () => {
  this.socket = undefined     // ← line 65
  this.isConnected = false
  this.stopHeartbeat()
  if (!this.manualClose) this.reconnect()  // ← line 68
}
```

#### The bug

`WebSocket.close()` is a synchronous _API call_, but the `close` event fires **asynchronously** (after the current call stack unwinds). The sequence is:

1. `manualClose = true` (line 44)
2. `socket.close()` — old socket will fire `onclose` later
3. `socket = undefined`
4. **`manualClose = false`** (line 49) — reset happens in the same synchronous block
5. `currentToken = token`
6. `socket = new WebSocket(url)` — new connection stored

Then, asynchronously:

7. Old socket's `onclose` fires.  
   At this point `this.manualClose === false` and `this.socket` is the **new** WebSocket.
8. `this.socket = undefined` — **the new connection is clobbered**.
9. `if (!this.manualClose)` → `true` → `this.reconnect()` is called.
10. `reconnect()` sets `this.currentToken = ''` then calls `connect(token)`.
11. `connect()` sees `currentToken !== token` and creates a **third** WebSocket, triggering the same cycle again.

#### Failure scenario

Every time the user's token refreshes (e.g., after `loginOrRegister()` issues a new anonymous session on app load, or after re-authentication), the WebSocket goes through one full spurious reconnect cycle with an exponential-backoff timer. On each cycle `reconnectAttempts` increments; after 5 cycles the WebSocket gives up permanently until the next page load.

#### Fix sketch

Before calling `.close()` on the old socket, null out or no-op its `onclose` handler so the async event can't interfere:

```typescript
if (this.socket) {
  this.manualClose = true
  this.socket.onclose = null   // ← prevent old handler from firing
  try { this.socket.close() } catch { /* noop */ }
  this.socket = undefined
}
this.manualClose = false
```

---

## Finding 2 — **MEDIUM** · Confirmed

### FolderPanel.vue: open panel never reflects WebSocket-pushed children updates

**Files:**  
- `bookmarkify-web/components/launchpad/FolderPanel.vue` — lines 120–133 (`localChildren`, `watch`)  
- `bookmarkify-web/pages/index.vue` — lines 55–60 (`folderPanelItem` ref)

#### What the diff changed

`updateOneBookmarkCell()` in `bookmark.store.ts` was refactored to replace nodes via recursive spread (`{ ...n, children: updateNodeRecursive(...) }`), creating new object references at every level to guarantee Vue reactivity. This is correct for the launchpad grid. However, the `FolderPanel` component was not updated to handle the case where its own folder node gets replaced.

#### The bug

In `pages/index.vue`:

```typescript
const folderPanelItem = ref<UserLayoutNodeVO | null>(null)

function openFolderPanel(item: UserLayoutNodeVO) {
  folderPanelItem.value = item   // snapshot of the node at click time
  folderPanelVisible.value = true
}
```

`folderPanelItem` is a plain `ref` holding whatever node was clicked. It is **never updated** when the bookmark store replaces that node.

In `FolderPanel.vue`:

```typescript
watch(
  () => [props.visible, props.folder?.id] as const,
  ([visible]) => {
    if (visible && props.folder) {
      localChildren.value = (props.folder.children || []).filter(...)
    }
  },
)
```

The watcher triggers only when `props.visible` or `props.folder?.id` changes. When `HOME_ITEM_UPDATE` arrives and `updateOneBookmarkCell()` replaces the folder object in the store (same `id`, new object), `props.folder` still points to the **old** JavaScript object (via the stale `folderPanelItem` ref). The `id` is unchanged, so the watcher does not re-fire.

#### Failure scenario

1. User opens a folder panel showing bookmarks [A, B, C].
2. Another device or the scraper pushes a `HOME_ITEM_UPDATE` via WebSocket — the folder's children become [A, B, D].
3. The store updates correctly. The launchpad grid re-renders correctly. But `folderPanelItem.value` still holds the old node object with `children: [A, B, C]`.
4. The `FolderPanel` watcher does not fire. `localChildren` keeps [A, B, C].
5. The user sees the stale children indefinitely until they close and reopen the panel.

**Compound risk:** `onDragReleaseEnd` (line 155) writes `localChildren.value` back to the store. If the user drags while the panel shows stale data, any bookmark that was WebSocket-deleted from the folder is silently reintroduced into `dirNode.children`.

#### Fix sketch

Replace `folderPanelItem` with a computed ref derived from the store, so it always tracks the live node:

```typescript
// pages/index.vue
const folderPanelId = ref<string | null>(null)
const folderPanelItem = computed(() =>
  bookmarkStore.layoutNode?.find(n => n.id === folderPanelId.value) ?? null
)

function openFolderPanel(item: UserLayoutNodeVO) {
  folderPanelId.value = item.id
  folderPanelVisible.value = true
}
```

Alternatively, watch `props.folder?.children` (deep) inside `FolderPanel.vue`.

---

## Finding 3 — **MEDIUM** · Plausible

### Non-JSON server responses silently reject with no user feedback

**File:** `bookmarkify-web/server/apis/http.ts`  
**Lines:** 86 (`JSON.parse`), 88–91 (`catch` block)

#### What the diff changed

The old code built a `Request` object and used `resultCheck()`. The new code inlines `exec()` and `handleResult()`. In the process, the error-surfacing logic was preserved as-is — only `TypeError` shows a toast:

```typescript
const exec = async (retried: boolean): Promise<any> => {
  try {
    const response = await fetch(url, { ... })
    const text = await response.text()
    if (!text) return null
    const data = JSON.parse(text) as Result<object>  // ← line 86: can throw SyntaxError
    return await handleResult(data, () => exec(true), retried)
  } catch (error) {
    // ← line 89: only TypeError gets a toast
    if (error instanceof TypeError && import.meta.client) ElMessage.error(`Oops,网络错误,请重试`)
    return Promise.reject(error)
  }
}
```

#### The bug

`JSON.parse` throws `SyntaxError` (not `TypeError`) when the input is not valid JSON. A `SyntaxError` is caught by the `catch` block but the `instanceof TypeError` guard is false, so no `ElMessage.error` fires. The call rejects silently.

#### Failure scenario

The backend is temporarily unreachable and nginx returns a `502 Bad Gateway` HTML page. `response.text()` returns `'<html>...'`. `JSON.parse` throws `SyntaxError`. The catch block runs, check fails, no toast shows. The user clicks **Add Bookmark** (or any API-backed action) and nothing happens — no error, no indication of failure.

This also affects: avatar upload, preference save, any action that calls `http.start()` or `http.uploadFile()`.

#### Fix sketch

```typescript
} catch (error) {
  const isNetworkOrParseError = error instanceof TypeError || error instanceof SyntaxError
  if (isNetworkOrParseError && import.meta.client) ElMessage.error(`Oops,网络错误,请重试`)
  return Promise.reject(error)
}
```

---

## Refuted / Not Bugs

| Claim | Verdict | Reason |
|---|---|---|
| `return retry()` missing `await` loses data | **Refuted** | `return somePromise` in an `async` function propagates the resolved value correctly to awaiting callers |
| 101 retry with empty token falls through to 1xx toast | **Refuted** | `loginOrRegister()` always either returns a valid account or `Promise.reject()` — never returns a falsy token normally |
| `authStore.account!` crashes on null in corrupted state | **Refuted** | `account` type is `UserInfo \| undefined` (never null); `authStatus` getter's `== undefined` guard catches both |
| `onDragReleaseEnd` mutates a detached plain object (invisible to Pinia) | **Refuted** | Pinia's `reactive()` deep-proxies all nested objects, so `.find()` returns a reactive proxy and mutation is tracked |
| `backgroundImageDataUrl: null` init causes background flash | **Refuted** | `pinia-plugin-persistedstate` restores the full state synchronously; `launch.vue` already has an intentional `hydrated` guard for SSR/client consistency |
| Email code cap change (6→4) breaks verification | **Refuted** | HTML input already had `maxlength="4"` and the UI has 4 input boxes — the JS change aligns with existing HTML constraint |

---

## Non-Bug Observations

- **`server/utils/index.ts`**: Removal of debug `console.log` calls from `cn()`, `randomId()`, `limitAction()`, `getCurrentEnvironment()` is correct housekeeping — no behavioral change.
- **`pages/index.vue` `createFolder()` sort fallback**: The `-1` guard is dead code (the function already returns early if `draggedNode` is not found via `.find()`), but the fallback to `nodes.length` is harmless and defensive.
- **`stores/bookmark.store.ts` `addEmpty()` spread order**: The old `{ children: [], ...item }` was confusing but functionally equivalent to the new `{ ...item, children: item.children ?? [] }` — the spread always overrode `children: []`. The rewrite is clearer.
- **`stores/websocket.store.ts` non-JSON message filter**: The new `raw === 'pong' || raw === 'ping'` guard before `JSON.parse` is a correct fix for server heartbeat frames that would have crashed the old code.
