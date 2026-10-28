# MUSEWARE.md — The Complete Build Specification

**Status:** Authoritative spec. Any agent (or human) MUST be able to build the entire
product from scratch to finish using only this file. Nothing here requires access to
any prior conversation. Where a decision was painful to learn elsewhere, the lesson is
recorded as a **LAW** — do not relitigate laws during implementation.

**Product one-liner (the pitch):**
> Museware is the canvas where Meta's muse builds with you: every AI action is a
> reviewable, undoable operation on the same drag-and-drop canvas you edit by hand.

**Context for the builder:** this is for a Meta AI hackathon. One model family (muse,
including the top "muse code" tier) does all the work. ~$160 API credit (functionally
unlimited: one full app build costs well under $1). The app is a **web app only**.
"Mobile" is an export story (PWA install / Capacitor wrap), never a second pipeline.

**Reference product (yardstick only, NEVER copy code):** Sketchware Pro — an Android
visual builder with an AI agent. Its agent patterns (tool loop, blueprint flow,
verification gate, bounded context, board/stop UX) were field-tested for weeks against
real quota exhaustion, model drift, and crashes. Those patterns are distilled below as
laws. Sketchware Pro is source-available, NOT open-source — zero code reuse.

---

## 0. The Laws (non-negotiable, learned from field failures)

1. **The model never types UI — it calls tools.** Every canvas change is a tool call
   with JSON arguments. Free-form "here is the HTML:" answers are rejected. A small
   model WILL type its answer as markdown if you let it (proven: it typed colour
   options as text despite explicit instructions). The defence is architecture, not
   instructions.
2. **Batched operations.** One tool call may carry ALL widget operations for a screen.
   Never one-widget-per-call (a 20-widget screen at 1 call/widget is 20 requests —
   minutes of dead air). Prompt the model explicitly: "one edit call with ALL the
   add operations, then one styling call."
3. **Decide up front, fill in blanks.** Blueprint first (screens + components + hard
   LOOK numbers), user approves, then filling happens. Never let the model improvise
   the whole app turn-by-turn.
4. **Constrained vocabulary.** The model picks components from the fixed library
   (Section 4) and colour tokens from the design system (Section 5). It never invents
   arbitrary CSS hex values when a token exists.
5. **Verify before show.** After the model finishes a work package, render the result
   and make the model review it (PASS / PROBLEMS). Failed verification gets ONE
   rebuild attempt. This gate is the difference between "demo junk" and "demo good."
6. **Bound every prompt.** Design-token sheet for the AI ≤ 1800 chars. Tool results
   ≤ 8000 chars (older ones in history compacted to a 160-char remembrance). History:
   keep the last 8 messages, trim assistant messages > 700 chars to head+tail.
   The full design system NEVER enters a prompt — the summary sheet does.
7. **Everything visible, everything stoppable.** Progress lines with a 30-second
   heartbeat ("Still working… 1m30s. Silence is normal."), N-of-M progress, a Stop
   button that is ALWAYS on screen while the agent runs, per-step undo.
8. **Persist the instant it exists.** The blueprint, the token sheet, the op log —
   saved on CREATION, not on completion. A killed browser tab must never lose a
   generated artifact ("no way to get back the blueprint created" was a real bug).
9. **One pinned model per role.** No router, no Auto mode, no failover zoo (field
   data: 135 failovers in one session, design redone 3× by 3 different models).
   `VITE/code model = muse code (top tier)` for all tool work; plain muse for chat.
   Fixed forever in config.
10. **Pace requests.** ≥ 4000 ms between requests, hard per-turn op cap (24 tool
    calls/turn, then rollover to a new turn). One in-flight request at a time.
11. **Cards leave on answer.** Any decision card the user taps disappears from the
    chat, leaving a one-line record ("✓ Picked: Sunny Playground"). Dead clickable
    cards are a bug.
12. **The agent stops at creation milestones.** After scaffolding a project/screen,
    the agent STOPS and hands control to the user (how-to-build card). The agent must
    never run straight into building — "why does it go ahead and start building?!!"
    was a real complaint. Gate it in the tool layer: after `scaffold_project`, builder
    tools refuse until the user picks a mode.
13. **Answer from receipts.** Never claim a change happened unless the applied op log
    shows it. Progress lines come from real tool activity only.
14. **JSON is banned from chat text.** The model's user-facing text is prose. Data
    flows in tool arguments only. If the model prints JSON in a reply, the turn is
    retried once with a correction note.

---

## 1. Stack & Project Layout (pinned — do not substitute)

