import SwiftUI

struct SettingsPage: View {
    let palette: ThemePalette
    @EnvironmentObject var notifier: StoreChangeNotifier
    @Environment(\.colorScheme) private var systemScheme

    @State private var semesterStart = SimpleDate.today()
    @State private var profiles: [ProfileStore.ProfileInfo] = []
    @State private var renameTarget: ProfileStore.ProfileInfo?
    @State private var renameText = ""
    @State private var deleteConfirm: ProfileStore.ProfileInfo?
    @State private var toast: String?
    @State private var shareURL: URL?
    @State private var showRestorePicker = false

    var body: some View {
        NavigationStack {
            Form {
                profilesSection
                defaultPageSection
                semesterSection
                themeSection
                appearanceSection
                fontSection
                displaySection
                reminderSection
                dataSection
                aboutSection
            }
            .scrollContentBackground(.hidden)
            .background(palette.pageBackground)
            .navigationTitle("设置")
            .onAppear { reload() }
            .onChange(of: notifier.version) { _ in reload() }
            .fileImporter(isPresented: $showRestorePicker,
                          allowedContentTypes: [.json, .plainText],
                          allowMultipleSelection: false) { result in
                if case .success(let urls) = result, let url = urls.first {
                    performRestore(at: url)
                }
            }
            .sheet(item: Binding(
                get: { shareURL.map { ShareItem(url: $0) } },
                set: { shareURL = $0?.url }
            )) { item in
                ShareSheet(activityItems: [item.url])
            }
            .alert("重命名课表", isPresented: Binding(
                get: { renameTarget != nil },
                set: { if !$0 { renameTarget = nil } }
            )) {
                TextField("课表名称", text: $renameText)
                Button("保存") {
                    if let target = renameTarget {
                        if ProfileStore.shared.renameProfile(target.id, to: renameText) {
                            toast = "已重命名"
                            notifyChangedAndReload()
                        } else {
                            toast = "名称不能为空"
                        }
                    }
                    renameText = ""
                }
                Button("取消", role: .cancel) { renameText = "" }
            }
            .confirmationDialog(
                "确认删除「\(deleteConfirm?.name ?? "")」吗？",
                isPresented: Binding(
                    get: { deleteConfirm != nil },
                    set: { if !$0 { deleteConfirm = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button("删除", role: .destructive) {
                    if let target = deleteConfirm {
                        if ProfileStore.shared.deleteProfile(target.id) {
                            toast = "已删除课表"
                        } else {
                            toast = "至少保留一个课表，无法删除"
                        }
                        notifyChangedAndReload()
                    }
                }
                Button("取消", role: .cancel) {}
            }
            .overlay(alignment: .bottom) {
                if let toast = toast {
                    Text(toast)
                        .font(.footnote)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(Capsule().fill(palette.panelBackground))
                        .padding(.bottom, 20)
                        .transition(.opacity)
                }
            }
        }
    }

    struct ShareItem: Identifiable {
        let id = UUID()
        let url: URL
    }

    private var profilesSection: some View {
        Section("课表管理") {
            ForEach(profiles) { profile in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(profile.name).foregroundColor(palette.textPrimary)
                            if profile.isActive {
                                Text("当前")
                                    .font(.caption2)
                                    .padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(Capsule().fill(palette.buttonBackground))
                                    .foregroundColor(palette.buttonText)
                            }
                        }
                        Text("最近更新：\(profile.updatedAt.formatted(date: .abbreviated, time: .shortened))")
                            .font(.caption)
                            .foregroundColor(palette.textSecondary)
                    }
                    Spacer()
                    Menu {
                        if !profile.isActive {
                            Button("切换到此课表") { switchTo(profile) }
                        }
                        Button("重命名") { renameText = profile.name; renameTarget = profile }
                        Button("删除", role: .destructive) { deleteConfirm = profile }
                    } label: {
                        Image(systemName: "ellipsis.circle").foregroundColor(palette.iconTint)
                    }
                }
            }
            Button("新建空课表") {
                let info = ProfileStore.shared.createEmptyProfile(named: "")
                notifyChangedAndReload()
                toast = "已新建并切换到：\(info.name)"
            }
        }
    }

    private var defaultPageSection: some View {
        Section("启动默认页面") {
            // iOS 版本从课表页启动；此处保留说明
            Text("iOS 版默认打开课表页").foregroundColor(palette.textSecondary)
        }
    }

    private var semesterSection: some View {
        Section("学期开始周（第一周起始日期）") {
            DatePicker("第一周周一", selection: Binding(
                get: { semesterStart.date },
                set: { newValue in
                    var comps = Calendar.current.dateComponents([.year, .month, .day], from: newValue)
                    let date = SimpleDate(year: comps.year ?? 2026, month: comps.month ?? 1, day: comps.day ?? 1)
                    ProfileStore.shared.setActiveSemesterStartDate(date)
                    notifyChangedAndReload()
                }
            ), displayedComponents: .date)
            .foregroundColor(palette.textPrimary)
        }
    }

