import Foundation

@_silgen_name("kekkai_packet_decide_v1")
private func kekkaiPacketDecide(
    _ family: Int64,
    _ packetLength: Int64,
    _ mtu: Int64,
    _ routeAction: Int64,
    _ transportReady: Int64
) -> Int64

enum PacketDecision: Int64 {
    case overlay = 100
    case bypass = 200
}

struct ParsedPacket {
    let family: Int64
    let bytes: Data

    init?(_ bytes: Data) {
        guard let first = bytes.first else { return nil }
        switch first >> 4 {
        case 4:
            guard bytes.count >= 20 else { return nil }
            let headerLength = Int(first & 0x0f) * 4
            guard headerLength >= 20, bytes.count >= headerLength else { return nil }
            let total = bytes.withUnsafeBytes {
                Int($0.loadUnaligned(fromByteOffset: 2, as: UInt16.self).bigEndian)
            }
            guard total >= headerLength, total <= bytes.count else { return nil }
            family = 4
            self.bytes = bytes.prefix(total)
        case 6:
            guard bytes.count >= 40 else { return nil }
            let payload = bytes.withUnsafeBytes {
                Int($0.loadUnaligned(fromByteOffset: 4, as: UInt16.self).bigEndian)
            }
            guard 40 + payload <= bytes.count else { return nil }
            family = 6
            self.bytes = bytes.prefix(40 + payload)
        default:
            return nil
        }
    }
}

struct KotobaPacketPolicy {
    let mtu: Int

    func decide(packet: ParsedPacket, transportReady: Bool) -> PacketDecision? {
        // Captured packets are covered by the signed full/subnet route. Relay
        // and management endpoints are excluded at NEPacketTunnel settings.
        let value = kekkaiPacketDecide(
            packet.family,
            Int64(packet.bytes.count),
            Int64(mtu),
            1,
            transportReady ? 1 : 0
        )
        return PacketDecision(rawValue: value)
    }
}
