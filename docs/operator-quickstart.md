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
pnpm       10.26.2
nbb        (on PATH)
wrangler   4.69.0     # global; NOT a dependency of either package.json
```

`wrangler` is only needed for step 5. Steps 1–4 do not use it.

Set a shell variable for the app directory — it is long and every step needs it:

```bash
APP=appview/etzhayyim-wasm-game-play-uploader-gm3pup1d
```

## 1. Is any of this live? (≈5s)

Run from the repo root:

```bash
nbb docs/check-surface.cljs
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

## 3. Build the artifact that *is* deployed (≈15s)

```bash
cd $APP/svelte
pnpm install
```

Observed: installs SvelteKit 2.70.2, svelte 5.56.9, vite 6.4.3,
`@sveltejs/adapter-cloudflare` 7.2.9, and warns that build scripts for
`esbuild` and `workerd` were ignored. **The warning is not a problem** — the
build below succeeds without approving them.

Builds in this workspace are serialised repo-wide (CLAUDE.md, resource
governor). Do **not** call `pnpm build` directly:

```bash
node /path/to/com-junkawasaki/scripts/resource-guard.mjs run build -- pnpm build
```

If another session holds the build lock you get
`resource-guard: build is already running (pid=…)` and **exit 2**. That is the
guard working. Wait and retry — the recorded run was blocked once by an
unrelated repo's build and succeeded on retry.

Observed on success: `✓ built in 4.38s`, then
`Using @sveltejs/adapter-cloudflare  ✔ done`, **exit 0**.

Confirm the artifact `wrangler.jsonc` points at now exists:

```bash
cd $APP
ls svelte/.svelte-kit/cloudflare/_worker.js svelte/.svelte-kit/cloudflare/client
```

Both exist after the build; neither exists before it.

## 4. Run it and see what it actually serves (≈20s)

```bash
cd $APP/svelte
pnpm preview --port 4319
```

In another shell:

```bash
for p in / /health /healthz /kids; do printf '%-10s -> ' "$p"; \
  curl -sS -o /dev/null -w '%{http_code}\n' "http://localhost:4319$p"; done
```

Observed:

```
/          -> 200
/health    -> 404
/healthz   -> 404
/kids      -> 404
```

The 404s are **not** a misconfigured preview. `/health` exists only in the
undeployed `src/app.ts`; `/kids` exists nowhere. `kotodama.jsonld` advertises
all of them under `triggers.http.routes`, and that declaration is wrong for the
thing that ships.

Now look at what `/` is:

```bash
curl -sS http://localhost:4319/ | grep -oE '<title>[^<]*</title>|No public route'
```

Observed:

```
<title>etzhayyim-wasm-game-play-uploader-gm3pup1d</title>
No public route
```

That is the **generated scaffold placeholder**, not the campaign. The campaign
pages are in `lp/` and no route serves them.

Confirm which entrypoint the build closure contains:

```bash
cd $APP/svelte/.svelte-kit
grep -rlF dispatcher.etzhayyim.com cloudflare output   # -> nothing
grep -rlF mcp.etzhayyim.com        cloudflare output   # -> output/server/entries/endpoints/xrpc/...
```

`src/app.ts`'s markers are absent; the SvelteKit BFF's are present. That is the
divergence the README describes, reproduced.

Finally, the XRPC route:

```bash
curl -sS -X POST -H 'content-type: application/json' -d '{}' \
  http://localhost:4319/xrpc/com.etzhayyim.apps.gamePlayUploader.ping
```

Observed: `{"message":"Internal Error"}`. Correct — the upstream MCP router host
is NXDOMAIN (step 1). This path cannot succeed anywhere until that host exists.

Stop the preview with:

```bash
pkill -f "preview --port 4319"
```

Not `pkill -f "vite preview …"` — that matches nothing. `pnpm` execs
`…/vite/bin/vite.js preview --port 4319`, so `vite` and `preview` are not
adjacent on the command line and the obvious pattern silently kills nothing and
exits 1. Two processes match the form above (the `pnpm` wrapper and `vite.js`);
both need to go.

## 5. Validate the deploy config without publishing (≈10s)

```bash
cd $APP
wrangler deploy --dry-run --outdir /tmp/gpu-dryrun
```

Observed: `Total Upload: 418.93 KiB` (gzip ≈94.5 KiB — it moves by ~10 bytes
between builds), a binding table listing `env.ASSETS` and the nine `APP_*` /
`AGENTGATEWAY_MCP_ROUTER_URL` vars, then `--dry-run: exiting now.`

Note what is **absent** from that table: `DISPATCHER_INTERNAL_SECRET`, which
`src/app.ts` reads. Another view of the same divergence.

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

`.gitignore` covers the ~315 MB of `node_modules/`, `.svelte-kit/` and
`.wrangler/` these steps create, so `git status` stays readable. Two files do
show up untracked:

```
?? appview/…/pnpm-lock.yaml
?? appview/…/svelte/pnpm-lock.yaml
```

That is deliberate, not an oversight. Neither lockfile is committed today, and
committing them pins dependency versions the repo currently leaves floating —
a real decision, so it is left to whoever makes it rather than hidden by an
ignore rule.

## What this quickstart does not cover

- **The campaign logic.** Not in this repo — it lives in the kotodama ingest
  module and the BPMN contracts named in `src/app.ts`.
- **Resolving the entrypoint divergence.** Documented in the README, not fixed.
  Fixing it means deciding whether the dispatcher facade or the MCP BFF is
  authoritative, which changes behaviour.
- **The open items in `MIGRATION-TODO.md`** — the substrate-boundary checklist
  from the extraction. Only the ad-pixel item is closed.
