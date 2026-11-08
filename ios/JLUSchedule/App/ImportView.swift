import SwiftUI
import WebKit

// MARK: - 网页导入（WKWebView + XHR/fetch 钩子捕获课表响应）

struct ImportView: View {
    let palette: ThemePalette
    @Environment(\.dismiss) private var dismiss
    @State private var coordinator = ImportCoordinator()
    @State private var showModeDialog = false
    @State private var newProfileName = ""
    @State private var showNewProfileField = false
    @State private var resultMessage: String?
    @State private var dismissAfterDone = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                addressBar
                ImportWebView(coordinator: coordinator)
                captureBar
            }
            .background(palette.pageBackground)
            .navigationTitle("课表导入")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
            .overlay(alignment: .bottomTrailing) {
                Button {
                    if coordinator.hasCapturedSchedule {
                        showModeDialog = true
                    } else {
                        resultMessage = "未找到可解析的课表数据，请在网页中打开课表详情后重试。"
                    }
                } label: {
                    HStack {
                        Image(systemName: "tray.and.down")
                        Text("从此处导入")
                    }
                    .font(.footnote.weight(.medium))
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(Capsule().fill(palette.buttonBackground))
                    .foregroundColor(palette.buttonText)
                }
                .padding(16)
            }
            .overlay(alignment: .bottom) {
                if let message = resultMessage {
                    VStack(spacing: 12) {
                        Text(message)
                            .font(.footnote)
                            .foregroundColor(palette.textPrimary)
                            .multilineTextAlignment(.center)
                        Button(dismissAfterDone ? "返回课表" : "知道了") {
                            resultMessage = nil
                            if dismissAfterDone {
                                StoreChangeNotifier.shared.notifyChanged()
                                dismiss()
                            }
                            dismissAfterDone = false
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding(16)
                    .background(RoundedRectangle(cornerRadius: 14).fill(palette.panelAltBackground))
                    .padding(24)
                }
            }
            .confirmationDialog("导入方式", isPresented: $showModeDialog, titleVisibility: .visible) {
                Button("覆盖当前课表") { startImport(mode: .overwriteActive, name: nil) }
                Button("新建课表") { showNewProfileField = true }
                Button("取消", role: .cancel) {}
            }
            .alert("新建课表", isPresented: $showNewProfileField) {
                TextField("新课表名称（可留空）", text: $newProfileName)
                Button("开始导入") {
                    startImport(mode: .createNew, name: newProfileName)
                    newProfileName = ""
                }
                Button("取消", role: .cancel) {}
            }
            .onAppear { coordinator.loadStartURL() }
        }
    }

    private var addressBar: some View {
        HStack(spacing: 0) {
            TextField("输入网址", text: $coordinator.addressText)
                .textFieldStyle(.plain)
                .font(.footnote)
                .keyboardType(.URL)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .padding(10)
            Button("前往") { coordinator.go() }
                .font(.footnote)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 6).fill(palette.buttonBackground))
                .foregroundColor(palette.buttonText)
                .padding(4)
        }
        .background(palette.panelBackground)
    }

    private var captureBar: some View {
        Text(coordinator.captureStatus)
            .font(.caption2)
            .foregroundColor(palette.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(palette.panelBackground)
    }

    private func startImport(mode: ProfileStore.ImportMode, name: String?) {
        let courses = coordinator.parsedCourses()
        guard !courses.isEmpty else {
            resultMessage = "未找到可解析的课表数据，请在网页中打开课表详情后重试。"
            return
        }
        do {
            let result = ProfileStore.shared.importParsed(
                courses, mode: mode,
                newProfileName: name,
                semesterStartDate: coordinator.parseResult?.inferredSemesterStartDate)
            var message = "已从缓存导入 \(result.courseCount) 条课程，当前课表：\(result.profileName)"
            if let semester = coordinator.parseResult?.selectedSemester, !semester.isEmpty {
                message += "\n学期：\(semester)"
            }
            if let start = result.semesterStartDate {
                message += "\n第一周起始日期：\(start.isoString)"
            }
            resultMessage = message
            dismissAfterDone = true
            coordinator.clearCaptures()
        } catch {
            resultMessage = "写入课表失败：\(error.localizedDescription)"
        }
    }
}

// MARK: - WebView 容器与捕获协调器

struct ImportWebView: UIViewRepresentable {
    @ObservedObject var coordinator: ImportCoordinator

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        let userController = WKUserContentController()
        userController.add(context.coordinator, name: "jluCache")
        userController.addUserScript(WKUserScript(
            source: ImportCoordinator.hookScript,
            injectionTime: .atDocumentEnd,
            forMainFrameOnly: false))
        config.userContentController = userController
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        coordinator.attach(webView)
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    func makeCoordinator() -> ImportCoordinator { coordinator }
}

