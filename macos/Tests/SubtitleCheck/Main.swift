import Foundation
import CoreFoundation
import PlaybackCore

@main
struct SubtitleCheck {
    static func require(_ condition: Bool, _ message: String) throws {
        if !condition { throw PlaybackError.unavailable(message) }
    }

    static func rejects(_ data: Data, ext: String) throws {
        do {
            _ = try ExternalSubtitle.normalize(data, extension: ext)
        } catch { return }
        throw PlaybackError.unavailable("Invalid subtitle accepted: \(ext)")
    }

    static func main() {
        do {
            guard CommandLine.arguments.count == 2 else {
                throw PlaybackError.unavailable("Usage: SubtitleCheck /path/to/testdata/subtitles")
            }
            let root = URL(fileURLWithPath: CommandLine.arguments[1])
            for name in ["中文 sample.srt", "sample.ass", "sample.ssa", "sample.vtt"] {
                let data = try ExternalSubtitle.read(root.appendingPathComponent(name))
                try require(String(decoding: data, as: UTF8.self).contains("外挂字幕"), "Chinese text was corrupted")
                print("PASS: \(name)")
            }
            let text = "1\r\n00:00:00,000 --> 00:00:02,000\r\n中文字幕\r\n"
            let encodings: [String.Encoding] = [.utf8, .utf16,
                String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(
                    CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))]
            for encoding in encodings {
                guard let input = text.data(using: encoding) else { throw PlaybackError.unavailable("Encoding fixture failed") }
                let output = try ExternalSubtitle.normalize(input, extension: "SRT")
                try require(String(decoding: output, as: UTF8.self) == text.replacingOccurrences(of: "\r\n", with: "\n"), "Encoding conversion failed")
            }
            let bom = try ExternalSubtitle.normalize(Data(("\u{feff}" + text).utf8), extension: "srt")
            try require(!String(decoding: bom, as: UTF8.self).hasPrefix("\u{feff}"), "BOM not removed")
            print("PASS: UTF-8, UTF-16, GB18030, BOM, CRLF")
            try rejects(Data(), ext: "srt")
            try rejects(Data("not a subtitle".utf8), ext: "srt")
            try rejects(Data(text.utf8), ext: "mkv")
            try rejects(Data(text.utf8), ext: "vtt")
            try rejects(Data(text.utf8), ext: "ass")
            try rejects(Data((text + "\0").utf8), ext: "srt")
            try rejects(Data(repeating: 0, count: ExternalSubtitle.maxBytes + 1), ext: "srt")
            print("PASS: empty, malformed, unsupported, binary, oversized")
        } catch {
            fputs("FAIL: \(error.localizedDescription)\n", stderr)
            exit(1)
        }
    }
}
