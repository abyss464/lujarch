# Background

An app that leaves the screen sinks: it keeps running, keeps only what receives its messages, is
paused, is reclaimed, or is stopped. The system makes it sink; the owner sets how deep it may sink
and who may wake it, and sees at every moment where each app is, why, and who put it there.

## Where an app is

### BG-1 Every app has a depth and a reason

- Home: Settings > System > Background
- Switch: none
- Default: none
- Verify: vm

An app off screen is at one depth: Running (it still works: playback, navigation, a download, a
small window, or Unrestricted), Messages only (only its process that holds the connection to its
messages runs), Paused (frozen in memory, using no CPU, back as it was), Reclaimed (its processes
have ended and its card in Recents remains; it starts again when opened) or Stopped (force
stopped: nothing wakes it until it is opened). Each depth has its own color, the same wherever the
depth is shown. Every depth says who put the app there: You, System or the app itself, with the
reason in words.

### BG-2 Background lists every app by depth

- Home: Settings > System > Background
- Switch: Show system processes
- Default: off
- Verify: vm

The page shows the memory in use as four parts (System, Apps, Kept, Free) and every app with a
process, grouped by depth, each row with its reason, who caused it and its memory. It sorts by
depth, memory, battery use or wakeups. The page has no other switches: a row opens the app's
Background page. Show system processes adds the system's own processes, grouped the same way.
Developer options has no Running services or Memory page.

## An app's background

### BG-3 One page holds an app's background

- Home: Settings > Apps > (app) > Background
- Switch: none
- Default: none
- Verify: vm

The page shows the app's depth now and its three choices drawn on the column of depths where each
acts: Background usage (BG-4) marks the highest depth the app may stay at once it leaves the
screen, Keep (BG-5) the deepest it may sink, and Autostart (PERM-15) who may wake it once it has
sunk to Reclaimed. Below them: what happened (BG-6), the app's processes (BG-7), its time on screen,
its time running in the background and its wakeups in the last 24 hours, and the actions End
background processes and Force stop. App info has the row Background with the depth as its
summary; App battery usage keeps the app's battery figures and leads here for the choices.

### BG-4 Background usage has Messages only

- Home: Settings > Apps > (app) > Background > Background usage
- Switch: Unrestricted, Messages only, Optimized, Restricted
- Default: Optimized
- Verify: vm

Unrestricted, Optimized and Restricted are Android's. Messages only: when the app leaves the
screen, the system keeps running only the app's process that holds a network connection for its
messages and pauses the rest; a message that arrives wakes the rest for as long as the app needs.

### BG-5 Keep holds an app in memory

- Home: Settings > Apps > (app) > Background > Keep
- Switch: on, off
- Default: off
- Verify: vm

A kept app is never reclaimed for memory; it can still be paused. Its card in Recents carries a
short mark at its top corner, cannot be swiped away (it springs back and says Kept: turn it off in
the app's Background page) and is skipped by Clear all.

### BG-6 What happened to an app is recorded

- Home: Settings > Apps > (app) > Background > What happened
- Switch: none
- Default: none
- Verify: vm

Every time the app is paused, woken, reclaimed, stopped or started in the background, a line
records the time, who did it (You, System or the app, with the other app's name when another app
started it) and why. The page shows the latest lines; All shows the last 7 days.

### BG-7 A process shows why it runs

- Home: Settings > Apps > (app) > Background > (process)
- Switch: none
- Default: none
- Verify: vm

The page shows the process's depth, the reasons it is at that depth in words with who caused each
(with the system's priority name in brackets), its memory, swap, CPU time, wakeups and network use
in the last 24 hours, and its threads by CPU time, read only. Pause this process and End this
process act once; the process then follows the app's choices again, and the page says where those
are changed.

## Notifications

### BG-8 One Background notification

- Home: the notification shade
- Switch: Settings > Notifications > App notifications > Android System > Background
- Default: on
- Verify: vm

A silent notification at the bottom of the shade, not on the lock screen, lists every app running
off screen with its depth color, reason and who caused it, and the free memory. An app running a
foreground service of its own (navigation, playback, a download) has End, which stops that
service; a small window has Close; an app running because of the owner's choice has no button and
its row opens its Background page. Background opens Settings > System > Background. The shade's
footer has no button for active apps.

### BG-9 An app's running notification folds into Background

- Home: touch and hold an app's ongoing notification > Fold into Background
- Switch: Fold into Background, per app
- Default: off
- Verify: vm

A folded app's ongoing notification of a foreground service is shown as a line inside the
Background notification instead of on its own; its service keeps running. The same menu on that
line puts it back.

### BG-10 Background alerts speak twice

- Home: the lock screen and the notification shade
- Switch: Settings > Notifications > App notifications > Android System > Background alerts
- Default: on
- Verify: vm

An alert appears in two cases only. When the system ends an app the owner chose to keep running
(Keep, Messages only, Unrestricted), the alert says when, who and what was lost, and offers Keep
(for Messages only) and What happened. When an app off screen uses much more battery than its
peers, the alert says how much and why and offers Restricted and It can do this; after It can do
this, the same app is not alerted for the same cause again. The system never restricts an app by
itself. Alerts at night wait silently for the lock screen.

## Recents

### BG-11 Recents shows what is running

- Home: Recents
- Switch: Home settings > Recents > Show running apps
- Default: on
- Verify: vm

Above the cards, a Running layer shows every app running off screen with its depth color and one
word (Navigating, Playing, Small window, Messages only, Unrestricted); open small windows appear
in it with their live screen (WIN-11). A tap opens the app; touching and holding shows End for what
the app started itself and Background. The cards keep Android's place, size and order; a
reclaimed app's card is dimmed with a dashed outline and says Opens again, a kept app's card
carries its mark (BG-5).

### BG-12 Recents shows the memory

- Home: Recents
- Switch: Home settings > Recents > Show memory
- Default: on
- Verify: vm

Below the cards, the memory as the same four parts as BG-2, with Background, which opens
Settings > System > Background.
