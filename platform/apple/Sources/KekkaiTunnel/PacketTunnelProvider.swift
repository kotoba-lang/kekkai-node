import Foundation
import NetworkExtension

public final class PacketTunnelProvider: NEPacketTunnelProvider {
    private var transport: KekkaiPacketTransport?
    private var policy = KotobaPacketPolicy(mtu: 1280)
    private var running = false

    public override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        guard !running, let transport = KekkaiTransportFactory.make() else {
            completionHandler(NSError(
                domain: "jp.kotoba.kekkai.tunnel", code: 1,
                userInfo: [NSLocalizedDescriptionKey:
                    "signed Kekkai native transport is unavailable"]))
            return
        }
        let configuration =
            (protocolConfiguration as? NETunnelProviderProtocol)?
                .providerConfiguration ?? [:]
        let mtu = min(max(configuration["mtu"] as? Int ?? 1280, 1280), 9000)
        policy = KotobaPacketPolicy(mtu: mtu)
        self.transport = transport

        let settings = makeSettings(configuration: configuration, mtu: mtu)
        setTunnelNetworkSettings(settings) { [weak self] error in
            guard error == nil, let self else {
                completionHandler(error)
                return
            }
            transport.start(
                configuration: configuration,
                receive: { [weak self] packet, family in
                    guard let self, self.running,
                          let parsed = ParsedPacket(packet),
                          self.policy.decide(
                            packet: parsed,
                            transportReady: transport.ready
                          ) == .overlay else { return }
                    self.packetFlow.writePackets(
                        [parsed.bytes], withProtocols: [family])
                },
                completion: { [weak self] transportError in
                    guard let self else { return }
                    if let transportError {
                        completionHandler(transportError)
                        return
                    }
                    self.running = true
                    self.readPackets()
                    completionHandler(nil)
                })
        }
    }

    public override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        running = false
        transport?.stop()
        transport = nil
        completionHandler()
    }

    private func readPackets() {
        guard running, let transport else { return }
        packetFlow.readPackets { [weak self] packets, families in
            guard let self, self.running else { return }
            for (packet, family) in zip(packets, families) {
                guard let parsed = ParsedPacket(packet),
                      self.policy.decide(
                        packet: parsed,
                        transportReady: transport.ready
                      ) == .overlay else { continue }
                transport.send(
                    packet: parsed.bytes,
                    protocolFamily: family,
                    completion: { _ in /* denial/drop is recorded by runtime */ })
            }
            self.readPackets()
        }
    }

    private func makeSettings(
        configuration: [String: Any],
        mtu: Int
    ) -> NEPacketTunnelNetworkSettings {
        let remote = configuration["remoteAddress"] as? String ?? "127.0.0.1"
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: remote)
        settings.mtu = NSNumber(value: mtu)

        let ipv4 = NEIPv4Settings(
            addresses: [configuration["ipv4Address"] as? String ?? "100.96.0.2"],
            subnetMasks: [configuration["ipv4Mask"] as? String ?? "255.255.255.255"])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        ipv4.excludedRoutes =
            (configuration["relayIPv4"] as? [String] ?? []).map {
                NEIPv4Route(destinationAddress: $0,
                            subnetMask: "255.255.255.255")
            }
        settings.ipv4Settings = ipv4

        let ipv6 = NEIPv6Settings(
            addresses: [configuration["ipv6Address"] as? String ?? "fd7a:6b65::2"],
            networkPrefixLengths: [128])
        ipv6.includedRoutes = [NEIPv6Route.default()]
        ipv6.excludedRoutes =
            (configuration["relayIPv6"] as? [String] ?? []).map {
                NEIPv6Route(destinationAddress: $0, networkPrefixLength: 128)
            }
        settings.ipv6Settings = ipv6

        let dns = NEDNSSettings(
            servers: configuration["dnsServers"] as? [String] ??
                ["100.96.0.1", "fd7a:6b65::1"])
        dns.matchDomains = [""]
        settings.dnsSettings = dns
        return settings
    }
}
