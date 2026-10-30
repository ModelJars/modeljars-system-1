# Code for "In-JVM Decisions That Need No API Key"

Every script the article quotes, runnable as-is. One command reproduces every local number in the
article on your machine; the hosted Jev column needs your own TypeSafe key and the VPS column a
Hetzner account.

## Prerequisites

- JDK 25 (Temurin or any other build) and [JBang](https://www.jbang.dev) 0.141 or later.
- About 3.2 GB of disk and a first-run download: the Harriet base (2.55 GiB) and Laya (449 MB)
  come from Maven Central into `~/.modeljars` and are verified against their digests on every open.
- 8 GB of heap for the Harriet scripts (set in each script's `//RUNTIME_OPTIONS`); 4 GB for Laya.
- Measured on macOS x86_64 and Linux x86_64. The runtime's Rust kernels are bundled for those;
  on another platform the diagnostics script tells you which backend you got.

## What is here

| file | article section | what it does |
|---|---|---|
| `HarrietDiag.java` | (not quoted) | Prints the backend the runtime chose and its environment. Run it first. |
| `HarrietHello.java` | Harriet from a JBang script | One open, three bare questions about one ticket. |
| `HarrietDemoTimed.java` | Give it a rubric; What does a decision cost? | The same three questions bare and with rubrics, each timed cold and warm, then all three in one `systemOne` call. |
| `LayaHello.java` | Laya, the small one | The same ticket through Laya's encoder route. |
| `HarrietGroundingEvaluator.java` | Fact-checking with a Noul | Spring AI's `Evaluator` contract answered by a local Noul; two answers, one grounded and one not. |
| `HarrietTicketsApp.java` | Triage as a service | A single-file Spring Boot 4.1.1 application: `Harriet.open()` as a bean, a `@Component` that asks queue, urgency and mood, six tickets. |
| `generated-harriet-demo.java` | Wrapping up | What `modeljars demo` writes for the Harriet entry (generated, not hand-written). |
| `jev/jev_column.py`, `jev/jev_tickets.py` | How do they stack up; Triage as a service | The identical state and question strings sent to hosted Jev, timed around each HTTP call. |
| `reproduce.sh` | all | Runs the six Java scripts in article order and keeps one log per script plus the machine description. |
| `vps/hetzner-run.sh` | What does a decision cost? | Runs `reproduce.sh` on a fresh Hetzner CCX33 and deletes it afterwards. |
| `vps/hetzner-record-tictactoe.sh` | A decision you can watch | Records the tic-tac-toe demo headless on a fresh CCX33 (Xvfb, software rendering) and brings the mp4 and GIF back. |

Versions are pinned in each script's `//DEPS` lines: `org.modeljars.composite:harriet:0.1.69`,
`org.modeljars:modeljars:0.1.69`, Laya `1.0.0-q8_0.1`, Spring Boot `4.1.1`, Spring AI commons
`2.0.1`. The `modeljars demo` command generates a script against the newest release instead.

## Run one script

```sh
jbang HarrietHello.java
```

The two Spring Boot scripts declare `package demo;` on purpose: in the default package, component
scanning walks Spring's own classes and fails on a missing servlet filter.

## Run everything

```sh
bash reproduce.sh            # logs in ./out, one per script, plus ENVIRONMENT.txt
```

The first script to touch each model pays for the download, so take open times from the second
run or from a script that runs later in the sequence. Verdicts and probabilities should match the
article's logs exactly on x86_64; they were identical on our two machines. Timings will not, and
that is the point of the laptop-versus-VPS table.

## The hosted column

```sh
export TYPESAFE_API_KEY=...   # your own key; it is read from the environment and never written
python3 jev/jev_column.py     # 16 calls
python3 jev/jev_tickets.py    # 12 calls
```

Or set the variable before `reproduce.sh` and both run at the end. The scripts pin `jev-1.13.0`;
`jev_column.py` falls back to `jev-latest` if the API stops serving that version, and records
which model answered. At the list price the 28 calls cost well under a cent.

## The VPS column

```sh
export HCLOUD_TOKEN=... HCLOUD_SSH_KEY=<key name in your Hetzner project>
bash vps/hetzner-run.sh       # creates a CCX33, runs reproduce.sh, copies ./out-vps back, deletes the server
```

The server is deleted by a trap on exit, including on failure. Only the Java scripts and
`reproduce.sh` are copied to it; the Jev key is never forwarded.

## Tic-tac-toe: a decision you can watch

`tictactoe/` is the visual demo and its measurement. Harriet plays O; before it moves, every
empty cell glows with the probability the model gives it. With `--jev` the same board and question
also go to TypeSafe's hosted Jev, named as such on screen: once Harriet's O has landed, a line says
what Jev would have played, its probability and its round trip, and a dashed ring marks Jev's cell
when the two differ. A score table keeps the session's comparison: minimax-optimal moves for each
arm, median time per move (Harriet in-process, Jev over the network), how often they agreed, and
the game record. `--player jev` swaps the roles, so Jev plays and Harriet shadows.

| file | what it does |
|---|---|
| `tictactoe/TicTacToe.java` | the board, minimax ground truth, the board-to-text renderings (`Rep`), the three questions, and both arms (Harriet in-process, Jev over HTTP); shared by the other three through JBang `//SOURCES` |
| `tictactoe/HarrietTicTacToeFx.java` | the JavaFX window: click to play X, or let X play itself; `--jev` adds the hosted comparison and score table; `--record` captures the window to frames and assembles an mp4 and a GIF with ffmpeg |
| `tictactoe/TicTacToeBench.java` | headless: sampled positions plus games against a random and a perfect X; how often the top cell is minimax-optimal, wins taken, blocks made, Noul accuracy, time per decision |
| `tictactoe/TicTacToeTiming.java` | what one move costs on fresh boards for each board text, and what the second question on the same board costs |

```sh
cd tictactoe
jbang HarrietTicTacToeFx.java                        # you are X; click a cell
jbang HarrietTicTacToeFx.java --auto random          # X plays itself
TYPESAFE_API_KEY=... jbang HarrietTicTacToeFx.java --jev            # hosted Jev shadows every move; score table
TYPESAFE_API_KEY=... jbang HarrietTicTacToeFx.java --jev --player jev   # Jev plays, Harriet shadows
jbang HarrietTicTacToeFx.java --auto random --games 2 --record out/rec   # frames + demo.mp4 + demo.gif
jbang HarrietTicTacToeFx.java --nouls                # also ask "can O win now" and "must O block" (two more readouts)
jbang HarrietTicTacToeFx.java --auto random --seed 42   # replay the same auto-played games (the footer shows each run's seed)
jbang TicTacToeTiming.java
jbang TicTacToeBench.java --arm harriet --rep GRID,LIST,GRID_HINTS,LIST_HINTS
TYPESAFE_API_KEY=... jbang TicTacToeBench.java --arm jev --rep GRID,LIST,GRID_HINTS,LIST_HINTS
```

JBang pulls JavaFX for your platform from the one `//DEPS org.openjfx:javafx-controls` line.
The window is 740 by 450 so a recording fits a post without scaling; the recorder writes 20
frames a second and, if `ffmpeg` is on the path, `demo.mp4` (H.264) and `demo.gif` (12 fps). The
default asks one question per move, which is the fast path; `--nouls` adds the two yes-or-no
questions as bars, at the cost of two more readouts on the same prefix.

Four board texts are built in: `GRID` (an ASCII board plus the list of empty cells), `LIST` (which
cells X, O and nobody hold), and `_HINTS` variants of each that prepend the rules and a one-line
strategy. The bench measures all four so the choice is a number, not a guess; the measured table is
`../article/tictactoe-results.md`, generated by `tictactoe/summarize.py` from the raw records in
`../article/runs/tictactoe/`. `GRID` is the default because it scored best for both arms.

To record at server speed, `../vps/hetzner-record-tictactoe.sh` runs the same app headless on a
fresh Hetzner box under Xvfb with JavaFX's software pipeline; the key is never sent to the box, so
that clip has no hosted arm.

## Reference outputs

The runs behind the article's numbers are in `../article/runs/` (laptop), `../article/runs/vps/`
(CCX33) and `../article/runs/jev/` (hosted), with the machine description beside each. The
tic-tac-toe bench, timing probes, snapshots and a recording are under `../article/runs/tictactoe/`. The
side-by-side table is `../article/laptop-vs-vps.md`.

This package was run end-to-end on both machines on 2026-10-29 (`../article/runs/reproduce-laptop/`,
`../article/runs/reproduce-vps/`). Every local verdict matched the article's logs. The hosted
answers do not repeat exactly: across the 28 Jev calls, probabilities moved by up to 0.03
between the two runs, with no winner changing.
