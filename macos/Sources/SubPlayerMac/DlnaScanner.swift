import Foundation
import Darwin

struct DlnaDevice {
    let name: String
    let controlURL: URL
    let serviceType: String
}

final class DlnaScanner {
    private var job: DispatchWorkItem?

    func start(onDevice: @escaping (DlnaDevice) -> Void, onComplete: @escaping (String?) -> Void) {
        cancel()
        let task = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            let fd = socket(AF_INET, SOCK_DGRAM, 0)
            guard fd >= 0 else {
                DispatchQueue.main.async { onComplete("无法开启局域网搜索") }
                return
            }
            defer { Darwin.close(fd) }
            var timeout = timeval(tv_sec: 0, tv_usec: 600_000)
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            var address = sockaddr_in()
            address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            address.sin_family = sa_family_t(AF_INET)
            address.sin_port = UInt16(1900).bigEndian
            inet_pton(AF_INET, "239.255.255.250", &address.sin_addr)
            for target in ["urn:schemas-upnp-org:device:MediaRenderer:1",
                           "urn:schemas-upnp-org:service:AVTransport:1"] {
                let request = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: \(target)\r\n\r\n"
                let bytes = Array(request.utf8)
                bytes.withUnsafeBytes { data in
                    withUnsafePointer(to: &address) { pointer in
                        pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                            _ = sendto(fd, data.baseAddress, data.count, 0, $0,
                                       socklen_t(MemoryLayout<sockaddr_in>.size))
                        }
                    }
                }
            }
            let end = Date().addingTimeInterval(6)
            var seen = Set<String>()
            var found = false
            while !self.jobIsCancelled && Date() < end {
                var buffer = [UInt8](repeating: 0, count: 8192)
                let count = buffer.withUnsafeMutableBytes { recv(fd, $0.baseAddress, $0.count, 0) }
                guard count > 0 else { continue }
                let text = String(decoding: buffer.prefix(count), as: UTF8.self)
                guard let location = text.components(separatedBy: .newlines)
                    .first(where: { $0.lowercased().hasPrefix("location:") })?
                    .dropFirst("location:".count)
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                    let url = URL(string: location), url.scheme?.lowercased() == "http",
                    seen.insert(location).inserted else { continue }
                guard let device = self.describe(url: url), !self.jobIsCancelled else { continue }
                found = true
                DispatchQueue.main.async { [weak self] in
                    if self?.jobIsCancelled == false { onDevice(device) }
                }
            }
            DispatchQueue.main.async { [weak self] in
                if self?.jobIsCancelled == false {
                    onComplete(found ? nil : "未找到 DLNA 电视，请确认电视已开启投屏并连接同一 Wi-Fi")
                }
            }
        }
        job = task
        DispatchQueue.global(qos: .userInitiated).async(execute: task)
    }

    func cancel() { job?.cancel(); job = nil }
    private var jobIsCancelled: Bool { job?.isCancelled ?? true }

    private func describe(url: URL) -> DlnaDevice? {
        let semaphore = DispatchSemaphore(value: 0)
        var result: (Data?, URLResponse?, Error?) = (nil, nil, nil)
        var request = URLRequest(url: url)
        request.timeoutInterval = 3
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            result = (data, response, error)
            semaphore.signal()
        }
        task.resume()
        guard semaphore.wait(timeout: .now() + 3) == .success else { task.cancel(); return nil }
        guard let data = result.0, data.count <= 256_000,
              (result.1 as? HTTPURLResponse)?.statusCode == 200,
              let text = String(data: data, encoding: .utf8),
              !text.contains("<!DOCTYPE"), !text.contains("<!ENTITY") else { return nil }
        let parser = DeviceDescriptionParser()
        let xml = XMLParser(data: data)
        xml.shouldResolveExternalEntities = false
        xml.delegate = parser
        guard xml.parse(), let service = parser.transport else { return nil }
        let base = parser.urlBase.flatMap(URL.init(string:)) ?? url
        guard let control = URL(string: service.path, relativeTo: base)?.absoluteURL,
              control.scheme?.lowercased() == "http" else { return nil }
        return DlnaDevice(name: parser.name ?? url.host ?? "电视",
                          controlURL: control, serviceType: service.type)
    }
}

private final class DeviceDescriptionParser: NSObject, XMLParserDelegate {
    var name: String?
    var urlBase: String?
    var transport: (type: String, path: String)?
    private var element = ""
    private var content = ""
    private var inService = false
    private var serviceType: String?
    private var controlPath: String?

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String]) {
        element = elementName.components(separatedBy: ":").last ?? elementName
        content = ""
        if element == "service" { inService = true; serviceType = nil; controlPath = nil }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { content += string }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?) {
        let key = elementName.components(separatedBy: ":").last ?? elementName
        let value = content.trimmingCharacters(in: .whitespacesAndNewlines)
        switch key {
        case "friendlyName": name = value
        case "URLBase": urlBase = value
        case "serviceType" where inService: serviceType = value
        case "controlURL" where inService: controlPath = value
        case "service":
            if let type = serviceType, type.hasPrefix("urn:schemas-upnp-org:service:AVTransport:"),
               let path = controlPath, !path.isEmpty { transport = (type, path) }
            inService = false
        default: break
        }
        element = ""
        content = ""
    }
}
