/**
 * 鸿蒙核心逻辑 Node 验证脚本。
 * 把 .ets 核心文件改写为 .ts 后用 Node 原生类型剥离运行断言，
 * 使解析器/周计算/策略/备份编解码在 Windows 上即可回归（无需 DevEco）。
 *
 * 运行：node verify/run.mjs
 */
import { execFileSync } from 'node:child_process';
import { mkdirSync, rmSync, readFileSync, writeFileSync, copyFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = join(here, '..');
const coreDir = join(projectRoot, 'entry', 'src', 'main', 'ets', 'core');
const tmpDir = join(here, '.verify_tmp');
const testFile = join(tmpDir, 'verify.test.ts');

rmSync(tmpDir, { recursive: true, force: true });
mkdirSync(tmpDir, { recursive: true });

for (const name of ['Core.ets', 'ImportParser.ets']) {
  let source = readFileSync(join(coreDir, name), 'utf-8');
  source = source.replaceAll("from './Core.ets'", "from './Core.ts'");
  // 类型剥离模式下 interface 无运行时导出：把纯类型导入拆分为 import type
  const typeOnlyNames = new Set([
    'CourseSchedule', 'MeetingTime', 'WeekRule', 'CourseMeetingRef',
    'CacheEntry', 'ParseResult', 'RejectedEntry',
    'BackupFile', 'BackupProfile', 'BackupCourse', 'BackupMeeting', 'BackupWeekRule',
  ]);
  source = source.replace(/import \{([^}]*)\} from '\.\/Core\.ts';/gs, (whole, inner) => {
    const names = inner.split(',').map((s) => s.trim()).filter(Boolean);
    const typeImports = names.filter((n) => typeOnlyNames.has(n));
    const valueImports = names.filter((n) => !typeOnlyNames.has(n));
    const parts = [];
    if (typeImports.length > 0) parts.push(`import type { ${typeImports.join(', ')} } from './Core.ts';`);
    if (valueImports.length > 0) parts.push(`import { ${valueImports.join(', ')} } from './Core.ts';`);
    return parts.join('\n');
  });
  writeFileSync(join(tmpDir, name.replace('.ets', '.ts')), source);
}

