import Foundation
import UniformTypeIdentifiers
import Darwin

final class VideoHTTPServer {
    let port: UInt16
    let path = "/video/\(UUID().uuidString)"
    let title: String
    let mime: String
    private let url: URL
    private let size: UInt64
    private let listenerFD: Int32
    private let lock = NSLock()
    private var active = true
    private var clients = Set<Int32>()

    init(file: URL) throws {
        guard file.isFileURL,
              let attributes = try? FileManager.default.attributesOfItem(atPath: file.path),
              let number = attributes[.size] as? NSNumber, number.uint64Value > 0 else {
            throw CastError.message("视频文件无法读取或大小未知")
        }
        url = file
        size = number.uint64Value
        title = file.lastPathComponent
        let detected = UTType(filenameExtension: file.pathExtension)?.preferredMIMEType
        mime = detected?.hasPrefix("video/") == true ? detected! : "video/mp4"
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw CastError.message("无法开启视频服务") }
        var reuse: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0
        address.sin_addr.s_addr = in_addr_t(0)
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, listen(fd, 8) == 0 else {
            Darwin.close(fd)
            throw CastError.message("局域网视频服务无法监听")
        }
        var assigned = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let resolved = withUnsafeMutablePointer(to: &assigned) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
        }
        guard resolved == 0 else {
            Darwin.close(fd)
            throw CastError.message("无法获取视频服务端口")
        }
        port = UInt16(bigEndian: assigned.sin_port)
        listenerFD = fd
        DispatchQueue.global(qos: .utility).async { [weak self] in self?.acceptConnections() }
    }

    private var isActive: Bool {
        lock.lock(); defer { lock.unlock() }
        return active
    }

    private func acceptConnections() {
        while isActive {
            let client = accept(listenerFD, nil, nil)
            if client < 0 { break }
            lock.lock()
            if active { clients.insert(client) }
            let shouldHandle = active
            lock.unlock()
            if shouldHandle {
                DispatchQueue.global(qos: .utility).async { [weak self] in self?.handle(client) }
            } else {
                Darwin.close(client)
            }
        }
    }

    private func handle(_ fd: Int32) {
        defer {
            lock.lock(); clients.remove(fd); lock.unlock()
            Darwin.close(fd)
        }
        var noPipe: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &noPipe, socklen_t(MemoryLayout<Int32>.size))
        var timeout = timeval(tv_sec: 60, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        var request = Data()
        while request.count < 16384 && request.range(of: Data("\r\n\r\n".utf8)) == nil {
            var bytes = [UInt8](repeating: 0, count: 4096)
            let count = bytes.withUnsafeMutableBytes { recv(fd, $0.baseAddress, $0.count, 0) }
            if count <= 0 { return }
            request.append(contentsOf: bytes.prefix(count))
        }
        guard let text = String(data: request, encoding: .utf8),
              text.contains("\r\n\r\n") else { return }
        let lines = text.components(separatedBy: "\r\n")
        let requestLine = lines[0].split(separator: " ")
        guard requestLine.count >= 2, String(requestLine[1]) == path,
              requestLine[0] == "GET" || requestLine[0] == "HEAD" else {
            sendText("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", to: fd)
            return
        }
        var start: UInt64 = 0
        var end = size - 1
        let range = lines.first { $0.lowercased().hasPrefix("range:") }?
            .dropFirst("range:".count).trimmingCharacters(in: .whitespaces)
        if let range = range {
            let components = range.replacingOccurrences(of: "bytes=", with: "")
                .split(separator: "-", omittingEmptySubsequences: false)
            if range.hasPrefix("bytes="), components.count == 2, !range.contains(",") {
                if components[0].isEmpty, let suffix = UInt64(components[1]), suffix > 0 {
                    start = size > suffix ? size - suffix : 0
                } else if let first = UInt64(components[0]),
                          components[1].isEmpty || UInt64(components[1]) != nil {
                    start = first
                    if let last = UInt64(components[1]) { end = min(end, last) }
                } else { start = size }
            } else { start = size }
            if start >= size || end < start {
                sendText("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */\(size)\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", to: fd)
                return
            }
        }
        let length = end - start + 1
        let response = "HTTP/1.1 \(range == nil ? "200 OK" : "206 Partial Content")\r\n"
            + "Content-Type: \(mime)\r\nContent-Length: \(length)\r\nAccept-Ranges: bytes\r\n"
            + "transferMode.dlna.org: Streaming\r\n"
            + "contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n"
            + (range == nil ? "" : "Content-Range: bytes \(start)-\(end)/\(size)\r\n")
            + "Connection: close\r\n\r\n"
        if requestLine[0] == "HEAD" {
            sendText(response, to: fd)
            return
        }
        guard let file = try? FileHandle(forReadingFrom: url) else { return }
        defer { try? file.close() }
        do { try file.seek(toOffset: start) } catch { return }
        guard sendText(response, to: fd) else { return }
        var remaining = length
        while remaining > 0 && isActive {
            guard let chunk = try? file.read(upToCount: Int(min(remaining, 128 * 1024))),
                  !chunk.isEmpty, sendData(chunk, to: fd) else { return }
            remaining -= UInt64(chunk.count)
        }
    }

    @discardableResult private func sendText(_ text: String, to fd: Int32) -> Bool {
        sendData(Data(text.utf8), to: fd)
    }

    private func sendData(_ data: Data, to fd: Int32) -> Bool {
        data.withUnsafeBytes { bytes in
            guard let base = bytes.baseAddress else { return false }
            var offset = 0
            while offset < bytes.count {
                let sent = send(fd, base.advanced(by: offset), bytes.count - offset, 0)
                if sent <= 0 { return false }
                offset += sent
            }
            return true
        }
    }

    func stop() {
        lock.lock()
        guard active else { lock.unlock(); return }
        active = false
        let openClients = Array(clients)
        lock.unlock()
        shutdown(listenerFD, SHUT_RDWR)
        Darwin.close(listenerFD)
        for client in openClients { shutdown(client, SHUT_RDWR) }
    }

    deinit { stop() }
}
