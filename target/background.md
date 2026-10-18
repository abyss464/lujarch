# Background

An app that leaves the screen sinks: it keeps running, keeps only the processes the owner named,
is paused, is reclaimed, or is stopped. The system makes it sink; for each app the owner decides
how far it may sink and who may wake it, and sees where each app is, why, and who put it there.
Each of these has one home; everywhere else it is only shown, with the same word, or leads home.

## Where an app is

### BG-1 Every process has a run state and a reason

- Home: Settings > Apps > (app) > App battery usage > Run state
- Switch: none
- Default: none
- Verify: vm

A process off screen is in one of these states: Running in background (playback, navigation, a
download, Unrestricted, or a process the owner named), Paused (frozen in memory, using no CPU,
back as it was), Reclaimed (ended; its card in Recents remains and it starts again when opened)
or Stopped (force stopped: it does not run again until the owner opens it). On screen it is
Foreground or Small window. Each state has its own color, the same wherever it is shown, and
every change says who made it (You, System or the app, with the other app's name when another app
caused it) and why, in plain words. App battery usage shows the app's run state as one line per
process for each of the last 7 days; a mark on a line opens the reason and who.

### BG-2 Allow background usage has four levels

- Home: Settings > Apps > (app) > App battery usage > Allow background usage
- Switch: Optimized, Chosen processes, Unrestricted; the switch off is Restricted
- Default: Optimized
- Verify: vm

Restricted: the app is paused as soon as it leaves the screen, runs no background tasks and is not
started for broadcasts. Optimized: it is paused 10 seconds after it leaves; the system schedules
its background tasks. Chosen processes: the processes of the app the owner ticks keep running
after it leaves, are never paused and are not ended when its card is closed; the rest is as
Optimized. The page lists the app's processes to tick, each with its memory and whether it holds
a network connection, and suggests which to tick; the system never ticks one by itself.
Unrestricted: the app runs as in the foreground and is never paused; it can still be reclaimed
when memory runs low (Keep, BG-4, holds it). Below the levels, After it leaves shows the app's
Autostart, Alarms & reminders, Long background tasks, Background data and Keep, each leading to
its own home. Settings > Apps > App battery usage lists every app with its level and filters by
level; Battery manager > Restricted apps opens that list filtered to Restricted.

### BG-3 A broken choice is suggested once

- Home: Settings > Apps > (app) > App battery usage > Allow background usage
- Switch: none
- Default: none
- Verify: vm

When a process the owner chose keeps calling the app's paused main process, which the system then
ends (five times in 10 minutes), or a chosen process ends without the system ending it, the page
shows a suggestion under the level: what happened, with the times, and Add the main process. The
same suggestion arrives as an alert (BG-12); acting on it or dismissing it in either place removes
both. Nothing changes until the owner acts. Each app gets at most one suggestion a day for each
cause.

### BG-4 Keep holds an app in memory

- Home: Recents > (card) > card menu > Keep
- Switch: Keep, Remove keep
- Default: off
- Verify: vm

A kept app is not reclaimed for ordinary memory pressure, and closing its card or Clear all does
not end it; it is reclaimed last when memory is critically low, and it can still be paused. Its
card carries a short mark at its top corner, resists being swiped away and springs back with the
line Kept · remove it in the card menu, and Clear all skips it. App info > Memory and the memory
page show whether an app is kept.

## Memory

### BG-5 Memory has one page

- Home: Settings > System > Memory
- Switch: Show system processes
- Default: off
- Verify: vm

The page shows the device's memory as four parts (System, Apps, Kept, Free) and the compressed
swap, then the apps with a process in groups: Running in background, by memory, with each one's
reason; Kept; Paused, each with its place in the order of reclaiming; and Lujarch's system
services. A row opens the app's memory page. While the page is open it reads again every 5
seconds. Show system processes adds the system's own processes in the same groups. Developer
options' Memory and Running services open this page.

### BG-6 An app's memory page

- Home: Settings > Apps > (app) > Memory
- Switch: none
- Default: none
- Verify: vm

App info always shows Memory, summarized as the app's average use and whether it is kept. The page
shows its average and highest use over the last 3 hours, its processes each with its state and
memory, whether it is kept, its background usage level and its last reclaim, each leading home.

