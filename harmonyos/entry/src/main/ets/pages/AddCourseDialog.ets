import { ProfileStore, ManualCourseInput } from '../core/ProfileStore.ets';
import {
  CourseSchedule, SimpleDate, PARITY_ALL, PARITY_ODD, PARITY_EVEN,
  guessCurrentWeek, totalWeeks,
} from '../core/Core.ets';
import { currentPalette, ThemePalette } from '../model/Theme.ets';
import { Prefs } from '../model/Prefs.ets';

const WEEKDAY_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

@CustomDialog
export struct AddCourseDialog {
  controller?: CustomDialogController;
  onSaved: () => void = (): void => {};
  @State courseName: string = '';
  @State teacher: string = '';
  @State location: string = '';
  @State weekdayIndex: number = 0;
  @State startSection: number = 1;
  @State endSection: number = 2;
  @State startWeek: number = 1;
  @State endWeek: number = 1;
  @State parity: string = PARITY_ALL;
  @State errorMessage: string = '';
  @StorageProp('systemDark') systemDark: boolean = false;

  aboutToAppear(): void {
    const courses = ProfileStore.instance?.loadActiveCourses() ?? [];
    const semesterStart = ProfileStore.instance?.getActiveSemesterStartDate();
    if (semesterStart !== undefined && semesterStart !== null) {
      const week = guessCurrentWeek(semesterStart, SimpleDate.today(), totalWeeks(courses));
      this.startWeek = Math.max(week, 1);
      this.endWeek = Math.max(week, 1);
    }
  }

  palette(): ThemePalette {
    return currentPalette(Prefs.theme(), Prefs.darkMode(), this.systemDark);
  }

  build() {
    Column() {
      Text('手动添加课程')
        .fontSize(17).fontWeight(FontWeight.Bold)
        .fontColor(this.palette().textPrimary)
        .margin({ bottom: 12 })

      Scroll() {
        Column() {
          this.sectionLabel('课程信息')
          this.fieldLabel('课程名称')
          TextInput({ text: this.courseName, placeholder: '课程名称' })
            .onChange((value: string) => { this.courseName = value; })
            .height(40)
          this.fieldLabel('授课教师（可留空）')
          TextInput({ text: this.teacher, placeholder: '授课教师（可留空）' })
            .onChange((value: string) => { this.teacher = value; })
            .height(40)
          this.fieldLabel('上课地点（可留空）')
          TextInput({ text: this.location, placeholder: '上课地点（可留空）' })
            .onChange((value: string) => { this.location = value; })
            .height(40)

          this.sectionLabel('时间信息')
          this.fieldLabel('星期')
          Select(WEEKDAY_LABELS.map((label: string, index: number) => {
            const option: SelectOption = { value: label };
            return option;
          }))
            .selected(this.weekdayIndex)
            .value(WEEKDAY_LABELS[this.weekdayIndex])
            .onSelect((index: number) => { this.weekdayIndex = index; })
            .height(40)

          Row({ space: 8 }) {
            Column() {
              this.fieldLabel('开始节(1-12)')
              TextInput({ text: String(this.startSection) })
                .type(InputType.Number)
                .onChange((value: string) => { this.startSection = Number(value) || this.startSection; })
                .height(40)
            }.layoutWeight(1)
            Column() {
              this.fieldLabel('结束节(1-12)')
              TextInput({ text: String(this.endSection) })
                .type(InputType.Number)
                .onChange((value: string) => { this.endSection = Number(value) || this.endSection; })
                .height(40)
            }.layoutWeight(1)
          }

          Row({ space: 8 }) {
            Column() {
              this.fieldLabel('开始周(1-30)')
              TextInput({ text: String(this.startWeek) })
                .type(InputType.Number)
                .onChange((value: string) => { this.startWeek = Number(value) || this.startWeek; })
                .height(40)
            }.layoutWeight(1)
            Column() {
              this.fieldLabel('结束周(1-30)')
              TextInput({ text: String(this.endWeek) })
                .type(InputType.Number)
                .onChange((value: string) => { this.endWeek = Number(value) || this.endWeek; })
                .height(40)
            }.layoutWeight(1)
          }

          this.fieldLabel('单双周规则')
          Row({ space: 12 }) {
            Radio({ value: '单双周都上', group: 'parity' }).checked(this.parity === PARITY_ALL)
              .onChange((isChecked: boolean) => { if (isChecked) this.parity = PARITY_ALL; })
            Text('单双周都上').fontSize(13).fontColor(this.palette().textPrimary)
            Radio({ value: '仅单周', group: 'parity' }).checked(this.parity === PARITY_ODD)
              .onChange((isChecked: boolean) => { if (isChecked) this.parity = PARITY_ODD; })
            Text('仅单周').fontSize(13).fontColor(this.palette().textPrimary)
            Radio({ value: '仅双周', group: 'parity' }).checked(this.parity === PARITY_EVEN)
              .onChange((isChecked: boolean) => { if (isChecked) this.parity = PARITY_EVEN; })
            Text('仅双周').fontSize(13).fontColor(this.palette().textPrimary)
          }
          .margin({ top: 4 })

          if (this.errorMessage.length > 0) {
            Text(this.errorMessage)
              .fontSize(12)
              .fontColor('#D35454')
              .margin({ top: 8 })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .width('100%')
      }
      .align(Alignment.Top)
      .scrollable(ScrollDirection.Vertical)
      .layoutWeight(1)

      Row() {
        Button('取消')
          .backgroundColor(this.palette().panelAlt)
          .fontColor(this.palette().textPrimary)
          .onClick(() => {
            this.controller?.close();
          })
        Button('保存')
          .backgroundColor(this.palette().buttonBg)
          .fontColor(this.palette().buttonText)
          .margin({ left: 10 })
          .onClick(() => {
            this.save();
          })
      }
      .width('100%')
      .justifyContent(FlexAlign.End)
      .margin({ top: 12 })
    }
    .padding(16)
    .backgroundColor(this.palette().panelAlt)
    .borderRadius(16)
  }

  @Builder
  sectionLabel(text: string) {
    Text(text)
      .fontSize(13).fontWeight(FontWeight.Bold)
      .fontColor(this.palette().textPrimary)
      .margin({ top: 10, bottom: 4 })
  }

  @Builder
  fieldLabel(text: string) {
    Text(text)
      .fontSize(12)
      .fontColor(this.palette().textSecondary)
      .margin({ top: 8, bottom: 2 })
  }

  save(): void {
    try {
      ProfileStore.instance?.addManualCourse({
        courseName: this.courseName,
        teacher: this.teacher,
        location: this.location,
        weekday: this.weekdayIndex + 1,
        startSection: this.startSection,
        endSection: this.endSection,
        startWeek: this.startWeek,
        endWeek: this.endWeek,
        parity: this.parity,
        semester: '手动添加',
      } as ManualCourseInput);
      this.controller?.close();
      this.onSaved();
    } catch (error) {
      this.errorMessage = (error as Error).message;
    }
  }
}


