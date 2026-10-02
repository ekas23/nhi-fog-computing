# Start here

This folder is the complete project — code, real data, trained model,
results, dashboard, and documentation. Open it as your VS Code workspace
root (`File > Open Folder...`).

## Push this to GitHub from VS Code

1. Open this folder in VS Code.
2. Open the built-in terminal (`` Ctrl+` ``) and run:
   ```bash
   git init
   git add -A
   git commit -m "Adaptive Node Health Index for fog computing"
   ```
3. Create an empty repository on GitHub (no README/license — this folder
   already has both; adding them on GitHub too will cause a conflict).
4. Back in the VS Code terminal:
   ```bash
   git remote add origin https://github.com/ekas23/YOUR_REPO_NAME.git
   git branch -M main
   git push -u origin main
   ```
   VS Code's Source Control panel can do steps 2–4 through the UI instead,
   if you prefer clicking over typing.

## For your review

- **`docs/RESULTS.md`** — the full methodology and every real number.
- **`dashboard/index.html`** — double-click to open in a browser, or use
  VS Code's Live Server extension. This is what to show live.
- **`docs/`** — the six planning docs (PRD, Architecture, Rules, Phases,
  Design, Memory) if asked how the project was planned.

## If you need to hand this to Claude Code later

Paste **`MASTER_PROMPT.md`** as your first message, with this folder open.
It has exact setup commands, reproduction steps, and the real numbers to
verify against.

## One thing before you push

`data/nasa_mat/` (19 files, ~122MB) is real NASA battery data included so
everything runs standalone. GitHub is fine with this in a single push, but
if you'd rather keep the repo lean, `.gitignore` already excludes this
folder by default — delete it from `.gitignore` first if you want the data
tracked in git (steps above assume you DO want it tracked, since you asked
for "everything in a folder").
