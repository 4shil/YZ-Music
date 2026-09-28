# Deploying the Play Together share link

This directory serves the invite link people paste into a chat: they open it in a
browser, see the party, and tap through to the app. The realtime party server is
**not** here — that is a separate FastAPI service on Render (see "Backend" below).
Nothing in this folder needs to stay up for a party to sync.

## What breaks the link today

Two handlers both claim `/join/<CODE>`, and they disagree about which one runs:

| Handler | Defined in | Status |
| --- | --- | --- |
| Next.js dynamic route | `src/app/join/[code]/page.tsx` | cannot build — see below |
| `vercel.json` rewrite | `"source": "/join/:code"` → `public/join.html` | works, self-contained |

Vercel applies `rewrites` **after** checking the filesystem, so a matching
framework route wins and `public/join.html` is never reached. Because the
Next.js build currently fails, the rewrite is what actually serves the link —
which is why changing the React page appears to have no effect.

`package.json` declares `build: next build`, so Vercel auto-detects Next.js and
tries to build it. That build **cannot succeed**: the App Router requires a root
layout, and the project has none of the files a Next.js app needs.

Missing, all of them:

```
next.config.js        postcss.config.js      src/app/layout.tsx
tsconfig.json         tailwind.config.js     src/app/globals.css
```

`page.tsx` is written entirely in Tailwind utility classes, and nothing imports
Tailwind — so even with a build that completed, it would render unstyled.

## Choose one design, and delete the other

Both designs serve the same page. Keeping both is what makes the link behave
unpredictably. Pick by whether you want this to be a real React app or a
zero-build static page.

### Option A — static page (recommended; nothing to build)

The invite page is one page with no interactivity beyond a fetch. It does not
need a framework, and a static deploy has no build step to break.

1. Delete `src/app/join/[code]/page.tsx` and the whole `src/` directory.
2. In `package.json`, drop the `next` scripts and dependencies, or replace the
   file with an empty one — Vercel reads it to decide the framework.
3. In project settings set **Framework Preset: Other**, **Root Directory: `web`**,
   **Output Directory: `public`**, and leave the build command empty.
4. Deploy. `vercel.json` already rewrites `/join/:code` and `/invite/:code` to
   `join.html`, so the existing rewrite rules start working with no change.

### Option B — keep the Next.js app

1. Add the missing files: `next.config.js`, `tsconfig.json`,
   `tailwind.config.js`, `postcss.config.js`, `src/app/globals.css` (with the
   three `@tailwind` directives), and `src/app/layout.tsx` as the root layout.
2. Delete `public/join.html` and both rewrite rules from `vercel.json` — with a
   real route present the rewrite is dead config that only misleads.
3. Keep **Framework Preset: Next.js**, **Root Directory: `web`**.

## The payload contract is wrong on both sides

Whichever design you pick, the page reads fields the server does not send. This
is separate from the build problem and will still be broken after it is fixed.

`backend/app/party.py:396` `to_preview()` returns:

```jsonc
{
  "code": "ABC123",
  "hostName": "...",
  "memberCount": 2,          // <- "member", not "members"
  "maxMembers": 5,
  "isFull": false,
  "members": [ { "displayName": "...", "avatarUrl": "...", "isHost": true } ]
  // no nowPlaying field at all
}
```

Both pages disagree with it in two places:

1. **`membersCount` vs `memberCount`.** `page.tsx:63` and `join.html:250-251` read
   `data.membersCount`. The server sends `memberCount`. The listener count
   therefore never appears; the badge stays on its "Live Listening Party"
   fallback forever.
2. **`nowPlaying` does not exist.** `page.tsx:81` and `join.html:256` both read
   `data.nowPlaying`, and a repo-wide grep finds no `nowPlaying` in `backend/`.
   The track preview can never render, even with a live party.

Fix by making the two agree. Renaming the field in `to_preview()` breaks any
other client already reading it, so the safer direction is to accept both on the
page and have the server start sending `nowPlaying`:

```js
const count = data.membersCount ?? data.memberCount;
```

and add a `nowPlaying` block to `to_preview()` carrying `videoId`, `title`,
`artist`, `thumbnailUrl` and `durationMs` from the party's current track. That
needs a host-only-control decision — whether a guest preview may expose the queue
is yours, not mine to pick.

`members[].avatarUrl` is already in the payload but no page reads it. Rendering
the member stack on the invite page is a small add once the count is right.

## Backend configuration

The party server lives in `backend/` and deploys to **Render**, per
`backend/render.yaml`. Three settings must match the Vercel production domain or
the page will fetch and be rejected:

```
PUBLIC_BASE_URL        https://yz-music.vercel.app
CORS_ALLOWED_ORIGINS   https://yz-music.vercel.app,http://localhost:3000,http://127.0.0.1:3000
WEBSOCKET_PUBLIC_URL   wss://yz-music-party.onrender.com/ws/parties
```

The page runs in a browser, so the fetch to `/api/parties/<CODE>/preview` is
cross-origin — it is the Render side that must allow the Vercel origin. Adding a
Vercel preview domain means adding it to `CORS_ALLOWED_ORIGINS`; the
`Access-Control-Allow-Origin: *` in `web/vercel.json` is the wrong direction and
does not affect it.

Note the hardcoded default in both pages,
`https://yz-music-party.onrender.com`. A `?server=` query parameter overrides it,
and `JamInviteLink.url()` URL-encodes one when the user has set a custom server,
so a self-hosted party works. Only the default needs to be correct.

## Android side

`JamInviteLink.ORIGIN` (`data/listentogether/JamInviteLink.kt:19`) is
`https://yz-music.vercel.app`, hardcoded. The app accepts four invite shapes and
will only open links from that exact host, plus the legacy
`yz-music-party.onrender.com`:

| Shape | Accepted |
| --- | --- |
| `yzmusic://party/<CODE>` | yes |
| `https://yz-music.vercel.app/join/<CODE>` | yes |
| `https://yz-music.vercel.app/invite/<CODE>` | yes |
| `https://yz-music-party.onrender.com/invite/<CODE>` | yes (legacy) |

Codes must be exactly `ListenTogether.CODE_LENGTH` alphanumerics, uppercased.
Any other Vercel domain — a preview URL, or a renamed project — will be
rejected by the app, so a domain change means changing `HOST` here too and
rebuilding.

## Verify after deploying

```bash
# the page resolves for a well-formed code
curl -sI "https://yz-music.vercel.app/join/ABC123" | head -1

# the server answers with the shape the page expects
curl -s "https://yz-music-party.onrender.com/api/parties/ABC123/preview"
```

If the first returns 404 the rewrite is being shadowed by a route. If the second
shows `memberCount` and no `nowPlaying`, the contract mismatch above is still
there.