const testSource = String.raw`
import assert from 'node:assert/strict';
import {
  SimpleDate, parseSchedule, parseWeekRules, guessCurrentWeek, totalWeeks,
  displayMeetings, defaultForToday, normalizeToWeekStart, inferFromSemesterLabelOrNull,
  encodeBackup, decodeBackup, courseToBackup, backupToCourse,
} from './Core.ts';
import { parseCacheEntries } from './ImportParser.ts';

const results = [];
function test(name, fn) {
  try { fn(); results.push(['PASS', name]); }
  catch (error) { results.push(['FAIL', name + ' :: ' + error.message]); process.exitCode = 1; }
}

function scheduleJson(rows) {
  return JSON.stringify({ datas: { xskcb: { rows } } });
}

// ---- 解析器 ----
test('accepts numeric fields as strings', () => {
  const courses = parseSchedule(scheduleJson([{
    KCM: '高等数学', SKJS: '张三', XNXQDM: '2026-2027-1', XF: '3.5',
    ZCMC: '1-16周', SKXQ: '1', KSJC: '3', JSJC: '4', JASMC: '逸夫楼A101',
  }]));
  assert.equal(courses.length, 1);
  assert.equal(courses[0].courseName, '高等数学');
  assert.equal(courses[0].credit, 3.5);
  assert.equal(courses[0].meetings[0].weekday, 1);
  assert.equal(courses[0].meetings[0].startSection, 3);
  assert.equal(courses[0].meetings[0].endSection, 4);
  assert.equal(courses[0].meetings[0].location, '逸夫楼A101');
});

test('splits multiple segments and parity', () => {
  const courses = parseSchedule(scheduleJson([{
    KCM: '大学物理', SKJS: '李四', XNXQDM_DISPLAY: '2026-2027学年第一学期', XF: 2,
    YPSJDD: '1-8周(单) 星期二 第1节-第2节 逸夫楼B201,9-16周(双) 星期四 第7节-第8节 中心校区C301',
    ZCMC: '1-16周', SKXQ: 2, KSJC: 1, JSJC: 2,
  }]));
  assert.equal(courses[0].semester, '2026-2027学年第一学期');
  assert.equal(courses[0].meetings.length, 2);
  assert.equal(courses[0].meetings[0].weekday, 2);
  assert.equal(courses[0].meetings[0].weekRules[0].parity, 'ODD');
  assert.equal(courses[0].meetings[1].weekday, 4);
  assert.equal(courses[0].meetings[1].weekRules[0].parity, 'EVEN');
});

test('supports discrete week list', () => {
  const courses = parseSchedule(scheduleJson([{ KCM: '体育', YPSJDD: '第1,3,5,7周 星期三 第3节-第4节 田径场' }]));
  const rules = courses[0].meetings[0].weekRules;
  assert.deepEqual(rules.map(r => r.startWeek), [1, 3, 5, 7]);
  assert.deepEqual(rules.map(r => r.endWeek), [1, 3, 5, 7]);
});

test('supports full-width parity with discrete weeks', () => {
  const courses = parseSchedule(scheduleJson([{ KCM: '英语', YPSJDD: '第1,3,5周（单） 星期一 第1节-第2节 外语楼' }]));
  const rules = courses[0].meetings[0].weekRules;
  assert.deepEqual(rules.map(r => r.startWeek), [1, 3, 5]);
  assert.ok(rules.every(r => r.parity === 'ODD'));
});

test('supports chinese comma segment separators', () => {
  const courses = parseSchedule(scheduleJson([{
    KCM: '数据结构',
    YPSJDD: '1-8周(单) 星期二 第1节-第2节 A楼，9-16周(双) 星期四 第7节-第8节 B楼',
  }]));
  assert.equal(courses[0].meetings.length, 2);
  assert.equal(courses[0].meetings[0].location, 'A楼');
  assert.equal(courses[0].meetings[0].weekRules[0].parity, 'ODD');
  assert.equal(courses[0].meetings[1].location, 'B楼');
  assert.equal(courses[0].meetings[1].weekRules[0].parity, 'EVEN');
});

test('supports compact section format', () => {
  const courses = parseSchedule(scheduleJson([{ KCM: '力学', YPSJDD: '1-16周 星期一 第1-2节 教三302' }]));
  assert.equal(courses[0].meetings[0].startSection, 1);
  assert.equal(courses[0].meetings[0].endSection, 2);
});

test('swaps reversed ranges', () => {
  const courses = parseSchedule(scheduleJson([{ KCM: '光学', YPSJDD: '16-1周 星期一 第4节-第2节 教室' }]));
  assert.equal(courses[0].meetings[0].startSection, 2);
  assert.equal(courses[0].meetings[0].endSection, 4);
  assert.equal(courses[0].meetings[0].weekRules[0].startWeek, 1);
  assert.equal(courses[0].meetings[0].weekRules[0].endWeek, 16);
});

test('defaults week rules when unparseable', () => {
  const courses = parseSchedule(scheduleJson([{ KCM: '选修', YPSJDD: '待定 星期五 第1节-第2节 教室' }]));
  const rule = courses[0].meetings[0].weekRules[0];
  assert.deepEqual([rule.startWeek, rule.endWeek, rule.parity], [1, 20, 'ALL']);
});

test('bare parity week text via fallback', () => {
  const courses = parseSchedule(scheduleJson([{ KCM: '实验', ZCMC: '双周', SKXQ: 3, KSJC: 5, JSJC: 6 }]));
  const rule = courses[0].meetings[0].weekRules[0];
  assert.deepEqual([rule.parity, rule.startWeek, rule.endWeek], ['EVEN', 1, 20]);
});

test('tolerates explicit nulls', () => {
  assert.equal(parseSchedule('{"datas": null}').length, 0);
  assert.equal(parseSchedule('{"datas": {"x": {"rows": null}}}').length, 0);
});

// ---- 周计算 ----
const course = (rules, weekday = 1) => ({
  courseName: '测试课程', teacher: '老师', semester: '2026-2027-1',
  credit: null, rawWeekText: 'custom',
  meetings: [{ weekday, startSection: 1, endSection: 2, weekRules: rules, location: '教室' }],
});

test('guess current week semantics', () => {
  const start = new SimpleDate(2026, 8, 31);
  assert.equal(guessCurrentWeek(start, start, 20), 1);
  assert.equal(guessCurrentWeek(start, start.adding(6), 20), 1);
  assert.equal(guessCurrentWeek(start, start.adding(7), 20), 2);
  assert.equal(guessCurrentWeek(start, start.adding(-30), 20), 1);
  assert.equal(guessCurrentWeek(start, start.adding(365), 20), 20);
});

test('total weeks', () => {
  assert.equal(totalWeeks([course([{ startWeek: 1, endWeek: 16, parity: 'ALL' }])]), 16);
  assert.equal(totalWeeks([]), 20);
});

test('parity rules', () => {
  const courses = [course([{ startWeek: 1, endWeek: 16, parity: 'ODD' }])];
  assert.equal(displayMeetings(courses, 1, false).filter(i => i.isCurrentWeek).length, 1);
  assert.equal(displayMeetings(courses, 2, false).filter(i => i.isCurrentWeek).length, 0);
  assert.equal(displayMeetings(courses, 3, false).filter(i => i.isCurrentWeek).length, 1);
});

test('past weeks show ended courses', () => {
  const courses = [course([{ startWeek: 1, endWeek: 5, parity: 'ODD' }])];
  const display = displayMeetings(courses, 4, true);
  assert.equal(display.length, 1);
  assert.equal(display[0].isCurrentWeek, false);
  assert.equal(display[0].nextActiveWeek, 5);
});

test('hides non current when disabled', () => {
  const courses = [course([{ startWeek: 6, endWeek: 10, parity: 'ALL' }])];
  assert.equal(displayMeetings(courses, 2, false).length, 0);
  assert.equal(displayMeetings(courses, 2, true).length, 1);
});

// ---- 学期日期 ----
test('semester label inference', () => {
  assert.equal(inferFromSemesterLabelOrNull('2026-2027学年第一学期').isoString(), '2026-08-31');
  assert.equal(inferFromSemesterLabelOrNull('2026-2027学年第二学期').isoString(), '2027-02-22');
  assert.equal(inferFromSemesterLabelOrNull('2026-2027学年秋季').isoString(), '2026-08-31');
  assert.equal(inferFromSemesterLabelOrNull('未知学期'), null);
});

test('normalize to week start', () => {
  assert.equal(normalizeToWeekStart(new SimpleDate(2026, 9, 2)).isoString(), '2026-08-31');
  assert.equal(normalizeToWeekStart(new SimpleDate(2026, 8, 31)).isoString(), '2026-08-31');
});

test('default for today branches', () => {
  assert.equal(defaultForToday(new SimpleDate(2026, 8, 15)).isoString(), '2026-08-31');
  assert.equal(defaultForToday(new SimpleDate(2026, 3, 15)).isoString(), '2026-02-23');
  assert.equal(defaultForToday(new SimpleDate(2027, 1, 10)).isoString(), '2027-02-22');
});

// ---- 备份编解码 ----
test('backup round trip', () => {
  const profiles = [{
    name: '主课表', semesterStartDate: '2026-08-31',
    courses: [courseToBackup(course([{ startWeek: 1, endWeek: 16, parity: 'ODD' }]))],
  }];
  const decoded = decodeBackup(encodeBackup('2026-09-05T00:00:00', profiles));
  assert.ok(decoded !== null);
  assert.equal(decoded.length, 1);
  assert.equal(decoded[0].courses[0].courseName, '测试课程');
  assert.equal(decoded[0].courses[0].meetings[0].weekRules[0].parity, 'ODD');
  const restored = backupToCourse(decoded[0].courses[0]);
  assert.equal(restored.meetings[0].weekday, 1);
  assert.equal(decoded[0].semesterStartDate, '2026-08-31');
  assert.equal(decodeBackup('not a json {'), null);
});

// ---- 导入缓存解析 ----
test('cache parser selects latest semester batch', () => {
  const entry = (sequence, name, semester) => ({
    url: 'https://x/cxxszhxqkb.do', fileName: sequence + '.do', sequence,
    content: scheduleJson([{ KCM: name, XNXQDM_DISPLAY: semester, ZCMC: '1-16周', SKXQ: 1, KSJC: 1, JSJC: 2 }]),
  });
  const result = parseCacheEntries([
    entry(1, '旧课', '2025-2026学年第一学期'),
    entry(2, '新课', '2026-2027学年第一学期'),
  ]);
  assert.deepEqual(result.courses.map(c => c.courseName), ['新课']);
  assert.equal(result.selectedSemester, '2026-2027学年第一学期');
  assert.equal(result.inferredSemesterStartDate?.isoString(), '2026-08-31');
});

test('cache parser rejects unrelated payloads', () => {
  const result = parseCacheEntries([{
    url: 'https://x/other.do', fileName: '0001.do', sequence: 1, content: 'x'.repeat(500),
  }]);
  assert.equal(result.courses.length, 0);
  assert.equal(result.rejectedEntries.length, 1);
});

for (const [status, name] of results) {
  console.log(status.padEnd(4), name);
}
console.log(results.every(r => r[0] === 'PASS') ? 'ALL PASS (' + results.length + ')' : 'HAS FAILURES');
`;

writeFileSync(testFile, testSource);

console.log('Running HarmonyOS core logic verification with Node type stripping...\n');
execFileSync(process.execPath, ['--experimental-strip-types', testFile], { stdio: 'inherit' });
