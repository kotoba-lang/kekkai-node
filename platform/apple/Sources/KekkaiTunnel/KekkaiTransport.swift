import Foundation

public protocol KekkaiPacketTransport: AnyObject {
    var ready: Bool { get }
    func start(
        configuration: [String: Any],
        receive: @escaping (Data, NSNumber) -> Void,
        completion: @escaping (Error?) -> Void
    )
    func send(
        packet: Data,
        protocolFamily: NSNumber,
        completion: @escaping (Error?) -> Void
    )
    func stop()
}

public enum KekkaiTransportFactory {
    private static let lock = NSLock()
    private static var builder: (() -> KekkaiPacketTransport)?

    /// The signed Kekkai native runtime installs exactly one transport builder
    /// before NetworkExtension starts. Absence is a hard failure, never a raw
    /// UDP fallback.
    public static func install(_ value: @escaping () -> KekkaiPacketTransport) {
        lock.lock()
        defer { lock.unlock() }
        precondition(builder == nil, "Kekkai transport factory already installed")
        builder = value
    }

    static func make() -> KekkaiPacketTransport? {
        lock.lock()
        defer { lock.unlock() }
        return builder?()
    }
}
