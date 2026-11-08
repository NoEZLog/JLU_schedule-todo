import {
  CourseSchedule, MeetingTime, SimpleDate, guessCurrentWeek, totalWeeks,
  meetingsInWeek, SECTION_RANGES,
} from '../core/Core.ets';

const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日'];

interface CourseMeetingItem {
  courseIndex: number;
  course: CourseSchedule;
  meeting: MeetingTime;
  isCurrentWeek: boolean;
  nextActiveWeek: number;
}
import { ProfileStore } from '../core/ProfileStore.ets';
import { currentPalette, ThemePalette } from '../model/Theme.ets';
import { Prefs } from '../model/Prefs.ets';

@Component
export struct TodayPage {
  @StorageProp('dataVersion') @Watch('reload') dataVersion: number = 0;
  @StorageProp('themeVersion') @Watch('reload') themeVersion: number = 0;
  @StorageProp('systemDark') systemDark: boolean = false;
  @State courses: CourseSchedule[] = [];
  @State week: number = 1;

  aboutToAppear(): void {
    this.reload();
  }

  reload(): void {
    this.courses = ProfileStore.instance?.loadActiveCourses() ?? [];
    const semesterStart = ProfileStore.instance?.getActiveSemesterStartDate() ?? SimpleDate.today();
    this.week = guessCurrentWeek(semesterStart, SimpleDate.today(), totalWeeks(this.courses));
  }

  palette(): ThemePalette {
    return currentPalette(Prefs.theme(), Prefs.darkMode(), this.systemDark);
  }

  today(): SimpleDate {
    return SimpleDate.today();
  }

  todayMeetings(): CourseMeetingItem[] {
    return meetingsInWeek(this.courses, this.week)
      .filter((item) => item.meeting.weekday === this.today().weekday())
      .sort((a, b) => a.meeting.startSection - b.meeting.startSection);
  }

  firstTime(): string {
    const meetings = this.todayMeetings();
    if (meetings.length === 0) return '无';
    let min = meetings[0].meeting.startSection;
    for (const item of meetings) {
      if (item.meeting.startSection < min) min = item.meeting.startSection;
    }
    return (SECTION_RANGES[min - 1] ?? '--:--').split('-')[0];
  }

  lastTime(): string {
    const meetings = this.todayMeetings();
    if (meetings.length === 0) return '无';
    let max = meetings[0].meeting.endSection;
    for (const item of meetings) {
      if (item.meeting.endSection > max) max = item.meeting.endSection;
    }
    return (SECTION_RANGES[max - 1] ?? '--:--').split('-')[1];
  }

  build() {
    Scroll() {
      Column() {
        Column() {
          Text('今日日程')
            .fontSize(20).fontWeight(FontWeight.Bold)
            .fontColor(this.palette().textPrimary)
          Text(`${this.today().displayText()} 周${WEEKDAY_LABELS[this.today().weekdayMonBased() - 1]}`)
            .fontSize(13)
            .fontColor(this.palette().textSecondary)
            .margin({ top: 4 })
          Text(`${this.todayMeetings().length} 门课`)
            .fontSize(12)
            .fontColor(this.palette().textPrimary)
            .padding({ left: 8, right: 8, top: 3, bottom: 3 })
            .backgroundColor(this.palette().panel)
            .borderRadius(12)
            .margin({ top: 8 })
        }
        .alignItems(HorizontalAlign.Start)
        .width('100%')
        .padding(12)
        .backgroundColor(this.palette().panelAlt)
        .borderRadius(18)

        Row({ space: 8 }) {
          Text(`最早：${this.firstTime()}`)
            .fontSize(12)
            .fontColor(this.palette().textSecondary)
            .layoutWeight(1)
            .padding(10)
            .backgroundColor(this.palette().panel)
            .borderRadius(12)
          Text(`最晚：${this.lastTime()}`)
            .fontSize(12)
            .fontColor(this.palette().textSecondary)
            .layoutWeight(1)
            .padding(10)
            .backgroundColor(this.palette().panel)
            .borderRadius(12)
        }
        .width('100%')
        .margin({ top: 10 })

        if (this.todayMeetings().length === 0) {
          Text('今天没有课程安排')
            .fontSize(15).fontWeight(FontWeight.Medium)
            .fontColor(this.palette().textPrimary)
            .width('100%')
            .padding(18)
            .backgroundColor(this.palette().panelAlt)
            .borderRadius(18)
            .margin({ top: 10 })
        } else {
          ForEach(this.todayMeetings(), (item: CourseMeetingItem) => {
            Column() {
              Text(item.course.courseName).fontSize(14).fontWeight(FontWeight.Medium)
              Text(`第${item.meeting.startSection}-${item.meeting.endSection}节  ${this.rangeText(item)}`)
                .fontSize(14)
              Text(item.meeting.location.length > 0 ? item.meeting.location : '教室待定')
                .fontSize(14)
            }
            .alignItems(HorizontalAlign.Start)
            .width('100%')
            .padding(16)
            .backgroundColor(this.palette().panel)
            .borderRadius(18)
            .margin({ top: 10 })
          }, (item: CourseMeetingItem) => `${item.course.courseName}-${item.meeting.startSection}`)
        }
      }
      .padding(12)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.palette().page)
    .align(Alignment.Top)
    .scrollable(ScrollDirection.Vertical)
  }

  rangeText(item: CourseMeetingItem): string {
    const start = Math.min(Math.max(item.meeting.startSection, 1), SECTION_RANGES.length);
    const end = Math.min(Math.max(item.meeting.endSection, start), SECTION_RANGES.length);
    const startText = (SECTION_RANGES[start - 1] ?? '--:--').split('-')[0];
    const endText = (SECTION_RANGES[end - 1] ?? '--:--').split('-')[1];
    return `${startText}-${endText}`;
  }
}

