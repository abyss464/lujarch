# Proxy

The proxy carries the phone's traffic through proxy nodes, per app and per destination, as the
owner's routes and rules say. Its page in Network & internet turns it on, shows what it is doing
and holds everything it uses: subscriptions, nodes, groups, rules and the core's settings. What
the proxy does on its own shows on that page.

## Turning it on

### PRX-1 The proxy carries the phone's traffic

- Home: Settings > Network & internet > Proxy; Quick Settings > Proxy
- Switch: Settings > Network & internet > Proxy > Use proxy
- Default: off
- Verify: vm

While Use proxy is on, the proxy runs from every start of the phone with a virtual network
interface, and every app's traffic, DNS included, passes through it, except apps kept out of it
(PRX-7, PRX-8). While it is off, nothing passes through it and the phone connects directly. A
change of the switch takes effect at once. Network & internet has a Proxy row with the proxy's
state as its summary and the same switch beside it. The Quick Settings tile Proxy shows the state
and the mode, turns the proxy on or off with a tap (after unlocking on a locked phone) and opens
the Proxy page with a long press.

### PRX-2 The proxy shows its state

- Home: Settings > Network & internet > Proxy > Status
- Switch: none
- Default: none
- Verify: vm

The status row shows the state, the mode and the live upload and download speed. The states are
Off, Starting, Running, Running on the last working configuration (the current one did not pass
the core's check, and the details say why), No usable configuration, and Stopped after repeated
failures; in the last two the phone connects directly. The row opens the details: the core's version, the virtual network
interface and its stack, whether IPv6 is blocked, the traffic since the start, when the running
and the last working configuration were built, and the last restart the proxy made on its own
with its reason. Each detail fills in when its source answers; one whose source fails says so.

### PRX-3 A failing proxy leaves the phone online

- Home: Settings > Network & internet > Proxy > Status
- Switch: none
- Default: none
- Verify: vm

A failure of the proxy never leaves the phone without a connection:

- A configuration that does not pass the core's check is not started. The last working
  configuration runs instead; with none, the proxy waits for a working one and the phone connects
  directly.
- A core that exits is started again after a pause that grows from 2 seconds to a minute. After 5
  exits within 5 minutes it stays stopped, and the phone connects directly until the proxy is
  restarted or turned off and on.
- A core that stops answering, or whose virtual network interface disappears, for 3 checks in a
  row is stopped and counts as an exit.

The status (PRX-2) says which of these happened.

### PRX-4 The proxy repairs its path after a network change

- Home: Settings > Network & internet > Proxy > Advanced > Repair after network changes
- Switch: Repair after network changes: on, off
- Default: on
- Verify: vm

Once the phone has changed network and the network has stayed the same for 10 seconds, the proxy
checks that a request through the virtual network interface gets through. When it does not while
the same request made by the proxy itself does, the proxy restarts once. When the proxy's own
request fails too, a node or the network is down: the proxy does not restart, and checks again
every 30 seconds, at most 10 times. A restart that did not repair the path is not repeated until
the path has worked again. Each such restart shows in the status details (PRX-2). While off, the
proxy never restarts on its own after a network change.

### PRX-5 IPv6 is blocked while the proxy runs

- Home: Settings > Network & internet > Proxy > Advanced > Core settings > IPv6
- Switch: Block IPv6 while the proxy runs: on, off
- Default: on
- Verify: vm

While the proxy runs with its virtual network interface, internet traffic over IPv6 that would go
past the interface is refused, except the proxy's own connections to nodes with IPv6 addresses.
The block is lifted as soon as the proxy stops or its interface is turned off. A change asks
whether to restart the proxy now or to take effect at its next start.

## Mode

### PRX-6 Rule, Global or Direct

- Home: Settings > Network & internet > Proxy > Mode
- Switch: Rule, Global, Direct
- Default: Rule
- Verify: vm

- Rule: each connection goes where the app's route and the rules send it.
- Global: every connection uses one node, named in the row Exit node for Global, which opens its
  group.
- Direct: no connection uses a node; the traffic still passes through the proxy.

The mode can be chosen while the proxy is off and is used from its next start. While the running
proxy uses another mode than the chosen one, a row says so and switches it on a tap.

## Apps

### PRX-7 Each app has a route

- Home: Settings > Apps > (app) > Proxy; Settings > Network & internet > Proxy > Apps
- Switch: the app's route: Follow the rules, Direct, Reject, Proxy group, Bypass the proxy entirely
- Default: Follow the rules
- Verify: vm

- Follow the rules: the domain and address rules decide each connection.
- Direct: every connection goes out without a node, still through the proxy.
- Reject: every connection is blocked.
- Proxy group: every connection uses the chosen group.
- Bypass the proxy entirely: the app's traffic, DNS included, does not enter the proxy at all; no
  rule applies and nothing shows in Connections.

App info shows the app's route as a row that opens its route page. Apps lists every app with its
route, apps with a route of their own first, system apps behind Show system apps. A route covers
the connections the app makes itself. Apps that share a user ID share one route, and the route
page names them. A change is applied to the running proxy at once; when that fails, the route
stays saved, and the page says it is not applied yet and offers to try again.

### PRX-8 The proxy can be limited to chosen apps

- Home: Settings > Network & internet > Proxy > Apps > Apps that use the proxy
- Switch: All apps, or the apps turned on in the list
- Default: All apps
- Verify: vm

With All apps, every app enters the proxy except those set to Bypass the proxy entirely. With apps
turned on in the list, only those apps enter it and every other app connects directly. An app on
the list still follows its route (PRX-7).

## Nodes

### PRX-9 Proxy groups choose the node

- Home: Settings > Network & internet > Proxy > Proxy groups
- Switch: the member each selectable group uses
- Default: the group's first member
- Verify: vm

Proxy groups lists the running proxy's groups in sections: custom groups, countries and regions,
line types, all nodes, and other groups. Search finds a group by name. A group's page lists its
members with their delay or Not tested, tests one member or all, and marks the member in use; in a
group where the owner chooses, a tap selects the member. Groups that choose by delay show their
choice. Groups inside a group open from their own rows.

### PRX-10 Adaptive groups choose per network

- Home: Settings > Network & internet > Proxy > Proxy groups > (adaptive group)
- Switch: Automatic choice, or a pinned member
- Default: Automatic choice
- Verify: vm

An adaptive group uses, for each connection, the member expected to connect fastest on the network
the phone is on, learned from that member's earlier connections on that network. A member that
fails twice in a row gives way until it succeeds again, and a second member is tried at the same
time when that is expected to pay. The group's page shows the network, the member used first and
why, every member in the order a connection tries them with its success rate and times, and the
networks the group remembers. Measure all members tests them on this network. Pinning a member,
after a confirmation that says what pinning stops, uses it on every network; Automatic choice
unpins it. Advanced > Adaptive groups holds the parameters of the choice.

### PRX-11 Subscriptions bring nodes

- Home: Settings > Network & internet > Proxy > Subscriptions
- Switch: none
- Default: none
- Verify: vm

Subscriptions lists each subscription with its node count, its last update, its usage of its
total and its expiry. A subscription is added with a name and an address. The address field is
masked and never offered to autofill or keyboard learning, and the address is never shown again:
it can only be replaced. Nodes can also be added by hand, from a node link or a file. Update all,
and a subscription's Update now, download the node lists again. A subscription's page tests its
nodes, replaces its address or its hand-added nodes, and removes it. After adding, updating or
replacing, the new nodes are located (PRX-12) and the configuration is applied; the result says how
many nodes were located.

### PRX-12 Nodes have locations

- Home: Settings > Network & internet > Proxy > Subscriptions > Node locations
- Switch: none
- Default: none
- Verify: vm

Each node has a country or region and a line type, from which the groups of PRX-9 are built. Node
locations lists the nodes without a location first, then the located ones. Locating asks first and
names the outside services it asks: the proxy connects through each node and asks them for the
node's address and its place. A location can be set by hand for one node or for every node without
one, and forgotten.

## Activity

### PRX-13 Connections are listed live

- Home: Settings > Network & internet > Proxy > Connections
- Switch: none
- Default: none
- Verify: vm

Connections lists the proxy's open connections, newest first, each with its app's name and icon,
its destination and the route it took. The proxy's own connections show as Proxy core, and a user
ID without an app as System or its number. A filter shows one app's connections. A connection's
details offer Close connection.

### PRX-14 The proxy's log is shown live

- Home: Settings > Network & internet > Proxy > Logs
- Switch: the level shown: Error, Warning, Info, Debug
- Default: Info
- Verify: vm

Logs shows the core's log as it is written, newest first, from the chosen level up. When the core
records less than the chosen level, the level row says so and names the core's own level (PRX-16).

## Advanced

### PRX-15 Rules decide where connections go

- Home: Settings > Network & internet > Proxy > Advanced > Rules
- Switch: each rule set: on, off; the final rule's target
- Default: none
- Verify: vm

Rules holds the rule sets in order, each with on or off, its rule count and where it applies
(every device, this phone only, computers only), and the final rule, which takes every connection
no other rule matched. A rule set's page adds, changes, moves and removes rules, imports a rule
list from a file, changes the set's targets, moves the set and removes it. A rule is changed only
while it is still as shown. Rule check tells which rule a domain matches. Custom groups collect
nodes by country, line type and name, each one as one adaptive group or as groups that choose by
delay. Every change is applied to the running proxy.

### PRX-16 The core's settings are on the phone

- Home: Settings > Network & internet > Proxy > Advanced > Core settings
- Switch: each setting on its page
- Default: the recommended values
- Verify: vm

Core settings holds every setting of the core: log level, connections, app recognition, IPv6,
the virtual network interface, group testing, domain sniffing, DNS, and country and domain lists.
Each change is saved and applied to the running proxy. Five values the proxy needs to work at all
(the virtual network interface, its routes, the network detection for the proxy's own
connections, the redirected DNS and the proxy's DNS) show their recommended value while they
differ. Changing one away from it first says what breaks. While any differs, a row at the top of
Core settings names them and restores them all with one tap.

### PRX-17 The configuration can be applied, rolled back and moved

- Home: Settings > Network & internet > Proxy > Advanced
- Switch: none
- Default: none
- Verify: vm

- Apply configuration builds the configuration from the current settings, checks it and loads it
  into the running proxy; when the proxy does not take it, the previous one stays.
- Check configuration builds and checks it without loading it.
- Roll back configuration loads the last working configuration.
- Restart proxy stops and starts the core.
- Configuration files lists every file with its size and time. Export all writes them to a file
  the owner chooses, with subscription addresses and node passwords only when the owner includes
  them after a warning. Import replaces them from such a file after saying what it holds. The
  replaced files are kept and can be restored.

Each of these asks first when it interrupts the proxy.

### PRX-18 The proxy's control key stays in the system

- Home: Settings > Network & internet > Proxy > Advanced > Change the control key
- Switch: none
- Default: none
- Verify: vm

The core's control interface on the phone answers only to a key that no app sees; Lujarch's pages
reach it through the system. Change the control key makes a new key, and the running proxy takes
it with a short interruption.

## Status bar

### PRX-19 The status bar shows the proxy

- Home: the status bar
- Switch: Settings > System > Status bar > System icons > Proxy
- Default: on
- Verify: vm

While the proxy runs, the status bar shows a globe; while it is on but not running (no working
configuration, stopped after repeated failures), a globe with an exclamation mark. Off or
starting, it shows nothing. Turning the icon off hides it and leaves the proxy as it is.
