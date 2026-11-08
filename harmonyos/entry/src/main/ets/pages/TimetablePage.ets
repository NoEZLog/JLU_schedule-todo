import {
  CourseSchedule, SimpleDate, CourseMeetingRef,
  displayMeetings, guessCurrentWeek, totalWeeks, resolveOverlaps, SECTION_RANGES,
} from '../core/Core.ets';
import { ProfileStore } from '../core/ProfileStore.ets';
import { currentPalette, CARD_COLORS, ThemePalette } from '../model/Theme.ets';
import { Prefs } from '../model/Prefs.ets';
import { AddCourseDialog } from './AddCourseDialog.ets';
import { router } from '@kit.ArkUI';

const ROW_HEIGHT = 46;
const HEADER_HEIGHT = 34;
const LEFT_COLUMN_WIDTH = 44;
const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日'];

@Component
export struct TimetablePage {
  @StorageProp('dataVersion') @Watch('reload') dataVersion: number = 0;
  @StorageProp('themeVersion') @Watch('reload') themeVersion: number = 0;
  @StorageProp('systemDark') systemDark: boolean = false;
  @State courses: CourseSchedule[] = [];
  @State semesterStart: SimpleDate = SimpleDate.today();
  @State currentWeek: number = 1;
  @State weeks: number = 20;
  @State selectedWeek: number = 1;
  addDialogController: CustomDialogController = new CustomDialogController({
    builder: AddCourseDialog({
      onSaved: (): void => {
        AppStorage.setOrCreate('dataVersion', Date.now());
      },
    }),
    autoCancel: true,
    customStyle: false,
  });

  aboutToAppear(): void {
    this.reload();
  }

  reload(): void {
    this.courses = ProfileStore.instance?.loadActiveCourses() ?? [];
    this.semesterStart = ProfileStore.instance?.getActiveSemesterStartDate() ?? SimpleDate.today();
    this.weeks = totalWeeks(this.courses);
    this.currentWeek = guessCurrentWeek(this.semesterStart, SimpleDate.today(), this.weeks);
    if (this.selectedWeek < 1 || this.selectedWeek > this.weeks) {
      this.selectedWeek = this.currentWeek;
    }
  }

  palette(): ThemePalette {
    return currentPalette(Prefs.theme(), Prefs.darkMode(), this.systemDark);
  }

  currentSection(): number | null {
    const now = new Date();
    const pad = (n: number): string => String(n).padStart(2, '0');
    const nowText = `${pad(now.getHours())}:${pad(now.getMinutes())}`;
    for (let i = 0; i < SECTION_RANGES.length; i++) {
      const parts = SECTION_RANGES[i].split('-');
      if (parts[0] <= nowText && nowText < parts[1]) return i + 1;
    }
    return null;
  }

