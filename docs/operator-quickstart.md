# Operator quickstart

Get from a fresh clone to *"I have seen what this repo actually does"* in about
five minutes.

**Every command below was executed against tip `9f30c92` on 2026-08-12**, from a
clean clone, in this order. The output shown is the output observed. Where a
step is expected to fail, that is stated — a failure there is the correct
result, not a broken quickstart. One step at the end was deliberately **not**
run; it is marked.

Read [`../README.md`](../README.md) first if you have not. The short version:
this repo is a campaign storefront, its business logic lives elsewhere, and
nothing it declares is currently reachable on the internet.

## 0. Prerequisites

Versions used for the run recorded here. Nothing is pinned by the repo, so
newer versions are likely fine and older ones untested.

```
node       v26.3.0
pnpm       10.26.2    # step 2 only (appview/…/package.json, src/app.ts typecheck)
npm        (on PATH)  # step 3 only (cljs/package.json — shadow-cljs, not pnpm)
nbb        (on PATH)
wrangler   4.69.0     # global; NOT a dependency of either package.json
```

`wrangler` is only needed for step 5. Steps 1–4 do not use it. `cljs/` uses
`npm`, not `pnpm` — it was scaffolded from the landed reference
(`cloud-itonami/app-tia`'s `tia-mcp-component/cljs`), which uses `npm`
throughout this workspace's cljs appview frontends; don't `pnpm install` there.

Set a shell variable for the app directory — it is long and every step needs it:

```bash
APP=appview/etzhayyim-wasm-game-play-uploader-gm3pup1d
```

## 1. Is any of this live? (≈5s)

Run from the repo root:

```bash
kbb --backend sci docs/check-surface.cljk
```

Observed — **exit 1**, and exit 1 is the expected result today:

```
SCANNED	16 files	DECLARED-HOSTS	6
control	registry.npmjs.org	resolves

        dispatcher.etzhayyim.com  NXDOMAIN  <- .../kotodama.jsonld, .../src/app.ts
                   etzhayyim.com  resolves  <- .../kotodama.jsonld, .../wrangler.jsonc, lp/*.html
game-play-uploader.etzhayyim.com  NXDOMAIN  <- .../wrangler.jsonc, lp/*.html, ...
          gm3pup1d.etzhayyim.com  NXDOMAIN  <- .../wrangler.jsonc
                hc.etzhayyim.com  NXDOMAIN  <- .../kotodama.jsonld, lp/*.html, ...
               mcp.etzhayyim.com  NXDOMAIN  <- .../svelte/src/routes/xrpc/[...path]/+server.ts

5 of 6 declared hosts do not exist.
```

**This exact output predates the 2026-09-07 cljs migration and is now stale in
one detail**: `svelte/src/routes/xrpc/[...path]/+server.ts` no longer exists —
the same `mcp.etzhayyim.com` reference now lives in
`src/xrpc-proxy.ts` (see the README's piece 2). Re-run the command; the file
count and file citation will differ from the transcript above, the host
verdicts should not (this migration did not touch DNS or hosts).

Three exit codes, and they mean different things:

| exit | meaning |
|---|---|
| `0` | every declared host resolves — the README's status table is stale, fix it |
| `1` | at least one is NXDOMAIN — **today's expected result** |
| `3` | **could not measure.** The control host failed (no DNS here) or zero hosts were extracted (wrong directory). A pass is never reported from this state. |

If you get `3`, nothing about the hosts has been learned. Do not read it as good news.

## 2. Typecheck the facade that is not deployed (≈10s)

```bash
cd $APP
pnpm install --ignore-scripts
pnpm typecheck
```

Observed: install adds `typescript 6.0.3`; `tsc --noEmit` **exits 0** with no output.

`tsconfig.json` scopes this to exactly `src/app.ts` — confirm with
`./node_modules/.bin/tsc --noEmit --listFiles | grep -v node_modules`, which
prints that one path.

The check does discriminate. Changing `internalTrustSecret`'s return type from
`Promise<string>` to `Promise<number>` produces `TS2322` at lines 66, 80, 82 and
84 — its call site and its returns — and exits non-zero. Restore the file
afterwards.

**This file is never deployed** (step 4 shows why). You are typechecking dead
code. It is still worth keeping green, because the divergence that makes it dead
is unresolved and it may become the live entrypoint.

## 3. Build the frontend that *is* deployed (≈45s)

**Changed 2026-09-07** (Svelte → ClojureScript migration, ADR-2608260900).
Through 2026-09-06 this step built the SvelteKit BFF under `$APP/svelte`; that
tree is deleted. The frontend is now `$APP/cljs` — reagent + re-frame + hiccup
on `jp-go-dds`, a single-page app (ADR-2608080100), served as static assets
(no server-side rendering, no BFF route).

```bash
cd $APP/cljs
npm install
```

Builds in this workspace are serialised repo-wide (CLAUDE.md, resource
governor). Do **not** call `npx shadow-cljs` directly:

```bash
node /path/to/com-junkawasaki/scripts/resource-guard.mjs run build -- amu compile --target wasm32-browser app
```

If another session holds the build lock you get exit `2` and no build output —
that is the guard working, not a failure. Wait and retry.

Observed on the run recorded for this migration: `[:app] Build completed.
(111 files, 110 compiled, 0 warnings, 29.38s)`, **exit 0**. Same command with
`compile test` instead of `compile app` compiles the test build (`(112 files,
111 compiled, 0 warnings, 13.06s)`); running `node out/tests.js` afterwards
prints `Ran 5 tests containing 14 assertions. 0 failures, 0 errors.`
`npm test` (`amu compile --target wasm32-browser test && node out/tests.js`) runs both steps.

Confirm the artifact `wrangler.jsonc` now points at exists:

```bash
cd $APP
ls cljs/public/index.html cljs/public/js/app.js
```

`index.html` exists before and after the build (it is checked in, not
generated); `js/app.js` exists only after.

## 4. What it serves, and what changed (≈5 min read, no server run)

**This step's original form (`pnpm preview` against the SvelteKit build, then
curling `localhost:4319`) no longer applies** — there is no `pnpm preview`
equivalent wired up for the cljs build in this repo, and starting one (or
running `wrangler dev`) was out of scope for the migration that replaced this
tree (see step 7's reasoning — the same "don't stand up a preview of an app
that can't launch its funnel" logic applies, plus this migration specifically
avoided `wrangler dev`/`deploy` so as not to make the entrypoint decision in
piece 1 of the README by accident). What follows is what changed, checked by
reading the build output and source rather than by curling a running server.

**`/` still serves the generated scaffold placeholder**, now built from
`cljs/`. `cljs/public/index.html`'s `<title>` and the mounted view's copy are
byte-for-byte the same strings the old `+page.svelte` rendered
(`etzhayyim-wasm-game-play-uploader-gm3pup1d` / *"No public route is declared
next to this app surface."*) — confirm without a server:

```bash
grep -o '<title>[^<]*</title>' $APP/cljs/public/index.html
grep -n 'No public route' $APP/cljs/src/game_play_uploader/app.cljs
```

`kotodama.jsonld` still advertises `/kids` `/adult-print` `/kids-print`
`/health` `/healthz` `/readyz` under `triggers.http.routes`; none of them are
implemented by the static cljs build (it has exactly one document, per
ADR-2608080100 — `/health` etc. were only ever implemented by the undeployed
`src/app.ts`, unaffected by this migration). That declaration was already wrong
for the thing that ships before 2026-09-07 and still is.

**The XRPC route is gone, not failing.** Before this migration, the SvelteKit
BFF's `/xrpc/[...path]` route existed and failed at the upstream fetch (see the
Status section's history). `wrangler.jsonc` no longer names a `main` Worker
script (step 5), so there is no server-side code left to receive that request
at all — the endpoint's handler was moved unmodified to
`$APP/src/xrpc-proxy.ts` (`SVELTEKIT-BACKEND-PRESERVED` marker) rather than
deleted, but it imports SvelteKit-only symbols and is not wired to anything.
Reviving it is the same entrypoint decision the README's piece 1 describes.

## 5. Validate the deploy config without publishing (≈10s)

**Not re-run as part of the 2026-09-07 cljs migration** — the numbers below
are from the last SvelteKit-era build and are stale for two reasons: the
upload closure is now `cljs/public` instead of the SvelteKit adapter output,
and `wrangler.jsonc` no longer declares `main` at all (see the README's piece 1
for why this migration deliberately left that undecided rather than repointing
it at `src/app.ts`). Re-run this before trusting the numbers:

```bash
cd $APP
wrangler deploy --dry-run --outdir /tmp/gpu-dryrun
```

Previously observed (SvelteKit era, superseded): `Total Upload: 418.93 KiB`
(gzip ≈94.5 KiB), a binding table listing `env.ASSETS` and the nine `APP_*` /
`AGENTGATEWAY_MCP_ROUTER_URL` vars, then `--dry-run: exiting now.` — and, absent
from that table, `DISPATCHER_INTERNAL_SECRET` (which `src/app.ts` reads;
another view of the same divergence).

`--dry-run` publishes nothing and is explicitly permitted by this workspace's
deploy guard.

## 6. Look at the landing pages

They are standalone files. Nothing serves them; open them directly:

```bash
open lp/adult.html lp/kids.html    # macOS
```

Before you do, know where their buttons go:

```bash
cd lp && for f in *.html; do echo "-- $f"; \
  grep -oE 'https?://[a-zA-Z0-9./?=_%~-]+' "$f" | sort -u; done
```

Observed — **every** call-to-action across all four pages:

```
-- adult-print.html
https://hc.etzhayyim.com/register?ref=game-play-uploader
-- adult.html
https://hc.etzhayyim.com
https://hc.etzhayyim.com/legal/worker-agreement
https://hc.etzhayyim.com/register?ref=game-play-uploader
-- kids-print.html
https://hc.etzhayyim.com/register?ref=game-play-uploader-minor
-- kids.html
https://hc.etzhayyim.com
https://hc.etzhayyim.com/legal/minor-consent
https://hc.etzhayyim.com/register?ref=game-play-uploader-minor
```

`hc.etzhayyim.com` is NXDOMAIN. The pages are finished; the destination is not
built. **This is the blocking dependency for launch, and it is not in this
repo.**

## 7. Deploying — NOT run

```bash
wrangler deploy          # ← not executed during the run recorded here
```

Deliberately skipped, for reasons that are about this repo and not about
caution in general:

- **The route hosts do not exist.** `wrangler.jsonc` binds
  `gm3pup1d.etzhayyim.com/*` and `game-play-uploader.etzhayyim.com/*` in zone
  `etzhayyim.com`; both are NXDOMAIN. A deploy would publish a Worker nothing
  can reach.
- **What would be published is the scaffold placeholder**, not the campaign
  (step 4). Shipping it would put a page reading *"No public route is declared"*
  on a public hostname.
- **The funnel has no destination.** Even with routes and real pages, every
  button leads to a host that does not exist (step 6).

Before anyone runs it: this workspace requires the deploying checkout to contain
`origin/main` (enforced by a pre-tool hook), because deploys have no
fast-forward check and the last writer wins.

## Afterwards

`.gitignore` (root, plus `cljs/.gitignore` for shadow-cljs output) covers the
`node_modules/` and `.wrangler/` these steps create, so `git status` stays
readable. One file does show up untracked:

```
?? appview/…/pnpm-lock.yaml
```

That is deliberate, not an oversight. The lockfile is not committed today, and
committing it pins dependency versions the repo currently leaves floating — a
real decision, so it is left to whoever makes it rather than hidden by an
ignore rule. (Before 2026-09-07, `svelte/pnpm-lock.yaml` showed up the same
way; that tree is gone. `cljs/package-lock.json` — `npm`, not `pnpm` — **is**
committed, following the landed reference this scaffold was copied from.)

## What this quickstart does not cover

- **The campaign logic.** Not in this repo — it lives in the kotodama ingest
  module and the BPMN contracts named in `src/app.ts`.
- **Resolving the entrypoint divergence.** Documented in the README, not fixed.
  Fixing it means deciding whether the dispatcher facade (`src/app.ts`) becomes
  the Worker's `main` (fronting the static cljs assets itself) or the app stays
  assets-only with no server-side `/xrpc/*` handler — either is a behaviour
  change, not a documentation one. The 2026-09-07 Svelte → ClojureScript
  migration deliberately did not decide this (see README piece 1).
- **The open items in `MIGRATION-TODO.md`** — the substrate-boundary checklist
  from the extraction. Only the ad-pixel item is closed.
