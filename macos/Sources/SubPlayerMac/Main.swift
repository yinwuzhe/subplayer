import AppKit
import PlaybackCore
import UniformTypeIdentifiers

@MainActor
final class AppController: NSObject, NSApplicationDelegate, NSWindowDelegate {
    private var window: NSWindow!
    private var player: VLCPlayer!
    private var playerView: NSView { player.view }
    private let openButton = NSButton()
    private let localRow = NSStackView()
    private let playButton = NSButton()
    private let progress = NSSlider(value: 0, minValue: 0, maxValue: 1, target: nil, action: nil)
    private let volume = NSSlider(value: 100, minValue: 0, maxValue: 100, target: nil, action: nil)
    private let timeLabel = NSTextField(labelWithString: "00:00 / 00:00")
    private var playbackTimer: Timer?
    private var polling = false
    private var busy = false
    private var quitting = false
    private var snapshot: PlaybackSnapshot?
    private var localActivity: NSObjectProtocol?
    private var pendingOpen: URL?
    private let videoContainer = NSView()
    private let emptyLabel = NSTextField(labelWithString: "打开 Mac 上的视频，直接播放或投到电视")
    private let statusLabel = NSTextField(labelWithString: "视频保存在 Mac 上即可，无需下载到手机")
    private let subtitlePicker = NSPopUpButton()
    private let loadSubtitleButton = NSButton()
    private let speedPicker = NSPopUpButton()
    private let castButton = NSButton()
    private let remoteRow = NSStackView()
    private let remoteButton = NSButton()
    private let deviceLabel = NSTextField(labelWithString: "")
    private var subtitleOptions: [SubtitleTrack] = []
    private var videoURL: URL?
    private var selectedSpeed: Float = 1
    private var searchAlert: NSAlert?
    private var scanner: DlnaScanner?
    private var devices: [DlnaDevice] = []
    private let cast = MacCastCoordinator()

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        setupMenu()
        do { player = try VLCPlayer() }
        catch {
            let alert = NSAlert()
            alert.messageText = "播放器无法启动"
            alert.informativeText = error.localizedDescription
            alert.runModal()
            NSApp.terminate(nil)
            return
        }
        buildWindow()
        cast.onStatus = { [weak self] message in
            self?.statusLabel.stringValue = message
            self?.refreshCastControls()
        }
        playbackTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in
            guard let owner = self else { return }
            Task { @MainActor in await owner.pollPlayback() }
        }
        refreshCastControls()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        if let url = pendingOpen {
            pendingOpen = nil
            openSelectedVideo(url)
        }
    }

    func application(_ application: NSApplication, open urls: [URL]) {
        guard let url = urls.first else { return }
        if window == nil { pendingOpen = url }
        else { window.makeKeyAndOrderFront(nil); openSelectedVideo(url) }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag { window.makeKeyAndOrderFront(nil) }
        return true
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard player != nil else { return .terminateNow }
        guard !quitting else { return .terminateLater }
        quitting = true
        playbackTimer?.invalidate()
        Task { @MainActor in
            if cast.isCasting { await cast.stop() }
            await player.close()
            setLocalActivity(false)
            sender.reply(toApplicationShouldTerminate: true)
        }
        return .terminateLater
    }

    func applicationWillTerminate(_ notification: Notification) {
        scanner?.cancel()
        cast.close()
    }

    private func setupMenu() {
        let menu = NSMenu()
        let appItem = NSMenuItem()
        let appMenu = NSMenu()
        appMenu.addItem(NSMenuItem(title: "退出 SubPlayer", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
        appItem.submenu = appMenu
        menu.addItem(appItem)
        let fileItem = NSMenuItem()
        let fileMenu = NSMenu(title: "文件")
        let openItem = NSMenuItem(title: "打开视频…", action: #selector(openVideo), keyEquivalent: "o")
        openItem.target = self
        fileMenu.addItem(openItem)
        fileItem.submenu = fileMenu
        menu.addItem(fileItem)
        NSApp.mainMenu = menu
    }

    private func buildWindow() {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1050, height: 720),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable],
                          backing: .buffered, defer: false)
        window.title = "SubPlayer · Mac"
        window.center()
        window.minSize = NSSize(width: 780, height: 540)
        window.delegate = self
        window.appearance = NSAppearance(named: .darkAqua)
        window.backgroundColor = NSColor(calibratedRed: 0.07, green: 0.08, blue: 0.11, alpha: 1)
        let root = NSView()
        window.contentView = root

        let title = NSTextField(labelWithString: "SubPlayer")
        title.font = .systemFont(ofSize: 24, weight: .bold)
        let subtitle = NSTextField(labelWithString: "MAC 本地播放  ·  DLNA 电视投屏")
        subtitle.font = .systemFont(ofSize: 12, weight: .medium)
        subtitle.textColor = .secondaryLabelColor
        let titleStack = NSStackView(views: [title, subtitle])
        titleStack.orientation = .vertical
        titleStack.alignment = .leading
        titleStack.spacing = 3
        let header = NSStackView(views: [titleStack, NSView()])
        header.orientation = .horizontal

        videoContainer.wantsLayer = true
        videoContainer.layer?.backgroundColor = NSColor.black.cgColor
        videoContainer.layer?.cornerRadius = 16
        videoContainer.layer?.masksToBounds = true
        playerView.translatesAutoresizingMaskIntoConstraints = false
        videoContainer.addSubview(playerView)
        emptyLabel.font = .systemFont(ofSize: 18, weight: .medium)
        emptyLabel.textColor = .secondaryLabelColor
        emptyLabel.translatesAutoresizingMaskIntoConstraints = false
        videoContainer.addSubview(emptyLabel)
        NSLayoutConstraint.activate([
            playerView.leadingAnchor.constraint(equalTo: videoContainer.leadingAnchor),
            playerView.trailingAnchor.constraint(equalTo: videoContainer.trailingAnchor),
            playerView.topAnchor.constraint(equalTo: videoContainer.topAnchor),
            playerView.bottomAnchor.constraint(equalTo: videoContainer.bottomAnchor),
            emptyLabel.centerXAnchor.constraint(equalTo: videoContainer.centerXAnchor),
            emptyLabel.centerYAnchor.constraint(equalTo: videoContainer.centerYAnchor),
            videoContainer.heightAnchor.constraint(greaterThanOrEqualToConstant: 260)
        ])

        statusLabel.textColor = .secondaryLabelColor
        statusLabel.font = .systemFont(ofSize: 12)
        statusLabel.lineBreakMode = .byTruncatingTail

        openButton.title = "打开视频"
        openButton.bezelStyle = .rounded
        openButton.image = NSImage(systemSymbolName: "folder", accessibilityDescription: "打开视频")
        openButton.target = self
        openButton.action = #selector(openVideo)
        subtitlePicker.addItem(withTitle: "字幕：无可用轨道")
        subtitlePicker.isEnabled = false
        subtitlePicker.target = self
        subtitlePicker.action = #selector(changeSubtitle)
        subtitlePicker.widthAnchor.constraint(lessThanOrEqualToConstant: 210).isActive = true
        loadSubtitleButton.title = "加载字幕…"
        loadSubtitleButton.bezelStyle = .rounded
        loadSubtitleButton.target = self
        loadSubtitleButton.action = #selector(loadExternalSubtitle)
        loadSubtitleButton.toolTip = "加载 SRT / ASS / SSA / VTT，仅用于本机播放"
        speedPicker.addItems(withTitles: ["0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×", "3×"])
        speedPicker.selectItem(at: 2)
        speedPicker.target = self
        speedPicker.action = #selector(changeSpeed)
        castButton.title = "搜索电视"
        castButton.image = NSImage(systemSymbolName: "tv", accessibilityDescription: "电视投屏")
        castButton.imagePosition = .imageLeading
        castButton.bezelStyle = .rounded
        castButton.target = self
        castButton.action = #selector(showTelevisions)
        let fullscreenButton = makeButton("全屏", icon: "arrow.up.left.and.arrow.down.right", action: #selector(toggleFullscreen))
        let controls = NSStackView(views: [openButton, loadSubtitleButton, subtitlePicker, speedPicker, NSView(), castButton, fullscreenButton])
        controls.orientation = .horizontal
        controls.alignment = .centerY
        controls.spacing = 12

        remoteButton.title = "暂停电视"
        remoteButton.target = self
        remoteButton.action = #selector(toggleRemote)
        remoteButton.bezelStyle = .rounded
        let back = makeButton("后退 30 秒", icon: "gobackward.30", action: #selector(seekBack))
        let forward = makeButton("前进 30 秒", icon: "goforward.30", action: #selector(seekForward))
        let stop = makeButton("结束投屏", icon: "stop.fill", action: #selector(stopCasting))
        remoteRow.setViews([deviceLabel, NSView(), remoteButton, back, forward, stop], in: .leading)
        remoteRow.orientation = .horizontal
        remoteRow.alignment = .centerY
        remoteRow.spacing = 10
        remoteRow.isHidden = true

        playButton.title = "播放"
        playButton.bezelStyle = .rounded
        playButton.target = self
        playButton.action = #selector(toggleLocal)
        playButton.keyEquivalent = " "
        progress.target = self
        progress.action = #selector(seekLocal)
        progress.isContinuous = false
        progress.setAccessibilityLabel("播放进度")
        volume.target = self
        volume.action = #selector(changeVolume)
        volume.setAccessibilityLabel("音量")
        volume.widthAnchor.constraint(equalToConstant: 90).isActive = true
        timeLabel.font = .monospacedDigitSystemFont(ofSize: 12, weight: .regular)
        localRow.setViews([playButton, progress, timeLabel,
                          NSTextField(labelWithString: "音量"), volume], in: .leading)
        localRow.orientation = .horizontal
        localRow.alignment = .centerY
        localRow.spacing = 12
        progress.setContentHuggingPriority(.defaultLow, for: .horizontal)
        let stack = NSStackView(views: [header, videoContainer, localRow, statusLabel, remoteRow, controls])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 15
        stack.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 22),
            stack.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -22),
            stack.topAnchor.constraint(equalTo: root.topAnchor, constant: 18),
            stack.bottomAnchor.constraint(equalTo: root.bottomAnchor, constant: -20),
            videoContainer.widthAnchor.constraint(equalTo: stack.widthAnchor),
            header.widthAnchor.constraint(equalTo: stack.widthAnchor),
            controls.widthAnchor.constraint(equalTo: stack.widthAnchor),
            remoteRow.widthAnchor.constraint(equalTo: stack.widthAnchor),
            localRow.widthAnchor.constraint(equalTo: stack.widthAnchor),
            statusLabel.widthAnchor.constraint(equalTo: stack.widthAnchor)
        ])
        videoContainer.setContentHuggingPriority(.defaultLow, for: .vertical)
        videoContainer.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
    }

    private func makeButton(_ title: String, icon: String, action: Selector) -> NSButton {
        let button = NSButton(title: title, target: self, action: action)
        button.bezelStyle = .rounded
        button.image = NSImage(systemSymbolName: icon, accessibilityDescription: title)
        button.imagePosition = .imageLeading
        return button
    }

    @objc private func openVideo() {
        guard !busy, !quitting else { return }
        let panel = NSOpenPanel()
        panel.title = "打开视频（支持 MKV、MP4、MOV 等）"
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.beginSheetModal(for: window) { [weak self] result in
            guard result == .OK, let url = panel.url else { return }
            self?.openSelectedVideo(url)
        }
    }

    private func openSelectedVideo(_ url: URL) {
        guard !busy, !quitting else { return }
        busy = true
        refreshCastControls()
        Task { @MainActor in
            defer { busy = false; refreshCastControls() }
            if cast.isCasting { await cast.stop() }
            videoURL = url
            snapshot = nil
            subtitleOptions = []
            subtitlePicker.removeAllItems()
            subtitlePicker.addItem(withTitle: "正在读取字幕…")
            selectedSpeed = 1
            speedPicker.selectItem(at: 2)
            progress.doubleValue = 0
            timeLabel.stringValue = "00:00 / 00:00"
            emptyLabel.isHidden = true
            statusLabel.stringValue = "正在打开 \(url.lastPathComponent)…"
            do {
                try await player.open(url)
                window.title = "\(url.lastPathComponent) · SubPlayer"
            } catch {
                statusLabel.stringValue = "无法播放：\(error.localizedDescription)"
                setLocalActivity(false)
            }
        }
    }

    private func pollPlayback() async {
        guard !polling, !busy, !quitting, player.hasMedia else { return }
        polling = true
        defer { polling = false }
        guard let current = try? await player.snapshot(), !busy, !quitting else { return }
        let previousState = snapshot?.state
        snapshot = current
        playButton.title = current.state == .playing ? "暂停" : "播放"
        timeLabel.stringValue = "\(formatTime(current.seconds)) / \(formatTime(current.duration))"
        progress.maxValue = max(1, current.duration)
        if NSApp.currentEvent?.type != .leftMouseDragged { progress.doubleValue = current.seconds }
        if subtitleOptions != current.subtitles {
            subtitleOptions = current.subtitles
            subtitlePicker.removeAllItems()
            subtitlePicker.addItems(withTitles: subtitleOptions.map(\.name))
        }
        if let selected = subtitleOptions.firstIndex(where: { $0.id == current.selectedSubtitle }) {
            subtitlePicker.selectItem(at: selected)
        }
        if current.state != previousState && !cast.isCasting {
            switch current.state {
            case .failed: statusLabel.stringValue = "解码失败，请确认文件已完整下载且未加密"
            case .playing: statusLabel.stringValue = "本机播放 · \(videoURL?.lastPathComponent ?? "")"
            case .ended: statusLabel.stringValue = "播放结束"
            default: break
            }
        }
        setLocalActivity(current.state == .playing && !cast.isCasting)
        refreshCastControls()
    }

    private func setLocalActivity(_ active: Bool) {
        if active && localActivity == nil {
            localActivity = ProcessInfo.processInfo.beginActivity(
                options: [.userInitiated, .idleDisplaySleepDisabled], reason: "SubPlayer 正在播放视频")
        } else if !active, let activity = localActivity {
            ProcessInfo.processInfo.endActivity(activity)
            localActivity = nil
        }
    }

    private func formatTime(_ seconds: Double) -> String {
        let value = Int(max(0, seconds))
        return value >= 3600 ? String(format: "%d:%02d:%02d", value / 3600, value / 60 % 60, value % 60)
            : String(format: "%02d:%02d", value / 60, value % 60)
    }

    private func localCommand(_ action: @escaping () async throws -> Void) {
        guard !busy, !quitting, !cast.isCasting, player.hasMedia else { return }
        Task { @MainActor in
            do { try await action() }
            catch { statusLabel.stringValue = error.localizedDescription }
        }
    }

    @objc private func toggleLocal() {
        let playing = snapshot?.state == .playing
        localCommand { if playing { try await self.player.pause() } else { try await self.player.play() } }
    }

    @objc private func seekLocal() {
        let target = progress.doubleValue
        localCommand { try await self.player.seek(to: target) }
    }

    @objc private func changeVolume() {
        let target = Int32(volume.intValue)
        localCommand { try await self.player.setVolume(target) }
    }

    @objc private func loadExternalSubtitle() {
        guard !busy, !quitting, !cast.isCasting, player.hasMedia else { return }
        let video = videoURL
        let panel = NSOpenPanel()
        panel.title = "加载外部字幕"
        panel.message = "支持 SRT、ASS、SSA、VTT；外挂字幕仅在本机显示。"
        panel.allowedContentTypes = ExternalSubtitle.extensions.compactMap { UTType(filenameExtension: $0) }
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.directoryURL = videoURL?.deletingLastPathComponent()
        panel.beginSheetModal(for: window) { [weak self] response in
            guard let self = self, response == .OK, let url = panel.url,
                  self.videoURL == video, !self.busy, !self.quitting, !self.cast.isCasting else { return }
            self.busy = true
            self.refreshCastControls()
            self.statusLabel.stringValue = "正在加载字幕：\(url.lastPathComponent)…"
            Task { @MainActor in
                do {
                    try await self.player.loadSubtitle(url)
                    self.statusLabel.stringValue = "已加载字幕：\(url.lastPathComponent)"
                } catch {
                    self.statusLabel.stringValue = "字幕加载失败：\(error.localizedDescription)"
                }
                self.busy = false
                await self.pollPlayback()
                self.refreshCastControls()
            }
        }
    }

    @objc private func changeSubtitle() {
        guard subtitleOptions.indices.contains(subtitlePicker.indexOfSelectedItem) else { return }
        let track = subtitleOptions[subtitlePicker.indexOfSelectedItem]
        localCommand { try await self.player.selectSubtitle(track.id) }
    }

    @objc private func changeSpeed() {
        let speeds: [Float] = [0.5, 0.75, 1, 1.25, 1.5, 2, 3]
        selectedSpeed = speeds[speedPicker.indexOfSelectedItem]
        localCommand { try await self.player.setSpeed(self.selectedSpeed) }
    }

    @objc private func toggleFullscreen() { window.toggleFullScreen(nil) }

    @objc private func showTelevisions() {
        guard !busy, !quitting else { return }
        guard videoURL != nil else {
            statusLabel.stringValue = "请先打开要投屏的视频"
            return
        }
        scanner?.cancel()
        devices.removeAll()
        let alert = NSAlert()
        alert.messageText = "选择电视（DLNA）"
        alert.informativeText = "Mac 和电视需连接同一 Wi-Fi；视频将从 Mac 直接传给电视。"
        alert.addButton(withTitle: "投屏")
        alert.addButton(withTitle: "取消")
        let picker = NSPopUpButton(frame: NSRect(x: 0, y: 0, width: 330, height: 30))
        picker.addItem(withTitle: "正在搜索电视…")
        alert.accessoryView = picker
        searchAlert = alert
        let search = DlnaScanner()
        scanner = search
        search.start(onDevice: { [weak self, weak alert] device in
            guard let self = self, self.searchAlert === alert else { return }
            if self.devices.isEmpty { picker.removeAllItems() }
            self.devices.append(device)
            picker.addItem(withTitle: device.name)
        }, onComplete: { [weak self, weak alert] error in
            guard let self = self, self.searchAlert === alert else { return }
            if self.devices.isEmpty {
                picker.removeAllItems()
                picker.addItem(withTitle: error ?? "未找到 DLNA 电视，请检查电视的投屏设置")
            }
        })
        alert.beginSheetModal(for: window) { [weak self, weak alert] response in
            guard let self = self, self.searchAlert === alert else { return }
            self.scanner?.cancel()
            self.scanner = nil
            self.searchAlert = nil
            guard response == .alertFirstButtonReturn,
                  self.devices.indices.contains(picker.indexOfSelectedItem),
                  let url = self.videoURL else { return }
            let device = self.devices[picker.indexOfSelectedItem]
            let resumeLocal = self.snapshot?.state == .playing
            self.busy = true
            self.refreshCastControls()
            self.statusLabel.stringValue = "正在连接 \(device.name)…"
            Task { @MainActor in
                defer { self.busy = false; self.refreshCastControls() }
                if self.cast.isCasting { await self.cast.stop() }
                do {
                    try await self.player.pause()
                    self.setLocalActivity(false)
                    try await self.cast.start(video: url, on: device)
                } catch {
                    self.statusLabel.stringValue = "投屏失败：\(error.localizedDescription)"
                    if resumeLocal { try? await self.player.play() }
                }
            }
        }
    }

    private func refreshCastControls() {
        let active = cast.isCasting
        remoteRow.isHidden = !active
        deviceLabel.stringValue = active ? "正在投屏到 \(cast.deviceName)" : ""
        remoteButton.title = cast.isPlaying ? "暂停电视" : "继续电视播放"
        castButton.title = active ? "更换电视" : "搜索电视"
        let localEnabled = player.hasMedia && !active && !busy && !quitting
        localRow.isHidden = active
        openButton.isEnabled = !busy && !quitting
        castButton.isEnabled = videoURL != nil && !busy && !quitting
        subtitlePicker.isEnabled = subtitleOptions.count > 1 && localEnabled
        loadSubtitleButton.isEnabled = localEnabled && (snapshot?.state == .playing || snapshot?.state == .paused)
        speedPicker.isEnabled = localEnabled
        playButton.isEnabled = localEnabled
        progress.isEnabled = localEnabled && snapshot?.seekable == true
        volume.isEnabled = localEnabled
        for case let button as NSButton in remoteRow.arrangedSubviews {
            button.isEnabled = !busy && !quitting
        }
    }

    @objc private func toggleRemote() {
        Task { @MainActor in
            do { try await cast.togglePlayback() }
            catch { statusLabel.stringValue = "电视控制失败：\(error.localizedDescription)" }
            refreshCastControls()
        }
    }

    @objc private func seekBack() { seekRemote(-30) }
    @objc private func seekForward() { seekRemote(30) }

    private func seekRemote(_ seconds: Int) {
        Task { @MainActor in
            do { try await cast.seek(seconds: seconds) }
            catch { statusLabel.stringValue = "电视快进失败：\(error.localizedDescription)" }
        }
    }

    @objc private func stopCasting() {
        Task { @MainActor in
            await cast.stop()
            refreshCastControls()
        }
    }
}

@main
struct SubPlayerMac {
    @MainActor static func main() {
        let app = NSApplication.shared
        let delegate = AppController()
        app.delegate = delegate
        app.run()
    }
}
