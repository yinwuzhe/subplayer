import Foundation
import CoreFoundation

public enum ExternalSubtitle {
    public static let extensions = ["srt", "ass", "ssa", "vtt"]
    public static let maxBytes = 8 * 1024 * 1024

    public static func read(_ url: URL) throws -> Data {
        guard url.isFileURL, extensions.contains(url.pathExtension.lowercased()) else {
            throw PlaybackError.unavailable("请选择 SRT、ASS、SSA 或 VTT 字幕文件")
        }
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true else { throw PlaybackError.unavailable("请选择字幕文件") }
        guard (values.fileSize ?? 0) <= maxBytes else {
            throw PlaybackError.unavailable("字幕文件不能超过 8 MB")
        }
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        let data = try file.read(upToCount: maxBytes + 1) ?? Data()
        return try normalize(data, extension: url.pathExtension)
    }

    public static func normalize(_ data: Data, extension ext: String) throws -> Data {
        let ext = ext.lowercased()
        guard extensions.contains(ext) else {
            throw PlaybackError.unavailable("请选择 SRT、ASS、SSA 或 VTT 字幕文件")
        }
        guard !data.isEmpty, data.count <= maxBytes else {
            throw PlaybackError.unavailable("字幕为空或超过 8 MB")
        }
        let gb18030 = String.Encoding(rawValue:
            CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))
        let bom = Array(data.prefix(2))
        var decoded: String?
        if bom == [0xff, 0xfe] || bom == [0xfe, 0xff] {
            decoded = String(data: data, encoding: .utf16)
        } else {
            decoded = String(data: data, encoding: .utf8) ?? String(data: data, encoding: gb18030)
        }
        guard var text = decoded, !text.contains("\0") else {
            throw PlaybackError.unavailable("无法识别字幕编码，请另存为 UTF-8 后重试")
        }
        if text.hasPrefix("\u{feff}") { text.removeFirst() }
        text = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        let valid: Bool
        if ext == "ass" || ext == "ssa" {
            valid = text.lowercased().contains("[script info]") && text.lowercased().contains("[events]")
                && text.range(of: #"(?im)^\s*Dialogue\s*:"#, options: .regularExpression) != nil
        } else {
            valid = (ext != "vtt" || text.hasPrefix("WEBVTT"))
                && text.range(of: #"(?m)^\s*(?:\d+:)?\d{2}:\d{2}[,.]\d{3}\s+-->\s+(?:\d+:)?\d{2}:\d{2}[,.]\d{3}"#,
                              options: .regularExpression) != nil
        }
        guard valid else { throw PlaybackError.unavailable("字幕格式无效或没有可显示的字幕，请检查文件内容") }
        return Data(text.utf8)
    }
}
