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
nbb --classpath "$CP" bin/relay.cljs relay.edn

# a node agent
nbb --classpath "$CP" bin/agent.cljs kekkai-node.edn

# macOS residency: print the LaunchDaemon + split-DNS resolver, then install
nbb --classpath "$CP" bin/install.cljs kekkai-node.edn
sudo nbb --classpath "$CP" bin/install.cljs kekkai-node.edn --write

# CP="src:../bytes/src:../noise/src:../org-ietf-dns/src:../org-ietf-turn/src"
```

Residency is a **LaunchDaemon**, not a LaunchAgent, for the reason murakumo's
README documents the hard way: a user agent dies with the login session and does
not even appear in `launchctl list` over SSH. MagicDNS binds **5354, not 53**, so
the agent needs no root; `/etc/resolver/<tailnet>` points the system at it.

## Verification

```bash
clojure -M:test                                              # pure cores, JVM
nbb --classpath "$CP" run-tests.cljs                         # pure cores, cljs
nbb --classpath "$CP" test/e2e.cljs                          # real UDP, end to end
clojure -M:lint
```

Measured 2026-07-26, all green:

- **cljs (nbb): 51 tests / 159 assertions** — every namespace.
- **JVM: 47 tests / 149 assertions** — the six portable `.cljc` namespaces
  (`netmap` 8/27, `disco` 9/32, `magicdns` 7/17, `launchd` 4/12, `peer`+`relay`
  19/61). The counts agree with the cljs run namespace for namespace; the
  difference in totals is exactly the two `.cljs`-only namespaces
  (`application` 3/7, `signed-netmap` 1/3), which have no JVM counterpart.

**The E2E (`test/e2e.cljs`) is the one that matters**, and it runs real sockets:
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
- **A forwarded service is not told which peer reached it.** `stream-edge`
  authorises the *proven* peer key in `handle-open!` and then connects to the
  loopback service with an ordinary TCP socket, so the service sees
  `127.0.0.1` and every downstream authorisation that derives a principal from
  the peer address fails closed. Measured 2026-08-19 by `test/principal_e2e.cljs`.
  **One port per peer recovers the principal without a protocol change** —
  `permitted?` is already per `(from, to, capability, port)`, so a peer granted
  only its own port cannot reach another's, and the probe shows both the
  discrimination and the erasure. The general fix is to carry the proven key
  to the service (a per-peer unix socket, or one framed header) rather than
  letting each application re-derive a principal from a network address.
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
