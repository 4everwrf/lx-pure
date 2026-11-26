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
    @State private var testingIds: Set<String> = []
    @State private var updatingIds: Set<String> = []
    @State private var pendingDeleteSource: UserApiSource?

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 14) {
                // 3 Quick Import Methods
                HStack(spacing: 10) {
                    importTile(icon: "link", title: "在线链接", subtitle: "粘贴 .js URL", disabled: isImporting) {
                        urlInput = ""
                        showUrlSheet = true
                    }
                    importTile(icon: "folder", title: "本地文件", subtitle: "选择 .js 文件", disabled: isImporting) {
                        showJsFilePicker = true
                    }
                    importTile(icon: "chevron.left.forwardslash.chevron.right", title: "粘贴脚本", subtitle: "源码导入", disabled: isImporting) {
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
                        guard !isUpdatingAll && !isImporting && testingIds.isEmpty && updatingIds.isEmpty else { return }
                        isUpdatingAll = true
                        Task {
                            defer { isUpdatingAll = false }
                            let summary = await userApiManager.updateAllSources()
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
                    .disabled(!testingIds.isEmpty || !updatingIds.isEmpty)
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
                guard let url = urls.first, !isImporting else { return }
                isImporting = true
                Task {
                    defer { isImporting = false }
                    let fileReadResult: Result<String, Error> = await Task.detached(priority: .userInitiated) {
                        let accessed = url.startAccessingSecurityScopedResource()
                        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                        let values = try? url.resourceValues(forKeys: [.fileSizeKey])
                        if let size = values?.fileSize, size > 1_048_576 {
                            return .failure(NSError(
                                domain: "SourceImport",
                                code: -1,
                                userInfo: [NSLocalizedDescriptionKey: "脚本文件过大（超过 1 MB），请确认选择的是 .js 音源脚本"]
                            ))
                        }
                        do {
                            let data = try Data(contentsOf: url, options: .mappedIfSafe)
                            if data.count > 1_048_576 {
                                return .failure(NSError(
                                    domain: "SourceImport",
                                    code: -1,
                                    userInfo: [NSLocalizedDescriptionKey: "脚本文件过大（超过 1 MB），请确认选择的是 .js 音源脚本"]
                                ))
                            }
                            if data.contains(0) {
                                return .failure(NSError(
                                    domain: "SourceImport",
                                    code: -2,
                                    userInfo: [NSLocalizedDescriptionKey: "文件包含非文本二进制内容，请确认选择的是 UTF-8 编码 .js 脚本"]
                                ))
                            }
                            guard let text = String(data: data, encoding: .utf8) else {
                                return .failure(NSError(
                                    domain: "SourceImport",
                                    code: -3,
                                    userInfo: [NSLocalizedDescriptionKey: "无法读取该文件（请确认 UTF-8 编码）"]
                                ))
                            }
                            return .success(text)
                        } catch {
                            return .failure(error)
                        }
                    }.value
                    switch fileReadResult {
                    case .success(let content):
                        let res = await userApiManager.importRawScript(
                            content,
                            fallbackName: url.deletingPathExtension().lastPathComponent
                        )
                        switch res {
                        case .success(let src): onMessage("已导入音源：\(src.name)")
                        case .failure(let err): onMessage("导入失败：\(err.localizedDescription)")
                        }
                    case .failure(let err):
                        onMessage(err.localizedDescription)
                    }
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
                        .foregroundColor(lx.textPrimary)
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
                            guard !isImporting else { return }
                            let targetUrl = urlInput.trimmingCharacters(in: .whitespacesAndNewlines)
                            guard !targetUrl.isEmpty else { return }
                            showUrlSheet = false
                            isImporting = true
                            Task {
                                defer { isImporting = false }
                                let res = await userApiManager.importFromUrl(targetUrl)
                                switch res {
                                case .success(let src): onMessage("已导入音源：\(src.name)")
                                case .failure(let err): onMessage("导入失败：\(err.localizedDescription)")
                                }
                            }
                        }
                        .disabled(isImporting || urlInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
            .preferredColorScheme(lx.isDark ? .dark : .light)
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
                        .foregroundColor(lx.textPrimary)
                        .scrollContentBackground(.hidden)
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
                            guard !isImporting else { return }
                            let code = pasteInput
                            guard !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                            showPasteSheet = false
                            isImporting = true
                            Task {
                                defer { isImporting = false }
                                let res = await userApiManager.importRawScript(code)
                                switch res {
                                case .success(let src): onMessage("已导入音源：\(src.name)")
                                case .failure(let err): onMessage("导入失败：\(err.localizedDescription)")
                                }
                            }
                        }
                        .disabled(isImporting || pasteInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
            .preferredColorScheme(lx.isDark ? .dark : .light)
        }
        .alert(
            "删除音源",
            isPresented: Binding(
                get: { pendingDeleteSource != nil },
                set: { if !$0 { pendingDeleteSource = nil } }
            ),
            presenting: pendingDeleteSource
        ) { target in
            Button("取消", role: .cancel) {
                pendingDeleteSource = nil
            }
            Button("删除", role: .destructive) {
                pendingDeleteSource = nil
                Task {
                    await userApiManager.removeSource(id: target.id)
                    onMessage("已移除音源：\(target.name)")
                }
            }
        } message: { target in
            Text("确定要移除自定义音源「\(target.name)」吗？移除后需重新导入。")
        }
    }

    private func importTile(icon: String, title: String, subtitle: String, disabled: Bool = false, action: @escaping () -> Void) -> some View {
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
        .disabled(disabled)
        .opacity(disabled ? 0.55 : 1)
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
                if let err = source.errorMessage, !err.isEmpty { return err }
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
                    let sortedCodes = source.supportedPlatforms.keys.sorted()
                    LazyVGrid(
                        columns: [GridItem(.adaptive(minimum: 42), spacing: 6)],
                        alignment: .leading,
                        spacing: 6
                    ) {
                        ForEach(sortedCodes, id: \.self) { code in
                            let label = MusicSource(rawValue: code.lowercased())?.shortLabel ?? code.uppercased()
                            SmallPillBadge(
                                text: label,
                                color: lx.textSecondary,
                                background: lx.hairline
                            )
                        }
                    }
                }

                Divider().overlay(lx.hairline)

                let isTesting = testingIds.contains(source.id)
                let isUpdating = updatingIds.contains(source.id)

                HStack(spacing: 14) {
                    if !isTopPriority {
                        actionButton(icon: "arrow.up", title: "优先", disabled: isUpdatingAll) {
                            Task { await userApiManager.movePriorityUp(id: source.id) }
                        }
                    }

                    actionButton(
                        icon: "speedometer",
                        title: isTesting ? "测速中..." : "测速",
                        disabled: !source.enabled || isTesting || isUpdating || isUpdatingAll
                    ) {
                        guard !testingIds.contains(source.id) && !updatingIds.contains(source.id) else { return }
                        testingIds.insert(source.id)
                        Task {
                            defer { testingIds.remove(source.id) }
                            let res = await userApiManager.testSourceHealth(id: source.id)
                            switch res {
                            case .success(let ms): onMessage("\(source.name) 解析可用 (\(ms)ms)")
                            case .failure(let err): onMessage("\(source.name) 测试失败：\(err.localizedDescription)")
                            }
                        }
                    }

                    if !SourceUrlUtils.buildUpdateCandidateUrls(source.sourceUrl).isEmpty {
                        actionButton(
                            icon: "arrow.clockwise",
                            title: isUpdating ? "检查中..." : "更新",
                            disabled: isUpdating || isTesting || isUpdatingAll
                        ) {
                            guard !updatingIds.contains(source.id) && !testingIds.contains(source.id) else { return }
                            updatingIds.insert(source.id)
                            Task {
                                defer { updatingIds.remove(source.id) }
                                let res = await userApiManager.updateSingleSource(id: source.id)
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
                        pendingDeleteSource = source
                    } label: {
                        Image(systemName: "trash")
                            .font(.system(size: 14))
                            .foregroundColor(lx.danger.opacity(0.85))
                            .frame(width: 30, height: 30)
                    }
                    .buttonStyle(.plain)
                    .disabled(isUpdatingAll)
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
