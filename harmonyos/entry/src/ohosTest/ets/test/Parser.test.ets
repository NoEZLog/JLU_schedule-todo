import { describe, beforeAll, afterAll, it, expect } from '@ohos/hypium';
import { parseSchedule, parseWeekRules, WeekRule } from '../../../../main/ets/core/Core.ets';

export default function parserTests(): void {
  describe('doScheduleParser', () => {
    it('accepts_numeric_fields_as_strings', 0, () => {
      const courses = parseSchedule(JSON.stringify({
        datas: {
          xskcb: {
            rows: [{
              KCM: '高等数学', SKJS: '张三', XNXQDM: '2026-2027-1', XF: '3.5',
              ZCMC: '1-16周', SKXQ: '1', KSJC: '3', JSJC: '4', JASMC: '逸夫楼A101',
            }],
          },
        },
      }));
      expect(courses.length).assertEqual(1);
      expect(courses[0].courseName).assertEqual('高等数学');
      expect(courses[0].credit).assertEqual(3.5);
      expect(courses[0].meetings[0].weekday).assertEqual(1);
      expect(courses[0].meetings[0].startSection).assertEqual(3);
      expect(courses[0].meetings[0].endSection).assertEqual(4);
      expect(courses[0].meetings[0].location).assertEqual('逸夫楼A101');
    });

    it('splits_multiple_segments_and_parity', 0, () => {
      const courses = parseSchedule(JSON.stringify({
        datas: {
          xskcb: {
            rows: [{
              KCM: '大学物理', SKJS: '李四', XNXQDM_DISPLAY: '2026-2027学年第一学期', XF: 2,
              YPSJDD: '1-8周(单) 星期二 第1节-第2节 逸夫楼B201,9-16周(双) 星期四 第7节-第8节 中心校区C301',
              ZCMC: '1-16周', SKXQ: 2, KSJC: 1, JSJC: 2,
            }],
          },
        },
      }));
      expect(courses[0].semester).assertEqual('2026-2027学年第一学期');
      expect(courses[0].meetings.length).assertEqual(2);
      expect(courses[0].meetings[0].weekday).assertEqual(2);
      expect(courses[0].meetings[0].weekRules[0].parity).assertEqual('ODD');
      expect(courses[0].meetings[1].weekday).assertEqual(4);
      expect(courses[0].meetings[1].weekRules[0].parity).assertEqual('EVEN');
    });

    it('supports_discrete_week_list', 0, () => {
      const courses = parseSchedule(JSON.stringify({
        datas: { xskcb: { rows: [{ KCM: '体育', YPSJDD: '第1,3,5,7周 星期三 第3节-第4节 田径场' }] } },
      }));
      const rules: WeekRule[] = courses[0].meetings[0].weekRules;
      expect(rules.length).assertEqual(4);
      expect(rules[0].startWeek).assertEqual(1);
      expect(rules[1].startWeek).assertEqual(3);
      expect(rules[2].startWeek).assertEqual(5);
      expect(rules[3].startWeek).assertEqual(7);
    });

    it('supports_full_width_parity_and_chinese_comma', 0, () => {
      const courses = parseSchedule(JSON.stringify({
        datas: {
          xskcb: {
            rows: [{
              KCM: '数据结构',
              YPSJDD: '1-8周(单) 星期二 第1节-第2节 A楼，9-16周(双) 星期四 第7节-第8节 B楼',
            }],
          },
        },
      }));
      expect(courses[0].meetings.length).assertEqual(2);
      expect(courses[0].meetings[0].location).assertEqual('A楼');
      expect(courses[0].meetings[0].weekRules[0].parity).assertEqual('ODD');
      expect(courses[0].meetings[1].location).assertEqual('B楼');
      expect(courses[0].meetings[1].weekRules[0].parity).assertEqual('EVEN');
    });

    it('swaps_reversed_ranges', 0, () => {
      const courses = parseSchedule(JSON.stringify({
        datas: { xskcb: { rows: [{ KCM: '光学', YPSJDD: '16-1周 星期一 第4节-第2节 教室' }] } },
      }));
      expect(courses[0].meetings[0].startSection).assertEqual(2);
      expect(courses[0].meetings[0].endSection).assertEqual(4);
      expect(courses[0].meetings[0].weekRules[0].startWeek).assertEqual(1);
      expect(courses[0].meetings[0].weekRules[0].endWeek).assertEqual(16);
    });

    it('tolerates_explicit_nulls', 0, () => {
      expect(parseSchedule('{"datas": null}').length).assertEqual(0);
      expect(parseSchedule('{"datas": {"x": {"rows": null}}}').length).assertEqual(0);
    });

    it('defaults_week_rules_when_unparseable', 0, () => {
      const rules = parseWeekRules('待定');
      expect(rules.length).assertEqual(1);
      expect(rules[0].startWeek).assertEqual(1);
      expect(rules[0].endWeek).assertEqual(20);
      expect(rules[0].parity).assertEqual('ALL');
      const parityRules = parseWeekRules('双周');
      expect(parityRules[0].parity).assertEqual('EVEN');
      expect(parityRules[0].endWeek).assertEqual(20);
    });
  });
}