### BG-7 A process shows why it is where it is

- Home: Settings > System > Memory > (app) > (process)
- Switch: none
- Default: none
- Verify: vm

The page shows the process's state and why, in plain words, with who caused it; its memory as
actual use, resident and compressed; its place in the order of reclaiming, with the system's
score in brackets; its CPU time today; and its threads by CPU time, read only. End this process
ends it once, recorded as You; it then follows the app's choices again.

## Running

### BG-8 Recents' Running layer holds what runs off screen

- Home: Recents
- Switch: Home settings > Recents > Show running
- Default: on
- Verify: vm

Above the cards, the Running layer shows everything that runs off screen with one word for why: what
the app runs itself (Navigating, Playing, a download), Small window, Unrestricted and Chosen
processes, in that order; it scrolls sideways when it is longer than the screen, and it stays when
Recents has no cards. A tap opens the app. Touching and holding shows a menu in the card menu's
style: End running for what the app started itself; the small window's own Full screen, Move to dock
and Close for a small window; and for an app running because of the owner's choice no End running
but Background usage, which leads home. Every menu has App info. With the layer off it is not drawn
and the shade's footer shows Android's own active apps button and dialog.

When Recents opens, the tiles come out of the status bar: each leaves it a little, moves along to
above its own place and drops into it, the one going furthest first, and the layer's name shows
once they have passed; a small window's tile comes from the window itself (WIN-11). When Recents
closes they go back up into the status bar over the app that opens. Clear all sends them up first
and brings them down again when it is done.

A card lifted toward the layer parts it. Once the card's top is within 80 dp of the layer, the
tiles in its way fold into their icons and slide aside to both sides of the card, the nearest
first, settling in about half a second however fast the finger moves; until the way is clear the
card waits at the layer's edge, so it never covers a tile and never passes under one. When the card
is gone or back in its place, the tiles slide back from the gap outward, overshoot a little and
open again.

### BG-9 The shade's footer leads to Running

- Home: the notification shade
- Switch: none
- Default: none
- Verify: vm

The shade's footer shows Running · N, N being what the Running layer shows; a tap closes the shade
and opens Recents, after unlocking. It carries Android's dot when an app starts running something
new, until Recents is next shown. Android's dialog of active apps is not shown while the Running
layer is on, and the system has no notification of its own listing what runs.

### BG-10 Recents folds into two rows

- Home: Recents
- Switch: Home settings > Recents > Show app state
- Default: on
- Verify: vm

While a gesture moves the cards and in quick switch, Recents is Android's single row. When it
settles, the current card keeps Android's size at the right, and the earlier cards fold into two
rows to its left, each column top then bottom, older to the left, scrolling sideways, with Clear all
at the far left (hidden when every card is kept). A card shows the app's memory while it is in
memory, Keep's mark, and for a reclaimed app a dimmed screen, a dashed outline and Opens again. Its
menu has Android's items, Open in small window and Keep, and below a divider the app's background
usage level and memory, each leading home. The fold follows Android's own transition to the grid;
the memory fades in after it and the Running layer arrives as BG-8 says; tapping a card fades the
memory and the cards' marks out and opens it as Android does. In landscape the same structure holds,
the Running layer as one row.

### BG-11 Recents shows the memory

- Home: Recents
- Switch: Home settings > Recents > Show memory
- Default: on
- Verify: vm

Below the cards, the memory as the same four parts as BG-5, with Memory, which opens Settings >
System > Memory.

## Alerts

### BG-12 Background activity speaks only before a decision

- Home: the notification shade
- Switch: Settings > Notifications > App notifications > Android System > Background activity
- Default: on
- Verify: vm

Alerts use Android's own Background activity channel and appear in two cases only: a choice of
the owner's is broken (BG-3), offering the same action as the page; or the system would decide
for the owner: when an app off screen uses much more battery than usual, the alert says how much
and offers Set to restricted, and the system never restricts it by itself. Android's alert that
an app is still running offers View running, which opens the Running layer. Pausing and reclaiming
alert nothing: they are written in the run state and shown on the cards.