@MainActor
final class ImportCoordinator: NSObject, ObservableObject, WKNavigationDelegate, WKScriptMessageHandler {
    @Published var addressText = ""
    @Published var captureStatus = "当前捕获状态：等待课表响应"
    @Published var hasCapturedSchedule = false
    @Published var parseResult: ScheduleImportCacheParser.ParseResult?

    private weak var webView: WKWebView?
    private var captured: [ScheduleImportCacheParser.CacheEntry] = []
    private var seenKeys = Set<String>()
    private var sequence = 0

    static let hookScript = """
    (function () {
        if (window.__jluCacheHooked) return;
        window.__jluCacheHooked = true;
        function send(url, text) {
            try {
                if (!url || !text) return;
                window.webkit.messageHandlers.jluCache.postMessage(JSON.stringify({url: String(url), text: String(text)}));
            } catch (e) {}
        }
        var _open = XMLHttpRequest.prototype.open;
        var _send = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(method, url) { this.__jluUrl = url || ''; return _open.apply(this, arguments); };
        XMLHttpRequest.prototype.send = function() {
            this.addEventListener('load', function() { send(this.__jluUrl || '', this.responseText || ''); });
            return _send.apply(this, arguments);
        };
        if (window.fetch) {
            var _fetch = window.fetch;
            window.fetch = function(input, init) {
                var url = (typeof input === 'string') ? input : ((input && input.url) || '');
                return _fetch(input, init).then(function(resp) {
                    try { resp.clone().text().then(function(text) { send(url, text); }).catch(function(){}); } catch (e) {}
                    return resp;
                });
            };
        }
    })();
    """

    func attach(_ webView: WKWebView) {
        self.webView = webView
    }

    func loadStartURL() {
        // 优先读包内 target.url，缺失时回退内置校内入口
        let start = Bundle.main.url(forResource: "target", withExtension: "url")
            .flatMap { try? String(contentsOf: $0, encoding: .utf8) }?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let urlText = (start?.isEmpty == false) ? start! : "https://iedu.jlu.edu.cn/"
        if addressText.isEmpty { addressText = urlText }
        if let url = URL(string: urlText) {
            webView?.load(URLRequest(url: url))
        }
    }

    func go() {
        var raw = addressText.trimmingCharacters(in: .whitespaces)
        if !raw.hasPrefix("http://") && !raw.hasPrefix("https://") {
            raw = "https://" + raw
            addressText = raw
        }
        if let url = URL(string: raw) {
            webView?.load(URLRequest(url: url))
        }
    }

    func clearCaptures() {
        captured.removeAll()
        seenKeys.removeAll()
        updateStatus()
    }

    func parsedCourses() -> [CourseSchedule] {
        parseResult?.courses ?? []
    }

    nonisolated func userContentController(_ userContentController: WKUserContentController,
                                           didReceive message: WKScriptMessage) {
        guard message.name == "jluCache",
              let payload = message.body as? String,
              let data = payload.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: String],
              let url = obj["url"], let text = obj["text"], text.count >= 400 else { return }
        Task { @MainActor in
            record(url: url, text: text)
        }
    }

    @MainActor
    private func record(url: String, text: String) {
        guard ScheduleImportCacheParser.isLikelyScheduleUrl(url) else { return }
        guard text.contains("\"datas\""), text.contains("\"rows\"") else { return }
        let key = "JS|\(url)|\(text.prefix(128))"
        guard seenKeys.insert(key).inserted else { return }
        sequence += 1
        captured.append(ScheduleImportCacheParser.CacheEntry(
            url: url, fileName: "%04d_js.json".format(sequence), content: text, sequence: sequence))
        updateStatus()
        reparse()
    }

    @MainActor
    private func reparse() {
        let result = ScheduleImportCacheParser.parse(entries: captured)
        parseResult = result
        hasCapturedSchedule = !result.courses.isEmpty
        updateStatus()
    }

    @MainActor
    private func updateStatus() {
        let count = captured.filter { ScheduleImportCacheParser.isLikelyScheduleUrl($0.url) }.count
        captureStatus = "当前捕获状态：已捕获 \(count) 个疑似课表响应"
    }

    nonisolated func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        Task { @MainActor in
            if let url = webView.url?.absoluteString, !url.isEmpty {
                addressText = url
            }
        }
    }
}

private extension String {
    func format(_ args: CVarArg...) -> String {
        String(format: self, arguments: args)
    }
}
