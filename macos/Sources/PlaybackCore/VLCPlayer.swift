import AppKit
import CVLC

public struct SubtitleTrack: Equatable, Sendable {
    public let id: Int32
    public let name: String
}

public enum PlaybackState: Sendable {
    case idle, opening, playing, paused, ended, failed
}

public struct PlaybackSnapshot: Sendable {
    public let state: PlaybackState
    public let seconds: Double
    public let duration: Double
    public let seekable: Bool
    public let speed: Float
    public let subtitles: [SubtitleTrack]
    public let selectedSubtitle: Int32
    public let decodedVideo: Int32
    public let displayedPictures: Int32
    public let decodedAudio: Int32
    public let playedAudioBuffers: Int32
}

public enum PlaybackError: LocalizedError {
    case unavailable(String)
    public var errorDescription: String? {
        switch self { case .unavailable(let message): return message }
    }
}

private final class VLCSession: @unchecked Sendable {
    let instance: OpaquePointer
    let player: OpaquePointer
    var closed = false
    var desiredSpeed: Float = 1
    var subtitleDirectories: [URL] = []
    var externalSubtitles: [String: Int32] = [:]

    func clearExternalSubtitles() {
        for directory in subtitleDirectories { try? FileManager.default.removeItem(at: directory) }
        subtitleDirectories.removeAll()
        externalSubtitles.removeAll()
    }

    init(drawable: NSView, runtime: URL) throws {
        setenv("VLC_PLUGIN_PATH", runtime.appendingPathComponent("plugins").path, 1)
        setenv("VLC_DATA_PATH", runtime.appendingPathComponent("share").path, 1)
        let options = ["--no-video-title-show", "--no-osd", "--no-snapshot-preview",
                       "--no-plugins-cache", "--no-lua", "--vout=macosx", "--verbose=0"]
        let strings = options.map { strdup($0) }
        defer { strings.forEach { free($0) } }
        var pointers = strings.map { $0.map { UnsafePointer($0) } }
        guard let instance = libvlc_new(Int32(pointers.count), &pointers) else {
            throw PlaybackError.unavailable("VLC 播放内核初始化失败，请重新安装完整的 App")
        }
        guard let player = libvlc_media_player_new(instance) else {
            libvlc_release(instance)
            throw PlaybackError.unavailable("无法创建视频播放器")
        }
        self.instance = instance
        self.player = player
        libvlc_media_player_set_nsobject(player, Unmanaged.passUnretained(drawable).toOpaque())
    }
}

@MainActor
public final class VLCPlayer {
    public let view = NSView()
    private let queue = DispatchQueue(label: "com.subplayer.vlc.playback", qos: .userInitiated)
    private var session: VLCSession?
    public private(set) var hasMedia = false

    public init() throws {
        let bundled = Bundle.main.bundleURL.appendingPathComponent("Contents/Frameworks/VLC")
        let runtime = FileManager.default.fileExists(atPath: bundled.appendingPathComponent("plugins").path)
            ? bundled : ProcessInfo.processInfo.environment["SUBPLAYER_VLC_ROOT"].map(URL.init(fileURLWithPath:))
        guard let runtime = runtime,
              FileManager.default.fileExists(atPath: runtime.appendingPathComponent("plugins").path) else {
            throw PlaybackError.unavailable("应用缺少 VLC 解码插件，请使用完整打包的 SubPlayer-Mac.app")
        }
        view.autoresizingMask = [.width, .height]
        session = try VLCSession(drawable: view, runtime: runtime)
    }

    public func open(_ url: URL) async throws {
        guard url.isFileURL, FileManager.default.isReadableFile(atPath: url.path) else {
            throw PlaybackError.unavailable("文件不存在或没有读取权限")
        }
        try await perform { session in
            libvlc_media_player_stop(session.player)
            session.clearExternalSubtitles()
            guard let media = libvlc_media_new_path(session.instance, url.path) else {
                throw PlaybackError.unavailable("无法读取视频文件")
            }
            defer { libvlc_media_release(media) }
            libvlc_media_add_option(media, ":file-caching=500")
            libvlc_media_player_set_media(session.player, media)
            session.desiredSpeed = 1
            _ = libvlc_media_player_set_rate(session.player, 1)
            guard libvlc_media_player_play(session.player) == 0 else {
                throw PlaybackError.unavailable("视频无法开始播放")
            }
        }
        hasMedia = true
    }

    public func play() async throws {
        try await perform { session in
            if libvlc_media_player_get_state(session.player) == libvlc_Ended {
                libvlc_media_player_stop(session.player)
            }
            guard libvlc_media_player_play(session.player) == 0 else {
                throw PlaybackError.unavailable("无法继续播放")
            }
            _ = libvlc_media_player_set_rate(session.player, session.desiredSpeed)
        }
    }

    public func pause() async throws {
        try await perform { libvlc_media_player_set_pause($0.player, 1) }
    }

    public func setSpeed(_ speed: Float) async throws {
        try await perform { session in
            guard libvlc_media_player_set_rate(session.player, speed) == 0 else {
                throw PlaybackError.unavailable("当前视频不支持该倍速")
            }
            session.desiredSpeed = speed
        }
    }

