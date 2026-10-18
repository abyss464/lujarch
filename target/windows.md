# Small windows

A small window is an app floating over another app, both in use. One small window is open at a
time; the others wait as cards in the dock at an edge of the screen. Apps and conversations are
the same kind of small window.

## Using a small window

### WIN-1 Apps float in small windows

- Home: Settings > Display > Small windows
- Switch: Use small windows
- Default: on
- Verify: vm

Any app can be shown in a small window over the app on screen, and both take touches. A small
window has rounded corners, a thin outline and a handle along its top edge; everything below the
handle is the app. Off: no entry of this file shows, the dock and its rail are gone, and
conversations that would open in a small window arrive as notifications.

### WIN-2 A small window moves by its handle and takes any size

- Home: a small window
- Switch: none
- Default: none
- Verify: vm

Dragging the handle moves the window anywhere on the screen. Dragging any of its four edges
changes its width or height, and dragging any of its four corners changes both: the app lays
itself out at the new size, so any width and height between the minimum and the screen can be
reached, and an app that cannot is shown whole inside the window with empty margins. Each edge
and corner takes a drag that starts within 16 dp of it. A window is at least 160 dp wide and
stays 12 dp inside the screen's edges; dragging an edge or a corner past that limit and letting go
shows the app full screen. A tap on the handle opens its menu: Full screen, Move to dock, Close.
Full screen turns the small window back into an ordinary app in Recents: the window grows into
the full screen from where it is. Opening the app from its icon on the home screen or in the app
drawer does the same, also while its window waits in the dock or the rail.

### WIN-3 Touching the app below moves the window to the dock

- Home: a small window
- Switch: Settings > Display > Small windows > Move to dock when the app below is touched
- Default: on
- Verify: vm

A touch on the app under an open small window moves the small window to the dock and gives the
touch to the app below.

## The dock

### WIN-4 Small windows wait in the dock at the screen's edge

- Home: the dock
- Switch: none
- Default: none
- Verify: vm

The dock is a stack of cards against the right edge of the screen, with nothing drawn behind
them: each card floats over the app on screen with its own shadow, overlaps the next, and is
turned slightly towards the middle of the screen. An app's card shows the app's whole live
screen, scaled to fit inside the card with empty margins beside it, and its icon in a lower
corner; a conversation's card shows the person's picture, the number of unread
messages and the last message. The newest card is on top. A tap on a card opens it, and the window
that was open becomes the top card. Dragging a card into the middle of the screen opens it there;
dragging it onto the circle that appears at the bottom closes it; dropping it on the dock puts it
in that place of the stack.

### WIN-5 The dock moves anywhere and rests at an edge

- Home: the dock
- Switch: none
- Default: none
- Verify: vm

A handle above the top card moves the whole dock: the dock follows the finger anywhere on the
screen, its cards trailing one after another, and on letting go it settles against the nearer
side edge at the height where it was let go, carried on by the fling, as Android's own bubbles
do; on the left edge its cards turn the other way. The dock keeps its edge and height until it is
moved again.

### WIN-6 The dock folds into a rail

- Home: the dock
- Switch: none
- Default: none
- Verify: vm

Pushing the dock's handle past the screen's edge folds the dock into a rail 34 dp wide holding a
22 dp icon for each card; a conversation with unread messages has a ring round its picture. A
tap on the rail unfolds the dock. Dragging the rail moves it anywhere on the screen, its icons
trailing one after another, and on letting go it settles against the nearer side edge, carried
on by the fling, as Android's own bubbles do. Apps in the rail count as in the background
(BG-1); conversations in it still receive messages.

### WIN-7 Cards show the live screen

- Home: the dock
- Switch: Settings > Display > Small windows > Live previews in the dock
- Default: on
- Verify: vm

Off: an app's card shows the app's icon and name instead of its screen.

### WIN-8 Up to four small windows

- Home: Settings > Display > Small windows > At most
- Switch: 2, 3, 4
- Default: 4
- Verify: vm

The open window and the app cards in the dock count; a conversation counts once it is opened. When
another app goes into a small window beyond the limit, the card used longest ago leaves the dock
and becomes an ordinary card in Recents; its app keeps running.

### WIN-9 One motion for every change

- Home: a small window, the dock
- Switch: Settings > Accessibility > Remove animations
- Default: off
- Verify: vm

Every move, resize, opening, folding and change of edge follows one spring that slightly
overshoots and settles in about half a second. A card opening into a window and a window going to
the dock are one shape changing its place, size, corners and turn, not a fade. Cards moving
together follow each other at 35 ms intervals. While a finger holds a window, a card or the dock,
it follows the finger without delay.

## Opening a small window

### WIN-10 From the home screen

- Home: Home screen > touch and hold an app > Small window
- Switch: Settings > Display > Small windows > Home screen
- Default: on
- Verify: vm

The app's menu on the home screen and in the app drawer has the item Small window, with the
system's bubble icon, after the system's other items. It opens the app in a small window, for
every app.

### WIN-11 From Recents

- Home: Recents
- Switch: Settings > Display > Small windows > Recents
- Default: on
- Verify: vm

Dragging a card in Recents into the Running layer (BG-8) opens the app in a small window; the
card's menu has Open in small window. While Recents is shown, the open window and the dock fly
into the Running layer and fly back when Recents closes.

### WIN-12 From a notification

- Home: a notification
- Switch: Settings > Display > Small windows > Notifications
- Default: on
- Verify: vm

Pulling a heads-up notification down more than 48 dp shows Release to open in a small window;
releasing opens what a tap on the notification would open, in a small window. A notification in
the shade has a small-window button that does the same. This works for every app's notifications.

## Conversations

### WIN-13 Conversations open in small windows

- Home: Settings > Notifications > App notifications > (app) > Small windows
- Switch: All conversations, Selected conversations, None
- Default: None
- Verify: vm

A conversation of an app that offers its conversations as bubbles goes into the dock as a
conversation card when the app's choice allows it. A conversation pulled into a small window from
its notification counts as selected. While a conversation is in a small window or the dock, its
new messages raise its unread count on the card and on the rail instead of a heads-up
notification, also in Do Not Disturb.

### WIN-14 One home for small windows

- Home: Settings > Display > Small windows
- Switch: none
- Default: none
- Verify: vm

Settings > Display > Small windows holds every switch of this file and the list of apps with their
conversation choice, each leading to the app's own page. Settings > Notifications has a row Small
windows that opens it, and no separate bubbles page.