    private var themeSection: some View {
        Section("主题色") {
            Picker("主题色", selection: Binding(
                get: { AppPreferences.theme },
                set: { AppPreferences.theme = $0; notifier.notifyChanged() }
            )) {
                Text("暖色").tag("warm")
                Text("海蓝").tag("ocean")
                Text("薄荷").tag("mint")
            }
            .pickerStyle(.segmented)
        }
    }

    private var appearanceSection: some View {
        Section("外观模式") {
            Picker("外观", selection: Binding(
                get: { AppPreferences.darkMode },
                set: { AppPreferences.darkMode = $0; notifier.notifyChanged() }
            )) {
                Text("跟随系统").tag("system")
                Text("浅色").tag("light")
                Text("深色").tag("dark")
            }
            .pickerStyle(.segmented)
        }
    }

    private var fontSection: some View {
        Section("课表字号") {
            Picker("字号", selection: Binding(
                get: { AppPreferences.fontScale },
                set: { AppPreferences.fontScale = $0; notifier.notifyChanged() }
            )) {
                Text("小号").tag(0.88)
                Text("标准").tag(1.0)
                Text("大号").tag(1.14)
            }
            .pickerStyle(.segmented)
        }
    }

    private var displaySection: some View {
        Section("非本周课程") {
            Toggle("显示非本周课程", isOn: Binding(
                get: { AppPreferences.showNonCurrent },
                set: { AppPreferences.showNonCurrent = $0; notifier.notifyChanged() }
            ))
        }
    }

    private var reminderSection: some View {
        Section("上课提醒") {
            Toggle("每天早上发送今日课程提醒", isOn: Binding(
                get: { AppPreferences.reminderEnabled },
                set: { enabled in
                    AppPreferences.reminderEnabled = enabled
                    if enabled {
                        Task {
                            let ok = await ReminderScheduler.requestAuthorizationAndSchedule()
                            if !ok {
                                AppPreferences.reminderEnabled = false
                                notifier.notifyChanged()
                                toast = "未获得通知权限"
                            }
                        }
                    } else {
                        ReminderScheduler.clearPending()
                    }
                    notifier.notifyChanged()
                }
            ))
            DatePicker("提醒时间", selection: Binding(
                get: {
                    let minute = AppPreferences.reminderMinute
                    return Calendar.current.date(bySettingHour: minute / 60, minute: minute % 60, second: 0, of: Date()) ?? Date()
                },
                set: { newValue in
                    let comps = Calendar.current.dateComponents([.hour, .minute], from: newValue)
                    AppPreferences.reminderMinute = (comps.hour ?? 7) * 60 + (comps.minute ?? 30)
                    if AppPreferences.reminderEnabled {
                        Task { await ReminderScheduler.refreshIfEnabled() }
                    }
                }
            ), displayedComponents: .hourAndMinute)
        }
    }

    private var dataSection: some View {
        Section("数据管理") {
            Button("导出当前课表为文本") {
                share(ProfileStore.shared.exportActiveTimetableText(periodRanges: SectionTimes.ranges),
                      fileName: "JLU课表.txt")
            }
            Button("备份全部课表") {
                let formatter = DateFormatter()
                formatter.dateFormat = "yyyyMMdd_HHmm"
                share(ProfileStore.shared.exportBackup(),
                      fileName: "JLU_schedule_backup_\(formatter.string(from: Date())).json")
            }
            Button("恢复备份") { showRestorePicker = true }
        }
    }

    private var aboutSection: some View {
        Section {
            LabeledContent("版本", value: "1.2.0")
        } footer: {
            Text("吉林大学课表应用 · iOS 版，与 Android 版功能对齐")
        }
    }

    // MARK: - 行为

    private func reload() {
        semesterStart = ProfileStore.shared.getActiveSemesterStartDate()
        profiles = ProfileStore.shared.listProfiles().sorted { $0.updatedAt > $1.updatedAt }
    }

    private func notifyChangedAndReload() {
        notifier.notifyChanged()
        reload()
    }

    private func switchTo(_ profile: ProfileStore.ProfileInfo) {
        if ProfileStore.shared.setActiveProfile(profile.id) {
            toast = "已切换到：\(profile.name)"
            notifyChangedAndReload()
        }
    }

    private func performRestore(at url: URL) {
        do {
            let secured = url.startAccessingSecurityScopedResource()
            defer { if secured { url.stopAccessingSecurityScopedResource() } }
            let content = try String(contentsOf: url, encoding: .utf8)
            let count = try ProfileStore.shared.importBackup(content)
            toast = "已恢复 \(count) 个课表"
            notifyChangedAndReload()
        } catch {
            toast = "恢复失败：文件格式不正确"
        }
    }
}

// MARK: - UIActivityViewController 封装

struct ShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