  build() {
    Column() {
      // 头部：周次 + 日期 + 操作按钮
      Row() {
        Column() {
          Text(this.headerText())
            .fontSize(15).fontWeight(FontWeight.Bold)
            .fontColor(this.palette().textPrimary)
          Text(SimpleDate.today().displayText())
            .fontSize(12)
            .fontColor(this.palette().textSecondary)
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)

        Button('＋')
          .backgroundColor(this.palette().panelBackground)
          .fontColor(this.palette().iconTint)
          .fontSize(20)
          .width(36).height(36)
          .margin({ right: 8 })
          .onClick(() => {
            this.addDialogController.open();
          })

        Button('导入')
          .backgroundColor(this.palette().panelBackground)
          .fontColor(this.palette().iconTint)
          .fontSize(13)
          .height(36)
          .onClick(() => {
            router.pushUrl({ url: 'pages/ImportPage' });
          })
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 6, bottom: 6 })

      // 周切换
      Row() {
        Button('‹')
          .backgroundColor(this.palette().panelBackground)
          .fontColor(this.palette().textPrimary)
          .onClick(() => {
            if (this.selectedWeek > 1) this.selectedWeek -= 1;
          })
        Text(`第 ${this.selectedWeek} 周 / 共 ${this.weeks} 周`)
          .fontSize(13)
          .fontColor(this.palette().textSecondary)
          .margin({ left: 12, right: 12 })
          .onClick(() => {
            this.selectedWeek = this.currentWeek;
          })
        Button('›')
          .backgroundColor(this.palette().panelBackground)
          .fontColor(this.palette().textPrimary)
          .onClick(() => {
            if (this.selectedWeek < this.weeks) this.selectedWeek += 1;
          })
      }
      .width('100%')
      .padding({ left: 12, right: 12, bottom: 4 })

      // 周网格
      Scroll() {
        Row() {
          // 左列：节次
          Column() {
            Text(`${this.weekStart().month}月`)
              .fontSize(10).fontWeight(FontWeight.Bold)
              .fontColor(this.palette().textPrimary)
              .height(HEADER_HEIGHT)
              .width('100%')
              .textAlign(TextAlign.Center)
            ForEach(SECTION_RANGES, (range: string, index: number) => {
              Column() {
                Text(`${index + 1}`).fontSize(11).fontWeight(FontWeight.Bold)
                Text(range.split('-')[0]).fontSize(8)
                Text(range.split('-')[1]).fontSize(8)
              }
              .fontColor(this.palette().textSecondary)
              .width(LEFT_COLUMN_WIDTH - 6)
              .height(ROW_HEIGHT)
              .textAlign(TextAlign.Center)
            }, (range: string) => range)
          }
          .backgroundColor(this.palette().gridLeft)

          Row({ space: 2 }) {
            ForEach([0, 1, 2, 3, 4, 5, 6], (dayIndex: number) => {
              this.DayColumn(dayIndex)
            }, (dayIndex: number) => dayIndex.toString())
          }
          .padding({ left: 4, right: 4 })
        }
        .alignItems(VerticalAlign.Top)
      }
      .layoutWeight(1)
      .align(Alignment.Top)
      .scrollable(ScrollDirection.Vertical)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.palette().page)
  }

  weekStart(): SimpleDate {
    return this.semesterStart.adding((this.selectedWeek - 1) * 7);
  }

  headerText(): string {
    const weekdayLabel = WEEKDAY_LABELS[SimpleDate.today().weekdayMonBased() - 1];
    if (this.selectedWeek === this.currentWeek) {
      return `第${this.selectedWeek}周 周${weekdayLabel}`;
    }
    return `第${this.selectedWeek}周（非本周）`;
  }

  @Builder
  DayColumn(dayIndex: number) {
    Column() {
      Column() {
        Text(WEEKDAY_LABELS[dayIndex])
          .fontSize(12).fontWeight(this.isToday(dayIndex) ? FontWeight.Bold : FontWeight.Normal)
        Text(`${this.dayDate(dayIndex).month}/${this.dayDate(dayIndex).day}`).fontSize(9)
      }
      .fontColor(this.palette().textPrimary)
      .width('100%')
      .height(HEADER_HEIGHT)
      .backgroundColor(this.isToday(dayIndex) ? this.palette().gridHeaderToday : this.palette().gridHeader)
      .textAlign(TextAlign.Center)

      Stack({ alignContent: Alignment.TopStart }) {
        Rect()
          .width('100%').height('100%')
          .fill(this.isToday(dayIndex) ? this.palette().gridDayToday : this.palette().gridDay)
          .radius(4)
        if (this.currentSection() !== null) {
          Line({ start: [0, 0], end: [44, 0] })
            .stroke(this.palette().iconTint)
            .strokeWidth(1.5)
            .offset({ x: 0, y: ((this.currentSection() ?? 1) - 1) * ROW_HEIGHT })
        }
        ForEach(
          resolveOverlaps(displayMeetings(this.courses, this.selectedWeek, Prefs.showNonCurrent())
            .filter((item: CourseMeetingRef) => item.meeting.weekday === dayIndex + 1)),
          (item: CourseMeetingRef) => {
            this.CourseCard(item)
          },
          (item: CourseMeetingRef) => `${item.courseIndex}-${item.meeting.weekday}-${item.meeting.startSection}`,
        )
      }
      .width(46)
      .height(ROW_HEIGHT * 12)
      .clip(true)
    }
  }

  dayDate(dayIndex: number): SimpleDate {
    return this.weekStart().adding(dayIndex);
  }

  isToday(dayIndex: number): boolean {
    return this.dayDate(dayIndex).equals(SimpleDate.today());
  }

  @Builder
  CourseCard(item: CourseMeetingRef) {
    Column() {
      if (!item.isCurrentWeek) {
        Text(`[非本周] 第${item.nextActiveWeek}周`)
          .fontSize(7.5 * Prefs.fontScale())
          .maxLines(1)
      }
      Text(item.course.courseName)
        .fontSize(12 * Prefs.fontScale())
        .fontWeight(FontWeight.Medium)
        .maxLines(4)
      Text(item.meeting.location.length > 0 ? item.meeting.location : '教室待定')
        .fontSize(9.5 * Prefs.fontScale())
        .maxLines(2)
    }
    .alignItems(HorizontalAlign.Start)
    .width(44)
    .height((item.meeting.endSection - item.meeting.startSection + 1) * ROW_HEIGHT - 2)
    .padding(3)
    .backgroundColor(CARD_COLORS[item.courseIndex % CARD_COLORS.length])
    .borderRadius(5)
    .opacity(item.isCurrentWeek ? 1 : 0.55)
    .onClick(() => {
      this.showCourseDetail(item);
    })
  }

  showCourseDetail(item: CourseMeetingRef): void {
    const rangeStart = SECTION_RANGES[item.meeting.startSection - 1]?.split('-')[0] ?? '--:--';
    const rangeEnd = SECTION_RANGES[item.meeting.endSection - 1]?.split('-')[1] ?? '--:--';
    const creditText = item.course.credit === null ? 'N/A' : String(item.course.credit);
    this.getUIContext().showAlertDialog({
      title: item.course.courseName,
      message: `教师：${item.course.teacher.length > 0 ? item.course.teacher : '未知'}\n` +
        `节次：第${item.meeting.startSection}-${item.meeting.endSection}节 (${rangeStart}-${rangeEnd})\n` +
        `地点：${item.meeting.location.length > 0 ? item.meeting.location : '未标注'}\n` +
        `周次：${item.course.rawWeekText}\n` +
        `学期：${item.course.semester}   学分：${creditText}`,
      buttons: [{ value: '知道了', action: (): void => {} }],
    });
  }
}