```
React 18 + TypeScript + Vite 5        (demo-fast dev, easy deploy)
Zustand                               (state; ONE store, no redux ceremony)
dnd-kit                               (drag & drop; accessible, transform-based)
 nanoid                                (ids)
zod                                   (schema validation for tool args & project files)
No Tailwind, no CSS-in-JS libs.       Generated apps use plain CSS + custom properties
                                      (design tokens). Keeps codegen output clean and
                                      portable to PWA/Capacitor later.
```

```
museware/
  index.html
  vite.config.ts
  .env.example                # MUSE_API_KEY= , MUSE_BASE_URL= , MUSE_CODE_MODEL= , MUSE_CHAT_MODEL=
  src/
    main.tsx
    App.tsx                   # layout shell (see §9.1)
    store/
      useProject.ts           # the component tree + screens + tokens (single source of truth)
      useAgent.ts             # agent turn state machine, streaming, op queue
      useHistory.ts           # op log, undo/redo
      useUi.ts                # selection, panels, preview mode, modals
    types/
      tree.ts                 # ComponentNode, ScreenDoc, ProjectDoc, Op (§2, §3)
      tokens.ts               # DesignTokens (§5)
      tools.ts                # ToolCall/ToolResult zod schemas (§7)
    muse/
      client.ts               # fetch wrapper (streaming SSE), pacing, backoff (§6)
      prompts.ts              # system prompts, review prompt, blueprint prompt (§8)
      loop.ts                 # turn lifecycle: call → validate ops → apply → verify (§8)
      tokenSheet.ts           # bounded AI-facing design summary (§5.4)
      projectIndex.ts         # bounded AI-facing canvas summary (§8.2)
      typedOptions.ts         # fallback parser for typed-out choice lists (§8.6)
    ops/
      apply.ts                # pure op→tree reducer + validation (§3)
      snapshot.ts             # undo snapshots (structural clone of tree)
      validate.ts             # zod validation + semantic rules (§3.3)
    canvas/
      Canvas.tsx              # renders the tree live (§9.2)
      NodeView.tsx            # recursive renderer + drop targets + selection
      drag.ts                 # dnd-kit wiring, container rules
    inspector/
      Inspector.tsx           # props/style editor generated from schema (§9.3)
      Fields.tsx              # schema-driven field widgets
    layers/
      Layers.tsx              # tree panel: reorder, rename, delete, duplicate
    agentrail/
      Rail.tsx                # chat + cards (§9.5)
      cards/                  # BlueprintCard, ChoiceCard, ColorCard, BuildCard, ErrorCard
    preview/
      RunMode.tsx             # interactive preview: links, local state, forms (§9.6)
    export/
      reactCodegen.ts         # tree → React components (§10.1)
      pwa.ts                  # manifest + service worker template (§10.2)
      zip.ts                  # download bundle
    persist/
      save.ts                 # autosave to IndexedDB + project file I/O (§11)
    design/
      tokens.css              # Museware's own UI theme (dark, like a pro tool)
  public/
```

**Dev/proxy rule (LAW):** the API key lives in `.env`, read ONLY by a Vite dev-server
middleware (`vite.config.ts` `server.proxy` → `/api/muse` → provider) or, in
production, a tiny serverless function. The browser never sees the key. The canvas
app calls `/api/muse/chat` (same-origin, relative URL — never localhost).

---

## 2. Data Model — the JSON Component Tree (single source of truth)

```ts
// types/tree.ts
type NodeId = string;            // "btn_save" style ids — see §3.2 naming

interface ComponentNode {
  id: NodeId;                    // unique per project, stable across ops
  type: ComponentType;           // from the fixed library (§4)
  props: Record<string, unknown>;// schema-checked per type (§4)
  styles: StyleSet;              // see below — token references FIRST
  children: ComponentNode[];     // containers only
}

interface StyleSet {
  // every value is either a token reference "var(--...)..." or a literal
  padding?: string;  margin?: string;  gap?: string;
  background?: string;              // e.g. "var(--openui-accent)" or "#FF0000"
  color?: string;
  fontSize?: string;  fontWeight?: string;
  borderRadius?: string;
  width?: string;  height?: string; // "match" | "hug" | "<px>" | "<%>"
  align?: "start"|"center"|"end"|"stretch";
  [k: string]: string | undefined;
}

interface ScreenDoc {
  id: string;                    // "home", "details" (slug)
  name: string;                  // "Home"
  root: ComponentNode;           // type:"screen" node, layout:"flex-col"
  entry?: boolean;               // the screen the app opens on
}

interface ProjectDoc {
  format: "museware/1";
  name: string;
  screens: ScreenDoc[];
  tokens: DesignTokens;          // §5
  opLog: OpEntry[];              // full history (append-only, for undo + replay)
  blueprint?: { text: string; decided: boolean; updatedAt: number };
  updatedAt: number;
}
```