    public func seek(to seconds: Double) async throws {
        guard seconds.isFinite else { return }
        try await perform { session in
            guard libvlc_media_player_is_seekable(session.player) != 0 else {
                throw PlaybackError.unavailable("当前视频暂时无法跳转")
            }
            let duration = libvlc_media_player_get_length(session.player)
            let target = Int64(max(0, min(seconds * 1000, Double(max(0, duration - 1)))))
            libvlc_media_player_set_time(session.player, target)
        }
    }

    public func loadSubtitle(_ url: URL) async throws {
        guard hasMedia else { throw PlaybackError.unavailable("请先打开视频") }
        let key = url.standardizedFileURL.resolvingSymlinksInPath().path
        let before = try await snapshot()
        guard before.state == .playing || before.state == .paused else {
            throw PlaybackError.unavailable("请等待视频加载完成，或继续播放后再加载字幕")
        }
        let existing: Int32? = try await perform { $0.externalSubtitles[key] }
        if let existing = existing, before.subtitles.contains(where: { $0.id == existing }) {
            try await selectSubtitle(existing)
            return
        }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        try await perform { session in
            let data = try ExternalSubtitle.read(url)
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("SubPlayer-subtitle-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let copy = directory.appendingPathComponent(url.lastPathComponent)
            do {
                try data.write(to: copy, options: .atomic)
                guard libvlc_media_player_add_slave(session.player, libvlc_media_slave_type_subtitle,
                                                   copy.absoluteString, true) == 0 else {
                    throw PlaybackError.unavailable("VLC 无法加载该字幕文件")
                }
                session.subtitleDirectories.append(directory)
            } catch {
                try? FileManager.default.removeItem(at: directory)
                throw error
            }
        }
        let oldIDs = Set(before.subtitles.map(\.id))
        for _ in 0..<50 {
            let current = try await snapshot()
            if let added = current.subtitles.first(where: { !oldIDs.contains($0.id) }) {
                try await selectSubtitle(added.id)
                try await perform { $0.externalSubtitles[key] = added.id }
                return
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        throw PlaybackError.unavailable("字幕轨道尚未就绪，请继续播放后查看字幕菜单，或检查字幕内容")
    }

    public func selectSubtitle(_ id: Int32) async throws {
        try await perform { session in
            if id == -1 && libvlc_video_get_spu(session.player) == -1 { return }
            guard libvlc_video_set_spu(session.player, id) == 0 else {
                throw PlaybackError.unavailable("无法切换字幕轨道")
            }
        }
    }

    public func setVolume(_ percent: Int32) async throws {
        try await perform { session in
            guard libvlc_audio_set_volume(session.player, max(0, min(percent, 100))) == 0 else {
                throw PlaybackError.unavailable("无法调整音量")
            }
        }
    }

    public func snapshot() async throws -> PlaybackSnapshot {
        try await perform { session in
            let player = session.player
            let state: PlaybackState
            switch libvlc_media_player_get_state(player) {
            case libvlc_Opening, libvlc_Buffering: state = .opening
            case libvlc_Playing: state = .playing
            case libvlc_Paused: state = .paused
            case libvlc_Ended: state = .ended
            case libvlc_Error: state = .failed
            default: state = .idle
            }
            var subtitles = [SubtitleTrack(id: -1, name: "字幕：关闭")]
            let first = libvlc_video_get_spu_description(player)
            defer { if let first = first { libvlc_track_description_list_release(first) } }
            var node = first
            while let track = node?.pointee {
                if track.i_id >= 0 {
                    subtitles.append(SubtitleTrack(id: track.i_id,
                        name: track.psz_name.map { String(cString: $0) } ?? "字幕 \(track.i_id)"))
                }
                node = track.p_next
            }
            var stats = libvlc_media_stats_t()
            if let media = libvlc_media_player_get_media(player) {
                _ = libvlc_media_get_stats(media, &stats)
            }
            return PlaybackSnapshot(state: state,
                seconds: max(0, Double(libvlc_media_player_get_time(player)) / 1000),
                duration: max(0, Double(libvlc_media_player_get_length(player)) / 1000),
                seekable: libvlc_media_player_is_seekable(player) != 0,
                speed: libvlc_media_player_get_rate(player), subtitles: subtitles,
                selectedSubtitle: libvlc_video_get_spu(player),
                decodedVideo: stats.i_decoded_video, displayedPictures: stats.i_displayed_pictures,
                decodedAudio: stats.i_decoded_audio, playedAudioBuffers: stats.i_played_abuffers)
        }
    }

    public func close() async {
        hasMedia = false
        guard let session = session else { return }
        self.session = nil
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            queue.async {
                libvlc_media_player_stop(session.player)
                libvlc_media_player_set_nsobject(session.player, nil)
                libvlc_media_player_release(session.player)
                libvlc_release(session.instance)
                session.clearExternalSubtitles()
                session.closed = true
                continuation.resume()
            }
        }
    }

    private func perform<T>(_ action: @escaping (VLCSession) throws -> T) async throws -> T {
        guard let session = session else { throw PlaybackError.unavailable("播放器已关闭") }
        return try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    guard !session.closed else { throw PlaybackError.unavailable("播放器已关闭") }
                    continuation.resume(returning: try action(session))
                } catch { continuation.resume(throwing: error) }
            }
        }
    }
}
