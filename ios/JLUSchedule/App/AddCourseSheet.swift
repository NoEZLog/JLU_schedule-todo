import SwiftUI

struct AddCourseSheet: View {
    let palette: ThemePalette
    let guessWeek: Int
    @Environment(\.dismiss) private var dismiss

    @State private var courseName = ""
    @State private var teacher = ""
    @State private var location = ""
    @State private var weekday = Weekday.monday
    @State private var startSection = 1
    @State private var endSection = 2
    @State private var startWeek = 1
    @State private var endWeek = 1
    @State private var parity: WeekParity = .all
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("课程信息") {
                    TextField("课程名称", text: $courseName)
                    TextField("授课教师（可留空）", text: $teacher)
                    TextField("上课地点（可留空）", text: $location)
                }
                Section("时间信息") {
                    Picker("星期", selection: $weekday) {
                        ForEach(Weekday.allCases, id: \.self) { day in
                            Text("周\(day.shortLabel)").tag(day)
                        }
                    }
                    Stepper("开始节：\(startSection)", value: $startSection, in: 1...12)
                    Stepper("结束节：\(endSection)", value: $endSection, in: 1...12)
                    Stepper("开始周：第\(startWeek)周", value: $startWeek, in: 1...30)
                    Stepper("结束周：第\(endWeek)周", value: $endWeek, in: 1...30)
                    Picker("单双周规则", selection: $parity) {
                        Text("单双周都上").tag(WeekParity.all)
                        Text("仅单周").tag(WeekParity.odd)
                        Text("仅双周").tag(WeekParity.even)
                    }
                }
                if let errorMessage = errorMessage {
                    Section { Text(errorMessage).foregroundColor(.red).font(.footnote) }
                }
            }
            .navigationTitle("手动添加课程")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                }
            }
            .onAppear {
                startWeek = max(guessWeek, 1)
                endWeek = max(guessWeek, 1)
            }
        }
    }

    private func save() {
        let name = courseName.trimmingCharacters(in: .whitespaces)
        if name.isEmpty { errorMessage = "课程名不能为空"; return }
        if name.count > 40 { errorMessage = "课程名最多 40 个字符"; return }
        if teacher.count > 40 { errorMessage = "教师名称最多 40 个字符"; return }
        if location.count > 40 { errorMessage = "地点最多 40 个字符"; return }
        guard endSection >= startSection else { errorMessage = "结束节不能小于开始节"; return }
        guard endWeek >= startWeek else { errorMessage = "结束周不能小于开始周"; return }

        do {
            try ProfileStore.shared.addManualCourse(.init(
                courseName: name, teacher: teacher.trimmingCharacters(in: .whitespaces),
                location: location.trimmingCharacters(in: .whitespaces),
                weekday: weekday, startSection: startSection, endSection: endSection,
                startWeek: startWeek, endWeek: endWeek, parity: parity))
            StoreChangeNotifier.shared.notifyChanged()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