Rendering rule: the Canvas renders `ProjectDoc` live; there is NO other representation.
The AI edits it only through ops; the user edits it only through the same ops
(drag/inspector produce the same op types — this is what makes everything undoable
and gives the verify gate one thing to inspect).

---

## 3. The Op Model (every change is an op)

### 3.1 Op types
```ts
type Op =
  | { op: "add"; parentId: NodeId; index?: number; node: ComponentNode }
  | { op: "remove"; id: NodeId }
  | { op: "move"; id: NodeId; parentId: NodeId; index?: number }
  | { op: "setProps"; id: NodeId; props: Record<string, unknown> }   // merge
  | { op: "setStyles"; id: NodeId; styles: StyleSet }                // merge
  | { op: "setText"; id: NodeId; text: string }                      // text-ish nodes
  | { op: "rename"; id: NodeId; name: string }
  | { op: "addScreen"; screen: ScreenDoc }
  | { op: "removeScreen"; id: string }
  | { op: "setTokens"; patch: Partial<DesignTokens> };               // §5
```

### 3.2 Id naming (enforced by validate.ts)
Pattern `^[a-z][a-z0-9_]{2,30}$`, semantic prefix by kind: `btn_ txt_ img_ card_ row_
col_ input_ nav_ hero_ list_ chip_`. Uniqueness per project. On collision the op fails
with `E_NAME_TAKEN` and the model is told to pick another (suggestions included in the
error). If the model omits an id, the app generates one (`btn_3f2a`) — never block.

### 3.3 Validation (before ANY op applies)
- zod structural check → `E_BAD_OP` with the zod message.
- Semantic rules → `E_NO_PARENT` (add/move target missing), `E_NOT_CONTAINER`
  (child under a leaf — see §4 kinds), `E_NAME_TAKEN`, `E_SCREEN_MISSING`,
  `E_TOKEN_UNKNOWN` (style references an undefined token), `E_DEPTH` (tree > 8 deep),
  `E_COUNT` (screen > 120 nodes).
- On ANY failed op in a batch: apply nothing from that batch; return the error list to
  the model for one correction round. (All-or-nothing batches keep the canvas sane.)
- **Snapshot before every applied batch** (structural clone) → undo stack. Snapshot
  persisted with the op log entry (persist-on-create law).

### 3.4 OpEntry (the log)
```ts
interface OpEntry { at: number; source: "agent"|"user"; batch: Op[]; summary: string }
// summary example: "add 6 components to home + style hero card"
```
The op log IS the undo history, the crash-recovery replay, and the receipts the agent's
progress lines are generated from.

---

## 4. The Component Library (fixed vocabulary — the model picks from THIS, only THIS)

All styling resolves through design tokens (§5) by default; the model overrides via
`styles` only when the request demands it. Every component lists: kind (container or
leaf), props schema (zod-able), defaults.

**Layout containers** (children allowed):
- `screen` — full-page flex-col; props: `title`, `layout` ("flex-col"|"flex-row"), `scroll` (bool, default true)
- `row` — flex-row container; props: `gap`, `align`, `wrap`
- `col` — flex-col container; props: `gap`, `align`
- `grid` — CSS grid; props: `columns` (2–6), `gap`
- `card` — elevated rounded surface; props: `title?`, `pad`
- `hero` — full-width banner; props: `title`, `subtitle`, `image?`
- `tabs` — tab strip + panels; props: `items: string[]`
- `section` — titled grouping; props: `title`

**Text & display** (leaf): `heading` (`text`, `level` 1–4), `text` (`text`), `label`,
`badge` (`text`, `tone`: accent|success|danger|neutral), `divider`, `icon`
(`name` from a fixed Lucide set, `size`), `image` (`src`, `alt`, `fit`), `avatar` (`src`, `size`),
`progress` (`value` 0–100), `spinner`.

**Input** (leaf): `button` (`text`, `action` §4.1, `variant`: primary|secondary|ghost|danger),
`iconbutton` (`icon`, `action`), `input` (`placeholder`, `bindKey`), `textarea`,
`select` (`options: string[]`, `bindKey`), `checkbox` (`label`, `bindKey`),
`switch` (`label`, `bindKey`), `slider` (`min`,`max`,`step`,`bindKey`), `datepicker` (`bindKey`).

