# game-play-uploader

**A recruitment surface for a gameplay-recording campaign.** It asks people to
play games they already own, upload the recordings, and be paid in Amazon gift
cards at ¥100/hour. The recordings are the point — they are the raw material for
a domain-coverage dataset. The repo holds the *surface*: four landing pages, a
Cloudflare Worker that fronts them, and the identity metadata that declares the
actor. **The campaign logic, the reward ledger, and the worker onboarding are
not here** (see [Where the logic actually lives](#where-the-logic-actually-lives)).

It is `kind :app` (`README.edn`), extracted verbatim from
`etzhayyim/root` at `60-apps/etzhayyim-project-game-play-uploader`
(`migration.edn`, source revision `f0f9f2aa67`).

## Status: not live — measured 2026-08-12

**Nothing this repo declares is reachable on the internet today.** This is not an
inference from the code; it is a DNS measurement taken at the tip this README
landed on (`9f30c92`):

| host | declared in | resolves? |
|---|---|---|
| `etzhayyim.com` | zone for both routes | **yes** (HTTPS 200) |
| `game-play-uploader.etzhayyim.com` | `wrangler.jsonc` route, `kotodama.jsonld` | **NXDOMAIN** |
| `gm3pup1d.etzhayyim.com` | `wrangler.jsonc` route, `APP_EMBED_URL` | **NXDOMAIN** |
| `dispatcher.etzhayyim.com` | `src/app.ts` upstream, `kotodama.jsonld` env | **NXDOMAIN** |
| `mcp.etzhayyim.com` | `AGENTGATEWAY_MCP_ROUTER_URL`, the deployed BFF's upstream | **NXDOMAIN** |
| `hc.etzhayyim.com` | **every call-to-action on all four landing pages** | **NXDOMAIN** |

The parent zone resolves and the measuring host's network was fine (a control
request to an unrelated host returned 200), so these are genuine absences, not a
local DNS failure. Re-measure before trusting this table:
`nbb docs/check-surface.cljs` (see the quickstart).

The consequences are concrete:

- **The app is not deployed.** Neither route host exists.
- **Both upstreams are absent**, so the `/xrpc/*` path cannot succeed even
  locally — a local `POST /xrpc/...` returns `500 {"message":"Internal Error"}`,
  which is the upstream fetch failing, not a bug in the route.
- **The funnel has no floor.** Every "登録する" button on every landing page
  points at `hc.etzhayyim.com`, which does not exist. If the surface were
  deployed as-is, every visitor who accepted the offer would land on nothing.

Nothing in this repo can fix the last one — `hc.etzhayyim.com` is another
system's responsibility. Treat it as the blocking dependency for launch.

## What is actually in here

Four separable pieces. Only the second one is deployed.

### 1. `appview/…/src/app.ts` — a thin-edge facade that is **not deployed**

A Worker handler that answers `/health` `/healthz` `/readyz` `/_app/meta` and
proxies `/xrpc/com.etzhayyim.apps.gamePlayUploader.*` to
`dispatcher.etzhayyim.com`, attaching an `x-internal-trust` secret.

It typechecks (`pnpm typecheck`, exit 0) and `tsconfig.json` scopes `tsc` to
exactly this file — so the check is real and it discriminates (breaking the
return type of `internalTrustSecret` produces `TS2322` at its call site and
returns).

**But `wrangler.jsonc` sets `main` to the SvelteKit build output, not this
file.** Verified against a real build: the deploy closure
(`.svelte-kit/cloudflare/` + `.svelte-kit/output/`) contains **none** of
`dispatcher.etzhayyim.com`, `com.etzhayyim.apps.gamePlayUploader`, or
`x-internal-trust`, and **does** contain the SvelteKit BFF's
`mcp.etzhayyim.com`, `sveltekit-edge-bff`, `x-etzhayyim-xrpc-method`.

So this file is typechecked, committed, and never shipped. `kotodama.jsonld`
disagrees with `wrangler.jsonc` about which entrypoint is authoritative:

| declares | entrypoint | framework | `/xrpc` upstream |
|---|---|---|---|
| `kotodama.jsonld` | `src/app.ts` | `ts-thin-edge` | `dispatcher.etzhayyim.com` |
| `wrangler.jsonc` | `svelte/.svelte-kit/cloudflare/_worker.js` | `sveltekit-edge-bff` | `mcp.etzhayyim.com` |

**This divergence is recorded, not repaired.** Resolving it means deciding which
entrypoint is authoritative, and that decision changes what the app *does* — it
is not a documentation change.

### 2. `appview/…/svelte/` — the SvelteKit BFF that **is** deployed

Two routes: `/` and `/xrpc/[...path]`. The latter wraps the request as a
JSON-RPC `tools/call` and forwards it to the MCP router.

`/` serves a **generated scaffold placeholder**, not the campaign. Verified by
running the production build locally: the page title is the package name
(`etzhayyim-wasm-game-play-uploader-gm3pup1d`) and the body reads *"No public
route is declared next to this app surface."*

The routes `kotodama.jsonld` advertises under `triggers.http.routes` are mostly
not implemented by the thing that ships. Measured against the built app:

| path | declared in `kotodama.jsonld` | actual |
|---|---|---|
| `/` | yes | **200** (scaffold placeholder) |
| `/health` `/healthz` `/readyz` | yes | **404** — implemented only in the undeployed `src/app.ts` |
| `/kids` `/adult-print` `/kids-print` | yes | **404** — no such route exists anywhere |

### 3. `lp/` — four standalone landing pages, wired to nothing

`adult.html` `kids.html` and print variants of each. They are complete, styled,
Japanese-language pages with real copy, and the kids variant has its own
guardian-consent flow. **They are not served by the app**: no route references
them, and a build of the client assets contains none of their content.

To see them you open the files. To ship them, something would have to route them.

### 4. Identity and provenance

`README.edn` `migration.edn` `kotodama.jsonld` `NOTICE` `MIGRATION-TODO.md`.
All three machine-readable files parse. `MIGRATION-TODO.md` carries the
etzhayyim substrate-boundary checklist from the extraction; the ad-pixel item is
closed (re-scanned clean 2026-05-23), the rest are open.

## Where the logic actually lives

The campaign, the reward arithmetic, and the upload handling are **not in this
repo**. `src/app.ts` names their homes:

- business logic — `40-engine/kotoba/crates/kotoba-kotodama/py/src/kotodama/ingest/game_play_uploader.py`
- process definition — `etzhayyim-root/00-contracts/bpmn/com/etzhayyim/gamePlayUploader`
- worker onboarding and payout — `hc.etzhayyim.com` (external, and absent)

This repo is the storefront. Read it as one.

## Boundary with the nearest repos

Several repos in this workspace are about games; this one is not about *making*
them.

- **`cloud-itonami/gameka`** — the business layer of game *making* (a studio's
  catalog of gameSpecs). **`kotoba-lang/game-production`** — the craft layer of
  the same. Both produce games. This repo consumes *recordings of people playing
  games that already exist*, and its product is the dataset, not a game.
- **`kotoba-lang/loop-game-autoplay`** — also produces gameplay, but from an
  evolved policy rather than a paid human. Same artifact, opposite source: that
  repo removes the human, this one recruits and pays them.
- **`kotoba-lang/game`, `kami-game-scene`, `host`** — engine-side `.cljc`
  libraries. No overlap; this repo contains no engine code.

## Operator entry point

**[`docs/operator-quickstart.md`](docs/operator-quickstart.md)** — every step in
it was executed against this tip. It says which commands succeed, what they
print, and which one was deliberately not run.

## Licence

Apache 2.0 with the etzhayyim Charter Compliance Rider v3.1 — see `NOTICE`.
