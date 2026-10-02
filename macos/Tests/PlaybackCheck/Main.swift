import AppKit
import PlaybackCore

@MainActor
final class PlaybackCheck: NSObject, NSApplicationDelegate {
    private var window: NSWindow!
    private var player: VLCPlayer?

    func applicationDidFinishLaunching(_ notification: Notification) {
        DispatchQueue.global().asyncAfter(deadline: .now() + 75) {
            fputs("FAIL: playback check timed out\n", stderr)
            exit(2)
        }
        Task { @MainActor in
            do {
                guard CommandLine.arguments.count >= 2 else {
                    throw PlaybackError.unavailable("Usage: PlaybackCheck /absolute/path/to/video")
                }
                let url = URL(fileURLWithPath: CommandLine.arguments[1])
                let player = try VLCPlayer()
                self.player = player
                window = NSWindow(contentRect: NSRect(x: 40, y: 40, width: 640, height: 360),
                                  styleMask: [.titled], backing: .buffered, defer: false)
                window.title = "SubPlayer 播放兼容性检查"
                window.contentView = player.view
                window.orderFront(nil)
                try await player.setVolume(0)
                try await player.open(url)
                let initial = try await waitFor(player) {
                    $0.state == .playing && $0.displayedPictures > 5 && $0.decodedAudio > 0
                        && $0.playedAudioBuffers > 0 && $0.duration > 3 && $0.seconds > 1
                }
                print("PASS: decoded_video=\(initial.decodedVideo) displayed=\(initial.displayedPictures) decoded_audio=\(initial.decodedAudio) played_audio=\(initial.playedAudioBuffers) duration=\(initial.duration) subtitles=\(initial.subtitles.count - 1)")
                try await player.pause()
                let paused = try await waitFor(player) { $0.state == .paused }
                try await Task.sleep(nanoseconds: 600_000_000)
                let still = try await player.snapshot()
                guard abs(still.seconds - paused.seconds) < 0.5 else {
                    throw PlaybackError.unavailable("Pause did not hold playback position")
                }
                print("PASS: pause")
                try await player.setSpeed(1.5)
                try await player.play()
                _ = try await waitFor(player) { $0.state == .playing && abs($0.speed - 1.5) < 0.01 }
                print("PASS: resume preserves 1.5x speed")
                let target = min(30, initial.duration / 2)
                try await player.seek(to: target)
                _ = try await waitFor(player) { abs($0.seconds - target) < 4 }
                print("PASS: seek to \(target)")
                for track in initial.subtitles {
                    try await player.selectSubtitle(track.id)
                    _ = try await waitFor(player) { $0.selectedSubtitle == track.id }
                    print("PASS: subtitle \(track.id) \(track.name)")
                }
                try await player.selectSubtitle(-1)
                try await player.open(url)
                _ = try await waitFor(player) {
                    $0.state == .playing && $0.displayedPictures > 5 && $0.speed == 1
                }
                print("PASS: reopen resets playback and keeps renderer functional")
                do {
                    try await player.open(URL(fileURLWithPath: "/nonexistent-subplayer-check.mkv"))
                    throw PlaybackError.unavailable("Missing file was accepted")
                } catch PlaybackError.unavailable(let message) where message == "文件不存在或没有读取权限" {
                    print("PASS: missing file error")
                }
                await player.close()
                window.orderOut(nil)
                print("PASS: player closed cleanly")
                exit(0)
            } catch {
                if let player = player { await player.close() }
                fputs("FAIL: \(error.localizedDescription)\n", stderr)
                exit(1)
            }
        }
    }

    private func waitFor(_ player: VLCPlayer,
                         predicate: (PlaybackSnapshot) -> Bool) async throws -> PlaybackSnapshot {
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            let state = try await player.snapshot()
            if state.state == .failed { throw PlaybackError.unavailable("Decoder entered error state") }
            if predicate(state) { return state }
            try await Task.sleep(nanoseconds: 200_000_000)
        }
        throw PlaybackError.unavailable("Expected playback state not reached within 20 seconds")
    }
}

@main
struct Main {
    @MainActor static func main() {
        let app = NSApplication.shared
        let delegate = PlaybackCheck()
        app.setActivationPolicy(.accessory)
        app.delegate = delegate
        app.run()
    }
}