**Navigation** (leaf): `navbar` (`items: string[]` — top app bar with menu),
`bottomnav` (`items`), `link` (`text`, `to: screenId`), `fab` (`icon`, `action`).

**Data display** (leaf, mock-data driven for the demo): `list` (`itemTemplate` — a
mini node map repeated over mock items; render first 5), `table` (`columns`, `rows` ≤ 10),
`stat` (`label`, `value`, `delta`), `chart` (`kind`: bar|line|spark, `data` ≤ 12 points).

### 4.1 Actions (the interactivity model — deliberately small)
`action: { type: "navigate"; to: ScreenDoc["id"] } | { type: "setState"; key: string; value: unknown } | { type: "toast"; text: string } | { type: "openUrl"; url: string }`
RunMode (§9.6) interprets actions client-side. That is the WHOLE interactivity surface
for the hackathon. No custom code, no arbitrary JS — constrained vocab beats live
freedom (and makes verification tractable).

---

## 5. Design System & Tokens

### 5.1 Token schema (compatible with the existing preset files)
Use the `openui-1.0` shape EXACTLY as the import format — the user already owns two
presets in this format (iPhone 17 Pro Max, Windows 11 Pro — Claude-made, with embedded
`foundations` sheets):

```jsonc
{
  "title": "...", "specVersion": "openui-1.0",
  "openuiTheme": {
    "brand_color": "#FF8A3D", "secondary_color": "#6366F1",
    "neutral_scale": "warm", "corner_profile": "soft",
    "density": "comfortable", "theme_mode": "DayNight",
    "radii_dp":   { "card": 26, "button": 999, "input": 14, "chip": 999, "tag": 8, "callout": 20 },
    "metrics_dp": { "screen_pad": 20, "section_gap": 24, "card_pad": 16,
                    "button_height": 50, "input_height": 50, "chip_height": 34, "row_height": 60 },
    "light_colors": { "openui_bg": "...", "openui_fg": "...", "openui_sunk": "...",
      "openui_elevated": "...", "openui_highlight": "...", "openui_accent": "...",
      "openui_accent_pressed": "...", "openui_accent_subtle": "...",
      "openui_text_on_accent": "...", "openui_secondary": "...",
      "openui_text_on_secondary": "...", "openui_text_primary": "...",
      "openui_text_secondary": "...", "openui_text_tertiary": "...",
      "openui_text_brand": "...", "openui_border_default": "...",
      "openui_border_interactive": "...", "openui_border_accent": "...",
      "openui_info_bg": "...", "openui_info_text": "...", "openui_info_border": "...",
      "openui_success_bg": "...", "openui_success_text": "...", "openui_success_border": "...",
      "openui_alert_bg": "...", "openui_alert_text": "...", "openui_alert_border": "...",
      "openui_danger_bg": "...", "openui_danger_text": "...", "openui_danger_border": "..." },
    "dark_colors": { …same 30 keys… },
    "foundations": { …optional deep sheet: typography styles, motion, materials — RIDE ALONG, never stripped… }
  },
  "tokens": [ { "key": "card", "label": "…", "kind": "card", "props": { "padding": "16", … } }, … ]
}
```
Import: accept this file AS IS; unknown top-level sections (foundations, motion,
materials, type scales) are **preserved on save** (never stripped — that was a real
bug: the save path silently dropped everything the engine didn't model).

### 5.2 Web materialization
`tokens → CSS custom properties` on the canvas root:
`--openui-bg, --openui-accent, …` (light + `[data-theme="dark"]`), plus
`--radius-card`, `--pad-screen`, `--gap-section`, `--h-button`… Default components
reference ONLY these variables. Changing `brand_color` repaints the whole canvas —
"colour propagation is the expectation."

### 5.3 Component token defaults
Ship ~14 core component tokens (`screen, card, sunk_card, primary_button,
secondary_button, input, chip, list_row, title, body, nav_bar, tab_bar, fab, dialog`)
derived from the theme (radii/metrics/palette) — a component with no style overrides
renders from its token defaults. Preset `tokens[]` entries override by `key`.

### 5.4 The AI-facing token sheet (LAW: hard cap 1800 chars)
`tokenSheet.ts` renders, from the CURRENT tokens:
title; brand/secondary; neutral/corner/density/mode; fonts; up to 14 light + 14 dark
colour key=value pairs; the core component tokens (key + up to 6 props each, core
first, "+N more components (in the Design System panel)"); and ≤ 2 distilled lines
from foundations (font family, type range "12–34sp (11 styles)", grid, margin, tap
target, motion ms). THIS STRING is the only design context any prompt ever sees.
Read component tokens by their **`key`** field (a previous implementation read a
nonexistent `name` field and silently showed zero component tokens — do not repeat).

---

## 6. Muse API Client (`src/muse/client.ts`)

- Endpoint: the Llama API (OpenAI-compatible chat-completions w/ tools + streaming)
  via the server proxy (`POST /api/muse/chat`). Config via env: `MUSE_BASE_URL`,
  `MUSE_API_KEY`, `MUSE_CODE_MODEL` (tool work — pin the top muse code model),
  `MUSE_CHAT_MODEL` (rail chat).
- Streaming SSE with tool-call delta accumulation; non-streaming fallback flag.
- **Pacing:** ≥ 4000 ms between request STARTS (global). One in-flight request.
- **Backoff:** 429/5xx → exponential (2s/8s/30s) with an honest rail line
  ("Rate limited — waiting 30s. Silence is normal."); never silently retry-loop
  (cap 3, then an error card with a Retry button).
- **Cancels:** Stop button aborts the fetch (AbortController), marks the turn
  cancelled, keeps ops already applied, posts "Stopped — X of Y done."
- Every request logs: model, prompt chars, tool names requested, latency → a tiny
  debug panel (receipts law).

---

## 7. The Tool Surface (complete — the model gets these five and NOTHING else)

```
read_canvas()                       → compact project index (§8.2), ≤ 4000 chars
set_design_tokens(patch)            → §5 patch; validates hexes; repaints canvas
propose_blueprint(blueprint_text)   → starts the approval flow (§8.4); does NOT build
edit_canvas(screen_id, ops[])       → §3 batched ops, all-or-nothing, ≤ 40 ops/call
finish(summary)                     → ends the turn with a 2-sentence user summary
```
All calls validated by zod (§3.3). `edit_canvas` with ops referencing unknown
screens/tokens/components fails with the E_ codes and the model gets exactly one
correction round per batch. The system prompt instructs: "edit_canvas calls are BIG:
one call carries ALL the add ops for a screen; a second carries the styling. NEVER one
widget per call." (law 2 exists because models do it unless told, every time).

**Structured output defence (law 1):** request tool-calls natively; if a reply
contains fenced JSON/HTML instead of tool calls, retry ONCE with a correction message.
If the model needs to offer CHOICES (styles/directions), it must use
`propose_blueprint` or the choice card protocol (§8.6) — if it still types options as
text, `typedOptions.ts` parses 2–6 numbered options (≥2 with hex codes) from the text
and renders tappable choice chips (coloured dots, hex-stripped labels, tap = send that
option as the user's message). The fallback exists because instructions alone were
proven insufficient.

---

## 8. The Agent Loop (`src/muse/loop.ts`)

### 8.1 Turn lifecycle
```
user message / card tap / scaffold event
→ assemble context (§8.2, LAW 6 caps)
→ [ paced, streaming ] muse call (tools on, CODE model)
→ for each tool call: validate → apply/queue → tool result (capped)
→ repeat while tool calls arrive (cap 24/turn → rollover: auto-continue new turn,
   progress line first: "Continuing — part 2")
→ verify gate (§8.3) when the turn touched the tree
→ finish(summary) → final rail line; op log persisted; tokens saved
```
Heartbeat: if no visible line for 30 s, the rail posts "Still working… 1m30s."
The rail shows one live line per tool call ("Adding hero + 6 components to home…"),
throttled: max one line per 12 s per work package (chatter kills trust less than
silence, but drowning the chat kills it entirely).

### 8.2 Context assembly (per request)
1. System prompt (§8.5) — static.
2. Token sheet (§5.4) — only the string.
3. Project index (`read_canvas` cached per turn): screens → node ids+types (≤ 18
   ids/screen), actions, bound state keys; plus the blueprint text if present
   (truncated 2000). NO full tree dump.
4. History: last 8 messages, assistant messages > 700 chars trimmed head+tail,
   tool results > 8000 chars truncated; results older than the last 24 calls replaced
   by a 160-char remembrance ("edit_canvas ok: 6 added, 2 styled — full result omitted").
5. The user's message (+ attachments: images described by muse vision on the CHAT model,
   ≤ 2 images, described into text before the code model sees the turn).

### 8.3 The Verify Gate (law 5 — non-negotiable, the best-paid pattern)
After a turn that applied tree/tokens changes:
1. Render the changed screens to a plain-text description (screens → tree outline with
   resolved styles; ≤ 4000 chars).
2. Ask the CHAT model, as reviewer: "A builder was asked: <user ask>. It produced:
   <outline>. Answer exactly PASS, or PROBLEMS: a numbered list. Visual quality
   counts: cramped/default-padded layouts, ignored LOOK numbers, off-palette colours
   are PROBLEMS, not PASS."
3. PASS → done. PROBLEMS → ONE rebuild round: same ask + "A PREVIOUS ATTEMPT HAD
   PROBLEMS (fix ALL): …". After the rebuild, ship regardless and show the reviewer's
   remaining notes honestly under the result. (Max attempts = 2 total.)

### 8.4 The Blueprint flow (law 3)
- Trigger: user asks for an app/screen (or picks "Blueprint first").
- The agent calls `propose_blueprint`: plain text, sections `RULES:` (id style, one
  shared state file key namespace), then per screen `SCREEN: <Name>` + component
  lines (`- btn_next (button) opens details`) + a **`LOOK:` line with HARD numbers**
  (padding/gaps/radii/type sizes — from the token sheet), then `STATE:` and `WIRING:`
  (what navigates where). ≤ 600 words.
- On arrival: persist IMMEDIATELY (law 8), render an **approval card** in the rail:
  screen thumbnails rendered live from a first-pass tree interpretation of the
  blueprint + Build it / Adjust it (inline input) / Cancel. Card LEAVES on tap
  (law 11); adjust round posts a NEW card; 30-min silence = honest cancel (kept
  blueprint, reopenable from history).
- Build it → the fill queue: screens filled one `edit_canvas` per screen + one wiring
  pass (actions/navigation), progress card N-of-M with Stop (§9.5), verify gate at the
  end. "Fast mode" skips per-screen review, keeps the final gate.
- **The stop law (12):** after `scaffold_project`-equivalent (first screen set created
  by the agent), builder tools refuse (`E_BUILD_CARD`) until the user answers the
  how-to-build card; tapping it cancels the agent's still-running turn first.

### 8.5 System prompt (assembled, ~1200 chars max — pin exactly this spirit)
```
You are the muse builder inside Museware. You change the canvas ONLY through tools:
read_canvas, set_design_tokens, propose_blueprint, edit_canvas, finish.edit_canvas
ops are BIG — all adds for a screen in one call, then one styling call; never one
component per call. Components come from the library list; colours come from the
token sheet (hex literals only when the user explicitly demands a custom colour).
First agree the look (offer 2–4 whole directions with hexes), save it with
set_design_tokens, propose_blueprint, then fill in after approval. Never print HTML,
JSON, or code in your reply — prose only. The user's saved design system is LAW:
use its tokens, never invent replacements.
```

### 8.6 Choice protocol (the colour moment)
The model asks the user to pick between 2–4 whole directions by calling
`propose_blueprint` (style section) OR `set_design_tokens` preview variants rendered
as a **choice card**: one row per option, up to 4 swatch dots per row (hexes from the
option text; word→colour fallback map for "pastel pink" etc.), display labels with hex
codes STRIPPED (the dots show the colour; the codes go to the model, not the user's
eyes). Tap = the answer; the card leaves; "✓ Picked: …" line stays.
The **colour card** (single pick): hex input + 8 quick swatches + three buttons —
"Everywhere (app theme)" (`applied_everywhere` → full token repaint), "Just this
element" (sets the one node's style), "Cancel". A non-parsing hex keeps the card open
with an honest line. (This was two popups chained in the old product; ONE card here.)

---

## 9. The Editor (UI of Museware itself)

### 9.1 Layout
```
┌──────────────────────────────────────────────────────────────┐
│ topbar: logo · screen tabs (＋) · viewport: [📱|💻|🖥] · [▶ Run] │
│         [↩ undo] [↪ redo] · [Export ▾] · agent status + STOP   │
├──────────┬────────────────────────────────┬──────────────────┤
│ Layers   │ Canvas (live ProjectDoc render,│ Inspector        │
│ tree,    │ drop zones, selection outline, │ (schema-driven   │
│ reorder, │ inline text edit on dbl-click) │ props + styles   │
│ rename   │                                │ + tokens button) │
├──────────┴────────────────────────────────┴──────────────────┤
│ agent rail (right dock or bottom sheet): chat + cards + dots │
└──────────────────────────────────────────────────────────────┘
```
- Dark pro-tool theme (Museware's own tokens — dogfood §5).
- **STOP is always visible while the agent runs** (law 7). Status pill: "Building
  screens 2 of 4 — Muse is working… [⏹ Stop]".
- Keyboard: `⌘/Ctrl+Z` undo, `⇧⌘Z` redo, `⌫` delete selection, `⌘D` duplicate,
  `⌘S` save project file. Empty states teach in one line each.

### 9.2 Canvas
- Recursive `NodeView` renders the tree with resolved styles (tokens → CSS vars).
- dnd-kit: drag from a component palette (grouped by §4 categories) OR drag existing
  nodes (move). Drop indicators: before/after/inside (containers only — enforce §3.3
  rules at drag time, so illegal drops are visibly impossible).
- Selection: click → inspector; outline uses `--mw-accent`. Double-click text nodes →
  inline edit (commit = `setText` op). Hover shows the node's id chip (ids matter to
  the agent round-trips).
- User drags produce the SAME ops as the agent — one history, one undo.

### 9.3 Inspector
Schema-driven from §4: props fields by type (text/number/select/toggle/screen-picker/
action-picker), styles section (token pickers FIRST: colour = token swatch grid with
custom hex as the last option; spacing/radius steppers bound to token defaults),
bindings list (`bindKey`s on the screen). "Ask muse to restyle this" button → prefills
the rail with a scoped request.

### 9.4 Screens & viewport
Screen tabs add/duplicate/delete/rename screens. Viewport switch resizes the canvas
frame (390×844 / 768 / 1280) — the tree is responsive (flex/grid), not absolute.

### 9.5 Agent rail & cards (port of the field-proven set — richness identical)
- **How-to-build card** (after scaffold/milestone): "Build it — how?" with Blueprint
  first (recommended) / Steady screens / Fast screens / Not now. Leaves on tap.
- **Blueprint approval card**: rendered screen thumbnails + rules text (8-line clamp)
  + Build it / Adjust it / Cancel. New card per adjust round.
- **Progress card** (fill queue): "🛠 Screens 1 of 4 — Home…", live lines (12 s
  throttle), ⏹ Stop, tap a DONE screen to expand: summary + Undo this screen /
  Rebuild this screen / Keep it (snapshots make all three real). FAILED screen rows
  show the reviewer's PROBLEMS + Rebuild.
- **Choice card / Colour card** (§8.6). **Typing-dots bubble** with the heartbeat
  text during long agent work ("🛠 Building 2 of 4 — Details…"), settling to a final
  line. **Error card** with the honest message + Retry.
- All cards: leave-on-answer (law 11); all rail content persisted with the project
  (restore on reload — cards older than the session restore as their decided/final
  state, never re-askable ghosts).

### 9.6 Run mode
Toggles the canvas to a real running app: navigation actions switch screens, state
actions mutate a per-run store (`bindKey`s read/write it), toasts toast, forms bind.
This is the judge-moment feature: "the AI built this, and it actually RUNS."

---

## 10. Export

### 10.1 React codegen
`ProjectDoc → React components` (one component per screen; styles → inline style
objects referencing the exported `tokens.css`; actions → `useState`/`useNavigate`
equivalents in a 40-line runtime stub). Deterministic, readable, no AI in the loop.
Round-trip guarantee: exported projects re-import as `museware/1`.

### 10.2 PWA (the "mobile app" story)
Zip bundle: `index.html` + built JS/CSS + `manifest.webmanifest` (name/theme/icons
from tokens) + one-file service worker (offline shell). Button: "Install as app" in
the exported README. Optional appendix: Capacitor wrap instructions (out of demo
scope, two paragraphs max).

---

## 11. Persistence & Recovery
- IndexedDB autosave: debounced 800 ms on every applied batch + rail append.
  Persist-on-create law: blueprint card content, token changes, and op log entries are
  saved the moment they exist.
- Reload recovery: replay = load ProjectDoc; rail restores; an agent mid-turn at kill
  time restores as "interrupted" (honest line: what the op log shows was applied).
- Project file: download/upload `.museware.json` (the ProjectDoc). Import validates
  with zod; unknown fields preserved.

---

## 12. Milestones (hackathon clock; acceptance criteria are testable)

- **M0 (h1–2) Skeleton:** Vite+TS+Zustand runs; ProjectDoc store + ops reducer +
  undo; canvas renders a hardcoded tree; palette drag-drop with container rules.
  ✅ drag a button into a card, undo restores, reload persists.
- **M1 (h3–4) Muse loop:** proxy + client + pacing; the five tools with zod;
  system prompt; rail with live lines + dots + stop. ✅ "make a landing page with a
  hero and 3 feature cards" → one batched edit_canvas → canvas shows it; receipts in
  the op log.
- **M2 (h5–6) Tokens & presets:** token store + CSS materialization; preset import
  (the two openui-1.0 preset files load day one, foundations preserved); token sheet
  generator (1800 cap, key-field test). ✅ set brand colour → whole canvas repaints.
- **M3 (h7–8) Blueprint flow:** propose_blueprint → persist-on-create → approval card
  with thumbnails → fill queue (N-of-M + Stop + per-screen undo) → verify gate with
  one rebuild round. ✅ full "habit tracker for my daughter" run end-to-end.
- **M4 (h9) Inspector & layers polish:** schema-driven editing; user drags = ops.
- **M5 (h10) Run mode + export:** actions run; React codegen + PWA zip.
- **M6 (h11) Hardening:** reload recovery, choice/colour cards with swatch dots +
  typed-options fallback, error cards, heartbeat, caps audit.
- **M7 (h12) Demo:** script below; recorded fallback; README with the architecture
  diagram.

**Demo script (5 min):** 1) blank canvas → one prompt → blueprint card appears with
rendered thumbnails → approve → screens fill with N-of-M visible, Stop shown.
2) Tap a colour on a choice card → canvas repaints everywhere. 3) Drag a button by
hand next to the AI's work → undo both histories together. 4) Run mode: click through
the app. 5) Export PWA → open on a phone. Fallback: pre-recorded run.

