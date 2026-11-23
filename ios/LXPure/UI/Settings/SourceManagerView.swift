import SwiftUI
import UniformTypeIdentifiers

struct SourceManagerView: View {
    @ObservedObject var userApiManager: UserApiManager
    let bottomInset: CGFloat
    let onMessage: (String) -> Void

    @Environment(\.lxPalette) private var lx
    @State private var showUrlSheet = false
    @State private var urlInput = ""
    @State private var showPasteSheet = false
    @State private var pasteInput = ""
    @State private var showJsFilePicker = false
    @State private var isImporting = false
    @State private var isUpdatingAll = false
    @State private var testingId: String?
    @State private var updatingId: String?

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 14) {
                // 3 Quick Import Methods
                HStack(spacing: 10) {
                    importTile(icon: "link", title: "在线链接", subtitle: "粘贴 .js URL") {
                        urlInput = ""
                        showUrlSheet = true
                    }
                    importTile(icon: "folder", title: "本地文件", subtitle: "选择 .js 文件") {
                        showJsFilePicker = true
                    }
                    importTile(icon: "chevron.left.forwardslash.chevron.right", title: "粘贴脚本", subtitle: "源码导入") {
                        pasteInput = ""
                        showPasteSheet = true
                    }
                }

                if userApiManager.sources.isEmpty {
                    GlassCard(cornerRadius: 18) {
                        VStack(spacing: 8) {
                            Text("尚未添加任何音源")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(lx.textPrimary)
                            Text("轻雪音乐本身不内置也不提供任何音源脚本，请通过上方三种方式自行导入合规的洛雪协议 .js 自定义源。支持同时启用多个源并按优先级自动切换兜底。")
                                .font(.system(size: 12))
                                .foregroundColor(lx.textSecondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 28)
                    }
                } else {
                    ForEach(Array(userApiManager.sources.enumerated()), id: \.element.id) { index, source in
                        sourceCard(source: source, isTopPriority: index == 0)
                    }
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 10)
            .padding(.bottom, bottomInset)
        }
        .background(lx.background)
        .navigationTitle("自定义音源管理")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                if isImporting || isUpdatingAll {
                    ProgressView().tint(lx.accent)
                } else if !userApiManager.sources.isEmpty {
                    Button {
                        Task {
                            isUpdatingAll = true
                            let summary = await userApiManager.updateAllSources()
                            isUpdatingAll = false
                            onMessage(summary.formatUserMessage())
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.clockwise")
                            Text("更新音源")
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(lx.accent)
                    }
                }
            }
        }
        .fileImporter(
            isPresented: $showJsFilePicker,
            allowedContentTypes: [.javaScript, .plainText, .data],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                Task {
                    isImporting = true
                    let accessed = url.startAccessingSecurityScopedResource()
                    defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                    if let content = try? String(contentsOf: url, encoding: .utf8) {
                        let res = await userApiManager.importRawScript(
                            content,
                            fallbackName: url.deletingPathExtension().lastPathComponent
                        )
                        switch res {
                        case .success(let src): onMessage("已导入音源：\(src.name)")
                        case .failure(let err): onMessage("导入失败：\(err.localizedDescription)")
                        }
                    } else {
                        onMessage("无法读取该文件（请确认 UTF-8 编码）")
                    }
                    isImporting = false
                }
            case .failure(let err):
                onMessage("文件选择失败：\(err.localizedDescription)")
            }
        }
        .sheet(isPresented: $showUrlSheet) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 12) {
                    Text("请输入有效的 http:// 或 https:// 洛雪协议 .js 脚本直链。")
                        .font(.system(size: 13))
                        .foregroundColor(lx.textSecondary)

                    TextField("https://example.com/source.js", text: $urlInput)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .padding(12)
                        .background(lx.surfaceElevated)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(lx.hairline, lineWidth: 1))

                    Spacer()
                }
                .padding(18)
                .background(lx.background)
                .navigationTitle("在线链接导入")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { showUrlSheet = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("导入") {
                            let targetUrl = urlInput.trimmingCharacters(in: .whitespacesAndNewlines)
                            showUrlSheet = false
                            Task {
                                isImporting = true
                                let res = await userApiManager.importFromUrl(targetUrl)
                                isImporting = false
                                switch res {
                                case .success(let src): onMessage("已导入音源：\(src.name)")
                                case .failure(let err): onMessage("导入失败：\(err.localizedDescription)")
                                }
                            }
                        }
                        .disabled(urlInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
            .presentationDetents([.height(260)])
        }
        .sheet(isPresented: $showPasteSheet) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 12) {
                    Text("将完整的洛雪 .js 自定义源脚本源码粘贴至下方。")
                        .font(.system(size: 13))
                        .foregroundColor(lx.textSecondary)

                    TextEditor(text: $pasteInput)
                        .font(.system(size: 12, design: .monospaced))
                        .padding(8)
                        .background(lx.surfaceElevated)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(lx.hairline, lineWidth: 1))
                }
                .padding(18)
                .background(lx.background)
                .navigationTitle("粘贴脚本源码")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { showPasteSheet = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("导入") {
                            let code = pasteInput
                            showPasteSheet = false
                            Task {
                                isImporting = true
                                let res = await userApiManager.importRawScript(code)
                                isImporting = false
                                switch res {
                                case .success(let src): onMessage("已导入音源：\(src.name)")
                                case .failure(let err): onMessage("导入失败：\(err.localizedDescription)")
                                }
                            }
                        }
                        .disabled(pasteInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
        }
    }

    private func importTile(icon: String, title: String, subtitle: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            GlassCard(cornerRadius: 16) {
                VStack(spacing: 6) {
                    Image(systemName: icon)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(lx.accent)
                    Text(title)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(lx.textPrimary)
                    Text(subtitle)
                        .font(.system(size: 10))
                        .foregroundColor(lx.textTertiary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
            }
        }
        .buttonStyle(.plain)
    }

    private func sourceCard(source: UserApiSource, isTopPriority: Bool) -> some View {
        let statusColor: Color = {
            switch source.status {
            case .ready: return lx.success
            case .initializing: return lx.warning
            case .error: return lx.danger
            case .idle: return lx.textTertiary
            }
        }()

        let statusText: String = {
            switch source.status {
            case .ready:
                if let ms = source.latencyMs { return "就绪 · \(ms)ms" }
                return "就绪"
            case .initializing: return "初始化中..."
            case .error: return source.errorMessage ?? "异常"
            case .idle: return "已停用"
            }
        }()

        return GlassCard(cornerRadius: 18) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center, spacing: 10) {
                    Circle()
                        .fill(statusColor)
                        .frame(width: 9, height: 9)

                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(source.name)
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(lx.textPrimary)
                                .lineLimit(1)
                            SmallPillBadge(
                                text: LxScriptParser.formatVersionBadge(source.version),
                                color: lx.accent,
                                background: lx.accentSoft
                            )
                        }
                        Text("\(source.author) · \(statusText)")
                            .font(.system(size: 12))
                            .foregroundColor(source.status == .error ? lx.danger : lx.textSecondary)
                            .lineLimit(2)
                    }

                    Spacer()

                    Toggle("", isOn: Binding(
                        get: { source.enabled },
                        set: { enabled in
                            Task { await userApiManager.toggleEnabled(id: source.id, enabled: enabled) }
                        }
                    ))
                    .labelsHidden()
                    .tint(lx.accent)
                }

                if !source.supportedPlatforms.isEmpty {
                    HStack(spacing: 6) {
                        ForEach(source.supportedPlatforms.keys.sorted(), id: \.self) { code in
                            SmallPillBadge(
                                text: MusicSource.fromCode(code).shortLabel,
                                color: lx.textSecondary,
                                background: lx.surfaceElevated
                            )
                        }
                    }
                }

                Divider().overlay(lx.hairline)

                HStack(spacing: 14) {
                    if !isTopPriority {
                        actionButton(icon: "arrow.up", title: "优先") {
                            Task { await userApiManager.movePriorityUp(id: source.id) }
                        }
                    }

                    actionButton(
                        icon: "speedometer",
                        title: testingId == source.id ? "测速中..." : "测速",
                        disabled: testingId == source.id
                    ) {
                        Task {
                            testingId = source.id
                            let res = await userApiManager.testSourceHealth(id: source.id)
                            testingId = nil
                            switch res {
                            case .success(let ms): onMessage("\(source.name) 解析可用 (\(ms)ms)")
                            case .failure(let err): onMessage("\(source.name) 测试失败：\(err.localizedDescription)")
                            }
                        }
                    }

                    if !SourceUrlUtils.buildUpdateCandidateUrls(source.sourceUrl).isEmpty {
                        actionButton(
                            icon: "arrow.clockwise",
                            title: updatingId == source.id ? "检查中..." : "更新",
                            disabled: updatingId == source.id
                        ) {
                            Task {
                                updatingId = source.id
                                let res = await userApiManager.updateSingleSource(id: source.id)
                                updatingId = nil
                                if res.updated {
                                    onMessage("\(res.sourceName) 已更新至 \(LxScriptParser.formatVersionBadge(res.newVersion))")
                                } else if res.alreadyLatest {
                                    onMessage("\(res.sourceName) 已是最新版本")
                                } else {
                                    onMessage(res.errorMessage ?? "更新失败")
                                }
                            }
                        }
                    }

                    Spacer()

                    Button(role: .destructive) {
                        Task {
                            await userApiManager.removeSource(id: source.id)
                            onMessage("已移除音源：\(source.name)")
                        }
                    } label: {
                        Image(systemName: "trash")
                            .font(.system(size: 14))
                            .foregroundColor(lx.danger.opacity(0.85))
                            .frame(width: 30, height: 30)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(14)
        }
    }

    private func actionButton(icon: String, title: String, disabled: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: icon)
                Text(title)
            }
            .font(.system(size: 12, weight: .medium))
            .foregroundColor(lx.textSecondary)
        }
        .buttonStyle(.plain)
        .disabled(disabled)
    }
}
