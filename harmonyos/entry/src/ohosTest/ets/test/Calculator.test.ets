import { describe, it, expect } from '@ohos/hypium';
import {
  SimpleDate, guessCurrentWeek, totalWeeks, WeekRule, CourseSchedule, MeetingTime,
  displayMeetings,
} from '../../../../main/ets/core/Core.ets';
import { parseCacheEntries, CacheEntry } from '../../../../main/ets/core/ImportParser.ets';

function course(rules: WeekRule[], weekday: number): CourseSchedule {
  const meeting: MeetingTime = {
    weekday, startSection: 1, endSection: 2, weekRules: rules, location: '教室',
  };
  return {
    courseName: '测试课程', teacher: '老师', semester: '2026-2027-1',
    credit: null, rawWeekText: 'custom', meetings: [meeting],
  };
}

export default function calcTests(): void {
  describe('weekCalculator', () => {
    it('guesses_current_week', 0, () => {
      const start = new SimpleDate(2026, 8, 31);
      expect(guessCurrentWeek(start, start, 20)).assertEqual(1);
      expect(guessCurrentWeek(start, start.adding(7), 20)).assertEqual(2);
      expect(guessCurrentWeek(start, start.adding(-30), 20)).assertEqual(1);
      expect(guessCurrentWeek(start, start.adding(365), 20)).assertEqual(20);
    });

    it('total_weeks', 0, () => {
      expect(totalWeeks([course([{ startWeek: 1, endWeek: 16, parity: 'ALL' }], 1)])).assertEqual(16);
      expect(totalWeeks([course([{ startWeek: 2, endWeek: 20, parity: 'ODD' }], 2)])).assertEqual(20);
      expect(totalWeeks([])).assertEqual(20);
    });

    it('shows_ended_courses_in_past_weeks', 0, () => {
      const courses = [course([{ startWeek: 1, endWeek: 5, parity: 'ODD' }], 1)];
      const display = displayMeetings(courses, 4, true);
      expect(display.length).assertEqual(1);
      expect(display[0].isCurrentWeek).assertFalse();
      expect(display[0].nextActiveWeek).assertEqual(5);
    });
  });

  describe('importCacheParser', () => {
    it('selects_latest_semester_batch', 0, () => {
      const oldEntry: CacheEntry = {
        url: 'https://x/cxxszhxqkb.do', fileName: '0001.do', sequence: 1,
        content: JSON.stringify({
          datas: { xskcb: { rows: [{
            KCM: '旧课', XNXQDM_DISPLAY: '2025-2026学年第一学期', ZCMC: '1-16周', SKXQ: 1, KSJC: 1, JSJC: 2,
          }] } },
        }),
      };
      const newEntry: CacheEntry = {
        url: 'https://x/cxxszhxqkb.do', fileName: '0002.do', sequence: 2,
        content: JSON.stringify({
          datas: { xskcb: { rows: [{
            KCM: '新课', XNXQDM_DISPLAY: '2026-2027学年第一学期', ZCMC: '1-16周', SKXQ: 1, KSJC: 1, JSJC: 2,
          }] } },
        }),
      };
      const result = parseCacheEntries([oldEntry, newEntry]);
      expect(result.courses.length).assertEqual(1);
      expect(result.courses[0].courseName).assertEqual('新课');
      expect(result.selectedSemester).assertEqual('2026-2027学年第一学期');
      const inferred = result.inferredSemesterStartDate;
      expect(inferred !== null).assertTrue();
      if (inferred !== null) {
        expect(inferred.isoString()).assertEqual('2026-08-31');
      }
    });
  });
}
