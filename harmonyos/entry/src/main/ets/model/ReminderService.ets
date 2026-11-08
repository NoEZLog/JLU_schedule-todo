/**
 * 每日课程提醒（对齐 Android DailyReminderReceiver）。
 * 采用未来 7 天滚动日历提醒：每次启动/数据变化时取消旧提醒并重排，
 * 每条提醒内容在排程时按对应日期的课表生成。
 */
import { reminderAgentManager } from '@kit.BackgroundTaskKit';
import { notificationManager } from '@kit.NotificationKit';
import { Prefs } from './Prefs.ets';
import {
  CourseSchedule, SimpleDate, guessCurrentWeek, totalWeeks,
  meetingsInWeek, SECTION_RANGES,
} from '../core/Core.ets';
import { ProfileStore } from '../core/ProfileStore.ets';

const TITLE_PREFIX = '今日课程';

export class ReminderService {
  static async refreshIfEnabled(): Promise<void> {
    if (!Prefs.reminderEnabled()) {
      await ReminderService.clearPending();
      return;
    }
    try {
      const enabled = await notificationManager.isNotificationEnabled();
      if (!enabled) return;
    } catch {
      return;
    }
    await ReminderService.scheduleWindow();
  }

  static async requestAndSchedule(): Promise<boolean> {
    try {
      await notificationManager.requestEnableNotification();
    } catch {
      return false;
    }
    await ReminderService.scheduleWindow();
    return true;
  }

  static async clearPending(): Promise<void> {
    try {
      const reminders = await reminderAgentManager.getValidReminders();
      for (const reminder of reminders) {
        if (reminder.title?.startsWith(TITLE_PREFIX) ?? false) {
          await reminderAgentManager.cancelReminder(reminder.reminderId);
        }
      }
    } catch {
      // 忽略取消失败
    }
  }

  static async scheduleWindow(): Promise<void> {
    await ReminderService.clearPending();
    const minute = Prefs.reminderMinute();
    const courses: CourseSchedule[] = ProfileStore.instance!.loadActiveCourses();
    const semesterStart = ProfileStore.instance!.getActiveSemesterStartDate();
    const weeks = totalWeeks(courses);
    const today = SimpleDate.today();

    for (let offset = 1; offset <= 7; offset++) {
      const date = today.adding(offset);
      const week = guessCurrentWeek(semesterStart, date, weeks);
      const meetings = meetingsInWeek(courses, week).filter((item) => item.meeting.weekday === date.weekday());
      let body = '今天没有课程安排';
      if (meetings.length > 0) {
        let first = meetings[0];
        for (const item of meetings) {
          if (item.meeting.startSection < first.meeting.startSection) first = item;
        }
        const range = SECTION_RANGES[first.meeting.startSection - 1] ?? '--:--';
        body = `${meetings.length} 门课，最早 ${range.split('-')[0]} ${first.course.courseName}`;
      }

      const reminder: reminderAgentManager.ReminderRequestCalendar = {
        reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_CALENDAR,
        title: `${TITLE_PREFIX}·${date.isoString()}`,
        content: body,
        dateTime: {
          year: date.year,
          month: date.month,
          day: date.day,
          hour: Math.floor(minute / 60),
          minute: minute % 60,
          second: 0,
        },
        wantAgent: {
          pkgName: 'cn.jlu.schedule.harmony',
          abilityName: 'EntryAbility',
        },
      };
      try {
        await reminderAgentManager.publishReminder(reminder);
      } catch {
        // 单条失败不阻塞其余提醒
      }
    }
  }
}
