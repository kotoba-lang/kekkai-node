# Kekkai OS packet tunnel

## Boundary

The packet tunnel is split into three independently enforced layers:

1. `kotoba/kekkai/packet_plane.kotoba` is the authority-free packet decision.
   It accepts five bounded integers and returns a closed action/reason code.
2. The OS adapter owns Network Extension, `VpnService`, `/dev/net/tun`, or the
   first-party Windows device handle. It validates packet structure before
   invoking the compiled Kotoba decision.
3. Kekkai-node owns authenticated Noise sessions, signed-netmap route
   selection, direct/relay transport, revocation, and peer identity.

The `.kotoba` guest never receives an OS descriptor or ambient socket. The
shared ABI is `aiueos:capability@0.4.0/packet-device`; the host owns its
non-serializable grant resource.

## Full-tunnel route model

A signed netmap grants both session establishment and packet forwarding:

```clojure
{:netmap/peers
 [{:node/id "exit-jp"
   :node/status "authorized"
   :node/routes
   [{:route/family 4 :route/prefix [0 0 0 0] :route/bits 0}
    {:route/family 6 :route/prefix [0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0]
     :route/bits 0}]}]
 :netmap/edges
 [{:edge/from "laptop-001" :edge/to "exit-jp"
   :edge/capabilities [:overlay :tun]}]}
```

Route selection is longest-prefix. Two equal best routes are ambiguous and
therefore denied. Relay and public mTLS management IPs must be excluded from
the installed default route so the tunnel cannot recursively capture its own
control traffic.

## Platform adapters

| Platform | Privileged adapter | Packet-plane binding |
|---|---|---|
| iOS/iPadOS | `NEPacketTunnelProvider` | iOS static AOT host, five scalar arguments |
| macOS | `NEPacketTunnelProvider` | same Apple static AOT boundary |
| Android | `VpnService` | build-gated host adapter verified against compiled Kotoba; native isolated host is a release gate |
| Linux | `/dev/net/tun`, `IFF_TUN | IFF_NO_PI` | local framed bridge; Node rechecks signed route |
| Windows | `\\.\Global\KekkaiTun` contract | userspace host; first-party NDIS package required |

The Apple adapter captures IPv4 and IPv6 default routes, excludes configured
relay addresses, sets an MTU of at least 1280, validates IPv4 total length and
IPv6 payload length, and drops when the signed Kekkai transport factory is not
installed.

The Android adapter creates a blocking dual-stack `VpnService`, parses and
bounds every packet, and drops while the injected Kekkai transport is not
ready. A native transport must call `VpnService.protect` on every relay,
discovery, and management socket before connecting.
Android `preBuild` compiles the authoritative `.kotoba` source to both the
Android KEXE and executable JavaScript, then checks the host adapter against
the executable artifact's boundary vectors. The KEXE is deliberately not
loaded inside the `VpnService` process: the compiler contract requires an
independently authenticating Android isolated-process host, and physical
Arm64-device evidence for that boundary remains a mobile release gate.

The Linux process has no network socket. It owns only `/dev/net/tun` and a
local Unix socket to the unprivileged Kekkai node, allowing systemd to restrict
it to `AF_UNIX` and `CAP_NET_ADMIN`.

Windows cannot provide a first-party full tunnel from userspace alone. The
userspace device contract and client are present, but a production NDIS/WDK
driver, Microsoft signing, physical Windows testing, installer, and rollback
evidence remain release gates. This repository does not silently substitute
Wintun or another market driver.

## Tailscale coexistence

Nothing in this change uninstalls or disables Tailscale.

- During migration, desktop route metrics decide whether Kekkai or Tailscale
  owns a destination.
- iOS and Android normally permit only one active packet VPN. Tailscale remains
  installed and enrolled, but cannot carry the same default route
  simultaneously with Kekkai `NetworkExtension`/`VpnService`.
- Public mTLS management remains outside both tunnels.
- Moving a device from Tailscale to Kekkai requires an explicit MDM command and
  can be rolled back without removing either application.

## Build and verification

```sh
npm test
npm run build:packet-plane
npm run test:android-tunnel
cargo check --manifest-path platform/desktop/Cargo.toml
swiftc -typecheck -framework NetworkExtension \
  platform/apple/Sources/KekkaiTunnel/*.swift
```

Apple device execution additionally requires the Network Extension entitlement,
an App ID/provisioning profile, compilation and linking of
`packet-plane-ios.S`, `kotoba_ios_host.c`, and
`KotobaPacketPlaneBridge.c`, and installation on a signed extension target.
These Apple-controlled credentials are deployment inputs, not repository
secrets.