---

## 13. Test Checklist (numbered, from the field scars — run ALL before demo)

1. One-prompt full build: zero unbatched add calls in the log (grep the op log —
   any `edit_canvas` with a single add op = law-2 violation, tighten prompt).
2. Kill the tab mid-blueprint → reopen → blueprint still there (persist-on-create).
3. Kill mid-fill → reload → honest "interrupted" state, N of M applied, no ghost
   pending cards.
4. Stop mid-fill → built screens stay, queue clears, final line exact.
5. Token import (both preset files) → foundations sections survive save→reload.
6. Token sheet length ≤ 1800 for both presets; component tokens non-empty
   (key-field regression).
7. Model types colour options as text (simulate by prompting) → typed-options chips
   appear, tap sends the option, dots show, labels hex-free.
8. Colour card: bad hex → card stays, honest line; Everywhere → canvas repaints;
   Just this element → only that node.
9. Verify gate: force a cramped result (prompt for "no padding") → reviewer says
   PROBLEMS → one rebuild → remaining notes shown honestly.
10. Undo across agent+user ops mixed (5 deep) — each restores exactly.
11. Rate-limit simulation (429) → countdown line, no retry storm, no data loss.
12. Stop button reachable at ALL times while running (including mid-stream).
13. Run mode: every navigate/setState/toast action works; no console errors.
14. Export: codegen compiles, PWA installs on Android Chrome, icon = brand colour.
15. Cost receipts panel: one full build ≤ 150 requests, ≤ $1 (log proves it).

