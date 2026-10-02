import Foundation
import Darwin

enum CastError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        if case .message(let text) = self { return text }
        return nil
    }
}

@MainActor
final class MacCastCoordinator {
    private(set) var isCasting = false
    private(set) var isPlaying = false
    private(set) var deviceName = ""
    var onStatus: ((String) -> Void)?
    private var server: VideoHTTPServer?
    private var transport: DlnaTransport?
    private var activity: NSObjectProtocol?

    func start(video: URL, on device: DlnaDevice) async throws {
        if isCasting { await stop() }
        let stream = try VideoHTTPServer(file: video)
        server = stream
        do {
            let ip = try Self.localAddress(for: device.controlURL)
            let videoURL = "http://\(ip):\(stream.port)\(stream.path)"
            let remote = DlnaTransport(device: device)
            transport = remote
            try await remote.setVideo(url: videoURL, title: stream.title, mime: stream.mime)
            try await remote.play()
            deviceName = device.name
            isCasting = true
            isPlaying = true
            activity = ProcessInfo.processInfo.beginActivity(
                options: [.userInitiated, .idleSystemSleepDisabled],
                reason: "SubPlayer 正在向电视提供视频")
            onStatus?("已投屏到 \(device.name) · 视频由 Mac 直接提供")
        } catch {
            server?.stop()
            server = nil
            transport = nil
            throw error
        }
    }

    func togglePlayback() async throws {
        guard let remote = transport, isCasting else { return }
        if isPlaying { try await remote.pause() } else { try await remote.play() }
        isPlaying.toggle()
        onStatus?(isPlaying ? "电视继续播放" : "电视已暂停")
    }

    func seek(seconds: Int) async throws {
        guard let remote = transport, isCasting else { return }
        let time = try await remote.seek(seconds: seconds)
        onStatus?("电视已跳转至 \(time)")
    }

    func stop() async {
        if let remote = transport { try? await remote.stop() }
        close()
        onStatus?("已结束投屏，可继续在 Mac 上播放")
    }

    func close() {
        server?.stop()
        server = nil
        transport = nil
        isCasting = false
        isPlaying = false
        deviceName = ""
        if let activity = activity {
            ProcessInfo.processInfo.endActivity(activity)
            self.activity = nil
        }
    }

    private static func localAddress(for url: URL) throws -> String {
        guard let host = url.host else { throw CastError.message("电视地址无效") }
        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_DGRAM
        var resolved: UnsafeMutablePointer<addrinfo>?
        let port = String(url.port ?? 80)
        guard getaddrinfo(host, port, &hints, &resolved) == 0, let target = resolved else {
            throw CastError.message("无法解析电视地址")
        }
        defer { freeaddrinfo(resolved) }
        let fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { throw CastError.message("无法访问 Wi-Fi 网络") }
        defer { Darwin.close(fd) }
        guard connect(fd, target.pointee.ai_addr, target.pointee.ai_addrlen) == 0 else {
            throw CastError.message("Mac 与电视不在可互通的网络中")
        }
        var local = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let result = withUnsafeMutablePointer(to: &local) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
        }
        guard result == 0 else { throw CastError.message("无法获取 Mac 的局域网地址") }
        var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &local.sin_addr, &buffer, socklen_t(buffer.count)) != nil else {
            throw CastError.message("无法获取 Mac 的局域网 IP")
        }
        return String(cString: buffer)
    }
}

private final class DlnaTransport {
    private let device: DlnaDevice

    init(device: DlnaDevice) { self.device = device }

    func setVideo(url: String, title: String, mime: String) async throws {
        let protocolInfo = "http-get:*:\(mime):DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        let metadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
            + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
            + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
            + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
            + "<dc:title>\(Self.xml(title))</dc:title><upnp:class>object.item.videoItem</upnp:class>"
            + "<res protocolInfo=\"\(Self.xml(protocolInfo))\">\(Self.xml(url))</res>"
            + "</item></DIDL-Lite>"
        let uri = "<CurrentURI>\(Self.xml(url))</CurrentURI>"
        do {
            _ = try await call("SetAVTransportURI", arguments: uri
                + "<CurrentURIMetaData>\(Self.xml(metadata))</CurrentURIMetaData>")
        } catch {
            _ = try await call("SetAVTransportURI", arguments: uri
                + "<CurrentURIMetaData></CurrentURIMetaData>")
        }
    }

    func play() async throws { _ = try await call("Play", arguments: "<Speed>1</Speed>") }
    func pause() async throws { _ = try await call("Pause") }
    func stop() async throws { _ = try await call("Stop") }

    func seek(seconds: Int) async throws -> String {
        let data = try await call("GetPositionInfo")
        let parser = PositionParser()
        let xml = XMLParser(data: data)
        xml.shouldResolveExternalEntities = false
        xml.delegate = parser
        guard xml.parse(), let time = parser.relative,
              let current = Self.seconds(time) else {
            throw CastError.message("电视不支持读取播放进度")
        }
        var target = max(0, current + seconds)
        if let duration = parser.duration.flatMap(Self.seconds) { target = min(target, duration) }
        let formatted = String(format: "%02d:%02d:%02d", target / 3600,
                               (target / 60) % 60, target % 60)
        _ = try await call("Seek", arguments: "<Unit>REL_TIME</Unit><Target>\(formatted)</Target>")
        return formatted
    }

    private func call(_ action: String, arguments: String = "") async throws -> Data {
        let type = device.serviceType
        let body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
            + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
            + "<s:Body><u:\(action) xmlns:u=\"\(Self.xml(type))\">"
            + "<InstanceID>0</InstanceID>\(arguments)</u:\(action)></s:Body></s:Envelope>"
        var request = URLRequest(url: device.controlURL)
        request.httpMethod = "POST"
        request.timeoutInterval = 6
        request.setValue("text/xml; charset=\"utf-8\"", forHTTPHeaderField: "Content-Type")
        request.setValue("\"\(type)#\(action)\"", forHTTPHeaderField: "SOAPACTION")
        request.httpBody = Data(body.utf8)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let status = (response as? HTTPURLResponse)?.statusCode, status < 400 else {
            throw CastError.message("电视拒绝 \(action) 操作")
        }
        guard data.count < 256_000,
              let text = String(data: data, encoding: .utf8),
              !text.contains("<!DOCTYPE"), !text.contains("<!ENTITY") else {
            throw CastError.message("电视返回了无效数据")
        }
        return data
    }

    private static func xml(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }

    private static func seconds(_ time: String) -> Int? {
        let parts = time.split(separator: ":")
        guard parts.count == 3,
              let hours = Int(parts[0]), let minutes = Int(parts[1]),
              let seconds = Double(parts[2]) else { return nil }
        return hours * 3600 + minutes * 60 + Int(seconds)
    }
}

private final class PositionParser: NSObject, XMLParserDelegate {
    var relative: String?
    var duration: String?
    private var content = ""

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String]) {
        content = ""
    }
    func parser(_ parser: XMLParser, foundCharacters string: String) { content += string }
    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?) {
        let key = elementName.components(separatedBy: ":").last ?? elementName
        if key == "RelTime" { relative = content }
        if key == "TrackDuration" { duration = content }
        content = ""
    }
}
