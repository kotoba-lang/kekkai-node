# kekkai-node

[![CI](https://github.com/kotoba-lang/kekkai-node/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/kekkai-node/actions/workflows/ci.yml)

**The node-side agent for the kekkai overlay: the data plane the control plane
deliberately does not touch.** Mutually-authenticated Noise IK sessions between
peers, NAT hole punching with a relay fallback (the DERP-equivalent), and MagicDNS
served straight off the netmap.

The repository also owns the first-party OS packet-adapter boundary: pure
packet admission in
[`kotoba/kekkai/packet_plane.kotoba`](kotoba/kekkai/packet_plane.kotoba),
Apple Network Extension, Android `VpnService`, Linux `/dev/net/tun`, the
Windows device contract, and a local bridge that rechecks signed `:tun`
routes. See [`docs/os-tunnel.md`](docs/os-tunnel.md). Tailscale coexistence is
retained; this repository performs no uninstall or disable action.

[`kekkai`](https://github.com/kotoba-lang/kekkai) is a Tailscale-equivalent
**control plane** and says so in its charter: it publishes a netmap and *"never
carries a packet and never pushes WireGuard config — the nodes pull the netmap and
open their own tunnels"*. Its own status note lists what was therefore missing:
*"WireGuard データ面エージェント（charter 外の別コンポーネント）との netmap 受け渡し"*.
This is that component. It uses [`noise`](https://github.com/kotoba-lang/noise)
for the cryptography and [`org-ietf-dns`](https://github.com/kotoba-lang/org-ietf-dns)
for the DNS wire format.

It replaces what [`murakumo`](https://github.com/kotoba-lang/murakumo)'s overlay
did with `--auth-key`: **one shared symmetric secret for the whole overlay,
AES-GCM, no per-peer identity, no forward secrecy, no replay window**. Here every
peer pair has its own session, keyed by the static keys the netmap publishes.

```
       ┌──────────────────────────┐
       │  kekkai (control plane)  │  admission · netmap · routes · ACL
       └────────────┬─────────────┘   (never actuates; always a human for
                    │ netmap           machine + exit approval)
        ┌───────────┴────────────┐
        ▼                        ▼
  ┌───────────┐  Noise IK   ┌───────────┐
  │kekkai-node│◄───────────►│kekkai-node│   direct, once punched
  └─────┬─────┘             └─────┬─────┘
        │   sealed frames, relay cannot read them   │
        └────────────► ┌───────┐ ◄─────────────────┘
                       │ relay │  DERP-equivalent: fallback path
                       └───────┘  AND the signalling channel a punch needs
```

## What each namespace owns

Everything that decides anything is pure `.cljc` and unit-tested without a
socket; the `.cljs` files are sockets and timers.

| namespace | role |
|---|---|
| `kekkai.node.netmap` | consume the published netmap: admission, deny-by-default edges, the Noise prologue that binds a session to a netmap version |
| `kekkai.node.peer` | one peer end to end: IK session, disco path state, routing decision, tick |
| `kekkai.node.disco` | endpoint candidates, hole-punch schedule, path scoring, upgrade/downgrade |
| `kekkai.node.relay` | relay protocol, both ends: registration by proven key, routing, roaming, expiry, home selection |
| `kekkai.node.stream` | a **reliable, ordered byte stream** over a peer session: byte-offset sequencing, cumulative ack, out-of-order buffering, fast retransmit, flow control, half-close |
| `kekkai.node.stream-edge` (cljs) | that stream bound to real TCP sockets — a local forwarder and a loopback service, each gated by `netmap/permitted?` |
| `kekkai.node.magicdns` | the netmap as an `nameserver.resolver/IResolver` |
| `kekkai.node.launchd` | the LaunchDaemon plist and the split-DNS resolver file |
| `kekkai.node.application` / `access-edge` / `signed-netmap` (cljs) | message framing over a session, a private-HTTP connector, and Ed25519 netmap-envelope verification — added alongside this work by a parallel session; tested, and `signed-netmap` is the beginning of the netmap-signature gap below |
| `kekkai.node.funnel` / `message-plane` (cljs) | **kekkai funnel**: a public HTTPS listener on one edge node that publishes a service on a NAT'd node (see below); the plane is pacing + nack-driven re-send for application messages, shared with `access-edge` |
| `kekkai.node.agent` (cljs) | the loop: one UDP socket, one relay client, N peers, a timer, a DNS listener |
| `relay_server` / `dns_server` / `stun` / `udp` (cljs) | the sockets |

## SSH over the overlay, without a TUN device

```
ssh ──▶ 127.0.0.1:2222 ──┐                    ┌──▶ 127.0.0.1:22 (sshd)
              forwarder  │  kekkai overlay    │  service
                         └── stream frames ───┘
```

The control plane grants `:ssh` on a port; the forwarder opens one stream per
TCP connection; the far side connects to its own loopback `sshd`. To the user it
is `ssh -p 2222 localhost` and to `sshd` it is a connection from 127.0.0.1.

**Measured 2026-08-07 on the real fleet**, not in a simulator: relay and service
agent on `judah` (a Mac mini on the tailnet), forwarder on the workstation, and

```
$ ssh -p 2222 judah@127.0.0.1 'echo KEKKAI-SSH-OK; hostname; uname -sm'
KEKKAI-SSH-OK
judahnoMac-mini.local
Darwin arm64
```

A second run hashed 200 KB of `/dev/urandom` on the far side through the same
forward. Then the kekkai service on `judah` was killed and the same command
failed with `Connection timed out during banner exchange` — which is the control
that makes the first result mean anything, since ordinary Tailscale SSH to the
same host works either way.

Why a reliable stream had to be written rather than borrowed: `peer` carries
authenticated datagrams with no sequence numbers, acknowledgement or
retransmission. A packet overlay does not need them — it hands datagrams to an
IP stack and the inner TCP recovers. A **userspace** forwarder has no inner TCP
to borrow from, so ordering, loss recovery, flow control and half-close are
`kekkai.node.stream`'s job or nobody's. `kekkai.node.application` does not
substitute: it reassembles a *message*, and a lost chunk means the message never
completes.

Two authorisation checks, deliberately not one. The forwarder asks
`netmap/permitted?` before opening a stream so a refusal is immediate and costs
no overlay traffic; the service asks again before connecting to anything, and
**that** is the boundary — without it the only thing between a peer and loopback
is the peer's own opinion of what it may do, which is the `fleet.edn` shape
`kekkai.node.netmap`'s docstring exists to warn about.

```bash
npm run e2e:stream       # relay + two agents + a real TCP service, over real UDP
```

## kekkai funnel — a public service on a NAT'd node, without Tailscale or Cloudflare

The Tailscale Funnel equivalent: a service on a fleet node that has no public
address (first target: `gad`, a Kubo node behind NAT running a Biscuit-gated
block service) is published on the public internet through **one** kekkai node
that does have one.

```
internet ──HTTPS :443──▶ edge node ──── Noise overlay ────▶ gad
  Host: blocks.example.net  kekkai.node.funnel    (punched or   access-edge connector
                            TLS, Host → funnel     relayed)     ──▶ 127.0.0.1:8080
```

The edge terminates TLS, chooses the funnel by `Host`, and sends access-edge's
`request` message over the overlay with `:via "funnel"`. `gad` opens no inbound
port: it reaches the edge the way every kekkai node reaches a peer.

### Local and remote funnels

- **Remote** (`:funnel/node` ≠ the edge): the path above — over the overlay as
  an access-edge `request` message, authorised again by the target's connector.
- **Local** (`:funnel/edge` = `:funnel/node` = the edge itself): the edge
  serves its own service **directly**, proxying to the `:base-url` in its own
  `:services` — never over the overlay. The control plane publishes a local
  funnel in `:netmap/funnels` with **no** `:funnel` edge and no peer, and it
  grants nothing to anyone (`netmap_test` pins `sessionable`/`permitted?`
  unchanged). The gate is the same local rule the connector applies: the
  service must carry `:funnel? true` and the funnel's `:port`, and a
  `:funnel? true` service whose `:base-url` is not loopback refuses to start.
  Bodies **stream** both ways — no message framing, no 6 MiB buffering — with
  the same body limit (413, counted as bytes arrive, so chunked uploads are cut
  off too), request timeout (504), header allowlists, `x-forwarded-*`, single
  404 and no-logging rule as the remote path. In `e2e:funnel` a 4 MiB + 32 B
  PUT echoed back through a local funnel takes **16–52 ms** (two runs), against about
  19 s for 3 MiB over the overlay.

### The gad deployment shape

`gad` is publicly reachable over IPv6, so it is the fleet's funnel **edge**:
its public listener (`:funnel {:listen-port 443 :tls …}`) serves

- its own `block-node` at `127.0.0.1:8480` as a **local** funnel — directly,
  no overlay — and
- any other node's service as a **remote** funnel, over the overlay to that
  node's access-edge connector.

```clojure
;; gad (npm run funnel -- gad-funnel.edn)
{:node/id "gad" :static {:priv "<hex>" :pub "<hex>"}
 :netmap-file "/opt/kekkai/netmap.edn" :netmap-authority-spki-b64 "<b64>"
 :listen-port 41641
 :services {"block-node" {:base-url "http://127.0.0.1:8480" :port 8480
                          :funnel? true}}
 :funnel {:listen-host "::" :listen-port 443
          :tls {:cert-file "/etc/kekkai/blocks.crt" :key-file "/etc/kekkai/blocks.key"}}}
;; published by the control plane:
;;   {:funnel/host "blocks.example.net" :funnel/edge "gad" :funnel/node "gad"
;;    :funnel/service "block-node" :funnel/port 8480}
```

### It needs one publicly reachable edge host, and that cannot be engineered away

A NAT'd host cannot accept a connection from an arbitrary internet client that
never sent it a packet first — hole punching needs both ends running kekkai. So
*something* with a public address must accept the client's TCP connection and
carry it inward. Tailscale's answer is its own funnel ingress servers; this is
the same role, run by us instead: a small host (a VPS is enough) with TCP 443
open, the kekkai UDP port reachable, a DNS record for each funnel host pointing
at it, and a TLS certificate for those names. kekkai removes the third party,
not the requirement for a public address.

### The contract (published by `kotoba-lang/kekkai`)

```clojure
:netmap/funnels [{:funnel/host "blocks.example.net"  ; lowercase, no port, no trailing dot
                  :funnel/edge "edge-1"              ; node running the public listener
                  :funnel/node "gad"                 ; target node
                  :funnel/service "block-node"       ; the connector's :services key
                  :funnel/port 8080}]                ; that service's port
```

Sorted by host, present only in the netmaps of a funnel's edge and target, and
— for a remote funnel — always accompanied by a **separate** edge entry
`{:edge/from "edge-1" :edge/to "gad" :edge/capabilities [:funnel] :edge/ports [8080]}`.
A `[:funnel]`-only edge is enough for the two nodes to hold a Noise session
(`netmap/session-capabilities`); it is never `:overlay`, `:ssh` or
`:private-http` authority. Malformed entries or duplicate hosts make the netmap
unusable (`netmap/validate`), like every other structural problem; so does a
remote funnel without its grant. A local funnel needs none.

### Security model

- **Two checks, and the connector's is the boundary.** The edge asks
  `netmap/funnel-for-host` (a funnel with `:funnel/edge` = itself *and* the
  `:funnel` grant); the connector asks `netmap/funnel-admits?` (a funnel naming
  this edge, this node, this service and port, *and* the grant) plus
  `peer-admitted?` against its own netmap. A stale or compromised edge can send
  requests; it cannot make `gad` serve them. `test/funnel_e2e.cljk` drives
  exactly that case and checks the upstream was never touched.
- **`:funnel` and `:private-http` never imply each other**, in either
  direction (`netmap_test`). Granting a colleague private access never
  publishes a service; publishing one never lets the edge browse it privately.
- **Local opt-in.** The connector serves funnel traffic only for services
  marked `:funnel? true` in its own config. That is a second *deny*, never a
  grant: the flag without the netmap's funnel admits nothing.
- **Nothing reaches loopback raw.** `stream-edge` refuses a stream opened
  under `:funnel` (`:funnel-is-http-only`), so a funnel grant cannot become a
  TCP tunnel past the header allowlists.
- **Headers are allowlists.** Request: access-edge's list plus `authorization`,
  `x-kotoba-grant` (Biscuit), `content-length`; hop-by-hop headers and anything
  named in `Connection` never cross; the edge sets `x-forwarded-proto: https`
  and `x-forwarded-host` itself and ignores the client's. Applied at the edge
  and again at the connector. Response: access-edge's list.
- **Refusals are early and uninformative where they must be.** Every unknown
  or malformed `Host` gets the same 404 before load or size are looked at;
  then 400 (non-origin-form path), 503 (busy, or target not admitted — the
  control plane does not check key expiry when it publishes), 413 (over the
  body limit, default 4 MiB + 64 KiB), 504 (timeout), 502 (connector refused or target
  unreachable).
- **TLS by default.** Plain HTTP only with an explicit `:insecure-http true`
  (tests, or behind a TLS terminator). Request bodies and `authorization`
  values are never logged.

### Configuration

```clojure
;; edge-1: the public listener (npm run funnel -- kekkai-funnel.edn)
{:node/id "edge-1" :static {:priv "<hex>" :pub "<hex>"}
 :netmap-file "/opt/kekkai/netmap.edn" :netmap-authority-spki-b64 "<b64>"
 :listen-port 41641
 :funnel {:listen-host "0.0.0.0" :listen-port 443
          :tls {:cert-file "/etc/kekkai/blocks.crt" :key-file "/etc/kekkai/blocks.key"}
          :max-body-bytes 4259840 :max-pending 64 :request-timeout-ms 30000}}

;; gad: an ordinary access-edge connector (npm run access-edge -- gad.edn)
{:mode :connector :node/id "gad" :static {:priv "<hex>" :pub "<hex>"}
 :netmap-file "/opt/kekkai/netmap.edn" :netmap-authority-spki-b64 "<b64>"
 :services {"block-node" {:base-url "http://127.0.0.1:8080" :port 8080
                          :funnel? true}}}
```

### Carrying block-sized bodies

A funnel request is access-edge's message protocol, raised to carry a 4 MiB
body (`application/max-message-bytes` 6 MiB after base64). Two things had to
change underneath it, both measured on the real overlay:

- **Pacing.** Sending a message's frames in one synchronous loop lost about a
  quarter of a 500 KB response's ~950 frames in the burst and it never
  completed; 16 frames per event-loop turn (`message-plane`) delivered it whole
  in about two seconds.
- **Selective re-send.** An application message is all-or-nothing, and a 4 MiB
  body is ~8000 frames. The receiver now names the parts it is missing once an
  assembly goes quiet (`application/stalled` → a one-frame `nack`), and the
  sender re-sends those from a bounded retention. `message_plane_test` drops
  every 9th frame and still gets the message through byte for byte.

A 3 MiB PUT echoed back (6 MiB across the overlay) takes about 19 s in
`e2e:funnel`, with both nodes and the relay in one interpreted process on
loopback. That is this transport's per-datagram cost (see "Performance"
below), not a funnel limit; it is adequate for occasional block transfer and
not for bulk.

```bash
npm run e2e:funnel   # relay + edge + connector + upstream, over real UDP
```

## Design decisions worth knowing before changing anything

**Connectivity first, optimization second.** Every peer starts on the relay, which
works behind anything. A node is never unreachable *because* discovery is still in
progress. Direct paths are an upgrade applied when a probe proves one works.

**A candidate is a hypothesis, never a fact.** Netmap endpoints are stale hints;
`disco` probes all of them and trusts only a reply. Hole punching is a
simultaneous open on a **fixed burst schedule** (0, 100, 300, 700, 1500, 3000,
5000 ms from the punch start) — fixed rather than adaptive precisely because both
sides must be firing at the same time, and they only agree if the schedule is a
constant.

**Disco pings ride inside the encrypted session.** An unauthenticated pong would
let anyone move a peer's active path — a traffic-hijack primitive. A path is
marked live only by a frame that decrypted.

**The relay cannot read what it forwards** (payloads are sealed peer-to-peer; it
sees only the destination key) and **cannot be spoofed into mis-routing**: clients
authenticate with the same Noise IK handshake against the relay's netmap key, so
registration is implicit and the routing table maps proven keys. Compromising a
relay costs metadata, not content.

**The node never derives authority from its own configuration.** Being in a local
file, or being reachable, is never sufficient — `netmap/dialable` folds admission
and the edge grant together so neither can be checked without the other, and
`netmap/denials` explains every refusal (`judah: :key-expired`), because silent
denial is how a deny-by-default system becomes unoperable.

**The prologue binds tailnet + netmap version.** A peer on an older netmap fails
the handshake loudly instead of quietly operating under stale ACLs. The cost is
real: a netmap rollout is a coordinated step.

## Configuration

```clojure
;; kekkai-node.edn
{:node/id      "asher"
 :static       {:priv "<hex32>" :pub "<hex32>"}   ; this node's X25519 identity
 :netmap-file  "/opt/kekkai/netmap.edn"
 :listen-port  41641
 :stun-servers ["stun.l.google.com:19302"]
 :dns          {:enabled? true :port 5354}
 :tick-ms      1000}
```

The netmap this consumes (published by the control plane):

```clojure
{:netmap/version 42
 :netmap/tailnet "kekkai.example"
 :netmap/self  {:node/id "asher" :node/key "<hex>" :node/overlay-ip "100.64.0.1"}
 :netmap/peers [{:node/id "judah" :node/key "<hex>" :node/overlay-ip "100.64.0.2"
                 :node/status "authorized" :node/expires-at 1790000000
                 :node/endpoints [{:kind :reflexive :host "203.0.113.9" :port 41641}]}]
 :netmap/edges [{:edge/from "asher" :edge/to "judah"
                 :edge/capabilities [:overlay :ssh] :edge/ports [22]}]
 :netmap/relays [{:relay/name "jp-tyo-1" :relay/region "jp"
                  :relay/host "relay.example" :relay/port 41642 :relay/key "<hex>"}]}
```

## Run

```bash
npm install

# a relay (one publicly reachable UDP port; no state, no database)
kbb --backend sci --classpath "$CP" bin/relay.cljk relay.edn

# a node agent
kbb --backend sci --classpath "$CP" bin/agent.cljk kekkai-node.edn

# macOS residency: print the LaunchDaemon + split-DNS resolver, then install
kbb --backend sci --classpath "$CP" bin/install.cljk kekkai-node.edn
sudo kbb --backend sci --classpath "$CP" bin/install.cljk kekkai-node.edn --write

# CP="src:../bytes/src:../noise/src:../org-ietf-dns/src:../org-ietf-turn/src"
```

Residency is a **LaunchDaemon**, not a LaunchAgent, for the reason murakumo's
README documents the hard way: a user agent dies with the login session and does
not even appear in `launchctl list` over SSH. MagicDNS binds **5354, not 53**, so
the agent needs no root; `/etc/resolver/<tailnet>` points the system at it.

## Verification

```bash
kbb -M:test                                              # pure cores, JVM
kbb --backend sci --classpath "$CP" run-tests.cljk                         # pure cores, cljs
kbb --backend sci --classpath "$CP" test/e2e.cljk                          # real UDP, end to end
kbb -M:lint
```

Measured 2026-07-26, all green:

- **cljs (nbb): 51 tests / 159 assertions** — every namespace.
- **JVM: 47 tests / 149 assertions** — the six portable `.cljc` namespaces
  (`netmap` 8/27, `disco` 9/32, `magicdns` 7/17, `launchd` 4/12, `peer`+`relay`
  19/61). The counts agree with the cljs run namespace for namespace; the
  difference in totals is exactly the two `.cljs`-only namespaces
  (`application` 3/7, `signed-netmap` 1/3), which have no JVM counterpart.

**The E2E (`test/e2e.cljk`) is the one that matters**, and it runs real sockets:
a relay process, two agents, and these checks, all passing:

- both agents authenticate to the relay and register
- a Noise IK session is established **through** the relay
- the first path is the relay (connectivity before optimization)
- sealed application data arrives over it
- the relay forwarded by destination key only
- **the path upgrades from relay to direct on both sides**, via a candidate
  exchange over the relay — the hole-punch protocol end to end
- the direct path has a measured latency
- data flows over the direct path, and **the session was not re-handshaked** to
  change path
- MagicDNS answers a real DNS query with the peer's overlay address
- names outside the tailnet are refused, not NXDOMAINed

Two bugs the E2E found that no unit test with one handshake in flight could have,
now covered by regression tests in `peer_test`:

1. **A retry invalidated the in-flight handshake.** When the retry interval is
   shorter than the round trip, the response to attempt N arrives after attempt
   N+1 was sent; replacing the pending state made a *valid* response fail
   authentication, and the log said "authentication failed" — which reads like an
   attack rather than a race. Fixed by keeping the last few attempts, each with a
   generation id.
2. **An out-of-order response could downgrade a live session.** Both ends then
   held different sessions and every frame failed to authenticate. Fixed by
   adopting a response only if its generation is at least the current one.

### Asking from another process

`edge/principal-of` needs the registry, so it answers a service that shares
this process. `edge/principal-endpoint` is the same answer over loopback for
one that does not — `GET /principal?port=<source port>`, off unless
configured, and **bound to 127.0.0.1 with no option to change it**. On this
host the answer is metadata about a machine the operator already controls;
off it, it is a map of who is connected to what, and no deployment wants that
by accident.

A port nobody holds is **404, not 200 with a null peer**: a caller that reads
"no answer" and "the answer is nobody" as one value will eventually read one
of them as permission.

### A half-close that depended on arrival order (found 2026-08-19)

The E2E above sends bytes and a raw greeting; neither depends on **EOF**. An
HTTP response whose body is delimited only by connection close does, and it
failed **2 runs in 6** over the real overlay.

The state machine was right and the report was missing. `on-frame`'s `:fin`
branch says in its own comment that a FIN can overtake data it was sent after,
and it records `:fin-at` correctly when that happens — but it emitted
`:stream-peer-fin` only when the contiguous prefix had *already* reached the
FIN. When the data landed second and completed the prefix, the `:data` branch
returned `:events []`. So the peer's close was consumed into the state and
nobody was told; `stream-edge`'s forwarder calls `end` on its local socket only
on that event, so the client waited for a body that had already been written.
Flaky precisely because it depended on which frame arrived first.

`stream_test/a-fin-that-overtook-its-data-is-still-reported` pins it
deterministically — FIN delivered before the data it overtook — and fails on
exactly the missing event without the fix, with the in-order case kept beside
it as the control. The overlay probe went from 4/6 to **8/8**.

### Performance, measured and not flattering

Per-datagram cost in this stack today, 512-byte payloads (this workstation,
Node 26 / Temurin 21):

| | per packet | per IK handshake |
|---|---|---|
| nbb (SCI) + `noise.provider.node` | ~6–9 ms | ~150 ms |
| JVM + `noise.provider.jvm` | ~1.5–1.9 ms | ~75–110 ms |
| raw OpenSSL for comparison | 0.08 ms AEAD, 0.5 ms DH | — |

So roughly **100–600 packets/s**, and the crypto is *not* the bottleneck: the raw
primitives are one to two orders of magnitude faster than the end-to-end cost.
What remains is per-datagram marshalling between byte-vectors and platform
buffers plus interpreted glue. That is fine for what this carries today —
handshakes, control traffic, ssh/RPC-shaped sessions — and **not** fine for bulk
transfer. The honest next step is to keep bytes in platform buffers along the hot
path (behind the existing port boundary, so the protocol cores do not change) or
to compile the agent with shadow-cljs instead of interpreting it.

One measurement changed a design choice rather than a comment: `@noble/curves`
X25519 costs **27 ms per DH here** (confirmed in raw `node -e`, so not an nbb
artefact), which made handshakes visibly starve the event loop. Hence
`noise.provider.node` (Node's own OpenSSL) for this agent, with the noble
provider reserved for browsers.

### Honest gaps

- ~~**The agent still reads the netmap from a local EDN file without verifying a
  signature.**~~ **Closed 2026-08-06.** `agent/load-netmap` verifies an Ed25519
  envelope through `kekkai.node.signed-netmap` and only accepts raw EDN under an
  explicit `allow-unsigned-netmap?` opt-in. The remaining half was on the other
  side — nothing *emitted* a signed envelope, and until
  [`kekkai`](https://github.com/kotoba-lang/kekkai) gained `kekkai.netmap`
  (the projection) and `kekkai.envelope` (the signature), a node had nothing
  signed to read. `kekkai.node.publisher-parity-test` now verifies a real
  envelope produced by that publisher, byte for byte, so the two independently
  written implementations of one format cannot drift in silence.
- ~~**A forwarded service is not told which peer reached it.**~~ **Closed
  2026-08-19.** `stream-edge` still connects to the loopback service with an
  ordinary TCP socket — the address it sees is `127.0.0.1` and always will be
  — but `edge/principal-of` now answers the question that information loss
  used to make unanswerable. `handle-open!` records the socket's **source
  port** against the peer it already proved, and a co-located service reads
  `socket.remotePort` on the connection it is holding and asks.

  Not a PROXY-protocol header, deliberately: a header is only safe where every
  service on that port opts in, and the failure when one does not is a mangled
  first request rather than a refusal. Nothing is injected into the byte
  stream, so this works for protocols the agent does not parse — including
  HTTP, which `test/principal_e2e.cljk` now drives with a real `fetch`. The
  entry lives exactly as long as the socket, because source ports are reused
  and an entry that outlived its connection would name the wrong peer rather
  than no peer.

  One port per peer still works and is still the cheaper answer where the
  service cannot be changed: `permitted?` is per `(from, to, capability,
  port)`, so a peer granted only its own port cannot reach another's.
- **No real-NAT measurement.** The E2E's "direct path" is loopback, so it
  exercises the hole-punch *protocol*, not any particular NAT. Two peers both
  behind symmetric NATs cannot be punched at all, by construction, and stay
  relayed — `disco/path-report` and `stun/candidates`'s `:symmetric?` are the
  instruments for finding out what this fleet's NATs actually do.
- **No L3/TUN plane.** This carries overlay sessions and forwards service traffic;
  it does not present a network interface, so it is not a drop-in for a
  `100.x.y.z`-routes-everything VPN. `:node/overlay-ip` is used for naming
  (MagicDNS) rather than for routing packets. **What this used to block —
  `ssh` — no longer needs it**: `kekkai.node.stream` + `kekkai.node.stream-edge`
  forward TCP in userspace (see "SSH over the overlay" above). Everything that
  is not a forwardable TCP service still needs the TUN plane.
- **`netmap/permitted?` (port-level) and `netmap/sessionable` (inbound-only
  edges) exist and are tested, but the agent does not enforce them yet** — it
  gates on `dialable`/`:overlay` only.
- Rekeying is *reported* (`:rekey-due`) and sessions expire correctly, but the
  agent does not yet proactively re-handshake before expiry, so a long-lived
  session has a gap at the 180 s boundary until the next dial.
- One relay only: `relay/home` selects from measured latencies, but the agent uses
  the first relay in the netmap and does not probe a mesh of them.

## Design record

`com-junkawasaki/root` ADR-2607266500.