---

## 14. War Stories (one-line each — why the laws exist; cite in the README)
- 190 tool calls in ONE message (unpaced loop) → law 10.
- 135 failovers, design rebuilt 3× (Auto routing) → law 9.
- 429s cascading per-KEY across a whole model catalog → law 10 + pinned models.
- The pretty preview painted the spec while the text blueprint carried no numbers →
  LOOK lines with hard numbers (§8.4).
- Model typed colour options as chat text, tool never called → §7 structured-output
  defence + §8.6 fallback.
- Blueprint lost on process death ("no way to get back") → law 8.
- Crash: rail updated from the worker thread → §8.1 all-UI-on-main rule (web:
  never touch the store from a stream callback without the state library).
- "Didn't see the stop button… every other thing was trash" → law 7.
- Save path stripped unknown preset sections → §5.1 preservation rule.
- Agent ran straight into building after project creation → law 12 (E_BUILD_CARD).
- Dead cards stayed clickable after answering → law 11.
- Hex codes ate the labels ("Any need for those codes?") → display stripping §8.6.

---

## 15. Glossary
**Op** — the single atomic change unit (§3). **Batch** — an all-or-nothing list of
ops from one tool call. **Blueprint** — the up-front plan text + its approval card.
**Fill queue** — the screen-by-screen build after blueprint approval. **Token sheet**
— the ≤1800-char AI-facing design summary. **Verify gate** — render + model
self-review PASS/PROBLEMS with one rebuild. **Rail** — the agent chat dock. **Card**
— an interactive rail message that leaves on answer. **Receipts** — the op log; the
only source of truth for "did it happen."
