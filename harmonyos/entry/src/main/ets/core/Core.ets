/**
 * 课表核心逻辑（与 Android cn.jlu.schedule 的 model/parser/domain 逐一对应）。
 * 使用 TS 可剥离语法书写，便于在 Node 环境下运行单元验证（见 verify/ 目录）。
 */

// MARK: 常量（星期 1=周一 ... 7=周日；单双周为字符串常量）
export const WEEK_MONDAY = 1;
export const WEEK_SUNDAY = 7;
export const PARITY_ALL = 'ALL';
export const PARITY_ODD = 'ODD';
export const PARITY_EVEN = 'EVEN';
export const DEFAULT_TOTAL_WEEKS = 20;
export const MAX_WEEK = 30;

export interface WeekRule {
  startWeek: number;
  endWeek: number;
  parity: string;
}

export interface MeetingTime {
  weekday: number;
  startSection: number;
  endSection: number;
  weekRules: WeekRule[];
  location: string;
}

export interface CourseSchedule {
  courseName: string;
  teacher: string;
  semester: string;
  credit: number | null;
  rawWeekText: string;
  meetings: MeetingTime[];
}

// MARK: 简单日期（语义对齐 java.time.LocalDate / Swift SimpleDate）

export class SimpleDate {
  readonly year: number;
  readonly month: number;
  readonly day: number;

  constructor(year: number, month: number, day: number) {
    this.year = year;
    this.month = month;
    this.day = day;
  }

  static today(): SimpleDate {
    const now = new Date();
    return new SimpleDate(now.getFullYear(), now.getMonth() + 1, now.getDate());
  }

  static parse(text: string): SimpleDate | null {
    const parts = text.split('-');
    if (parts.length !== 3) return null;
    const y = Number(parts[0]);
    const m = Number(parts[1]);
    const d = Number(parts[2]);
    if (!Number.isInteger(y) || !Number.isInteger(m) || !Number.isInteger(d)) return null;
    if (y <= 0 || m <= 0 || d <= 0) return null;
    return new SimpleDate(y, m, d);
  }

  private utc(): number {
    return Date.UTC(this.year, this.month - 1, this.day, 12, 0, 0);
  }

  /** 周一=1 ... 周日=7 */
  weekdayMonBased(): number {
    const wd = new Date(this.utc()).getUTCDay(); // 0=Sun..6=Sat
    return wd === 0 ? 7 : wd;
  }

  weekday(): number {
    return this.weekdayMonBased();
  }

  days(since: SimpleDate): number {
    return Math.round((this.utc() - since.utc()) / 86400000);
  }

  adding(days: number): SimpleDate {
    const d = new Date(this.utc() + days * 86400000);
    return new SimpleDate(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate());
  }

  previousOrSameMonday(): SimpleDate {
    return this.adding(-(this.weekdayMonBased() - 1));
  }

  isoString(): string {
    const mm = String(this.month).padStart(2, '0');
    const dd = String(this.day).padStart(2, '0');
    return `${this.year}-${mm}-${dd}`;
  }

  displayText(): string {
    return `${this.year}/${this.month}/${this.day}`;
  }

  equals(other: SimpleDate): boolean {
    return this.year === other.year && this.month === other.month && this.day === other.day;
  }
}

// MARK: 周次计算（对齐 WeekScheduleCalculator）

export function totalWeeks(of: CourseSchedule[]): number {
  let maxWeek: number | null = null;
  for (const course of of) {
    for (const meeting of course.meetings) {
      for (const rule of meeting.weekRules) {
        if (maxWeek === null || rule.endWeek > maxWeek) maxWeek = rule.endWeek;
      }
    }
  }
  return Math.max(maxWeek ?? DEFAULT_TOTAL_WEEKS, 1);
}

export function guessCurrentWeek(semesterStart: SimpleDate, today: SimpleDate, weeks: number): number {
  const days = today.days(semesterStart);
  const week = Math.floor(days / 7) + 1;
  return Math.min(Math.max(week, 1), Math.max(weeks, 1));
}

export function isMeetingActiveInWeek(meeting: MeetingTime, week: number): boolean {
  if (meeting.weekRules.length === 0) return true;
  return meeting.weekRules.some((rule) => {
    if (week < rule.startWeek || week > rule.endWeek) return false;
    if (rule.parity === PARITY_ODD) return week % 2 === 1;
    if (rule.parity === PARITY_EVEN) return week % 2 === 0;
    return true;
  });
}

export function nextActiveWeekOf(meeting: MeetingTime, fromWeek: number): number | null {
  if (meeting.weekRules.length === 0) return fromWeek;
  let maxEnd: number | null = null;
  for (const rule of meeting.weekRules) {
    if (maxEnd === null || rule.endWeek > maxEnd) maxEnd = rule.endWeek;
  }
  if (maxEnd === null || fromWeek > maxEnd) return null;
  for (let w = fromWeek; w <= maxEnd; w++) {
    if (isMeetingActiveInWeek(meeting, w)) return w;
  }
  return null;
}

export interface CourseMeetingRef {
  courseIndex: number;
  course: CourseSchedule;
  meeting: MeetingTime;
  isCurrentWeek: boolean;
  nextActiveWeek: number;
}

export function meetingsInWeek(courses: CourseSchedule[], week: number): CourseMeetingRef[] {
  const result: CourseMeetingRef[] = [];
  courses.forEach((course, index) => {
    for (const meeting of course.meetings) {
      if (isMeetingActiveInWeek(meeting, week)) {
        result.push({ courseIndex: index, course, meeting, isCurrentWeek: true, nextActiveWeek: week });
      }
    }
  });
  return result;
}

/** 周视图：非本周课程从被查看周向后寻找下一次上课周（历史周可见已结课课程） */
export function displayMeetings(courses: CourseSchedule[], week: number, showNonCurrent: boolean): CourseMeetingRef[] {
  const result: CourseMeetingRef[] = [];
  courses.forEach((course, index) => {
    for (const meeting of course.meetings) {
      if (isMeetingActiveInWeek(meeting, week)) {
        result.push({ courseIndex: index, course, meeting, isCurrentWeek: true, nextActiveWeek: week });
        continue;
      }
      if (!showNonCurrent) continue;
      const next = nextActiveWeekOf(meeting, week);
      if (next === null) continue;
      result.push({ courseIndex: index, course, meeting, isCurrentWeek: false, nextActiveWeek: next });
    }
  });
  return result;
}

/** 冲突课程去重（对齐 Android resolveOverlapForDay） */
export function resolveOverlaps(items: CourseMeetingRef[]): CourseMeetingRef[] {
  const occupied = new Array<boolean>(13).fill(false);
  const sorted = [...items].sort((a, b) => {
    if (a.isCurrentWeek !== b.isCurrentWeek) return a.isCurrentWeek ? -1 : 1;
    if (a.nextActiveWeek !== b.nextActiveWeek) return a.nextActiveWeek - b.nextActiveWeek;
    return a.meeting.startSection - b.meeting.startSection;
  });
  const selected: CourseMeetingRef[] = [];
  for (const item of sorted) {
    const start = Math.min(Math.max(item.meeting.startSection, 1), 12);
    const end = Math.min(Math.max(item.meeting.endSection, 1), 12);
    let overlap = false;
    for (let s = start; s <= end; s++) {
      if (occupied[s]) { overlap = true; break; }
    }
    if (overlap) continue;
    selected.push(item);
    for (let s = start; s <= end; s++) occupied[s] = true;
  }
  return selected.sort((a, b) => a.meeting.startSection - b.meeting.startSection);
}

// MARK: 学期开始日期推断（对齐 SemesterStartDatePolicy）

const codePattern = /(20\d{2})\D+(20\d{2})\D*([12])/;
const yearRangePattern = /(20\d{2})\D+(20\d{2})/;

export function normalizedSemesterKey(label: string): string | null {
  const normalized = label.trim();
  if (normalized.length === 0) return null;

  const codeMatch = codePattern.exec(normalized);
  if (codeMatch) {
    return `${codeMatch[1]}-${codeMatch[2]}-${codeMatch[3]}`;
  }
  const rangeMatch = yearRangePattern.exec(normalized);
  if (rangeMatch) {
    let term: string | null = null;
    if (normalized.includes('第一') || normalized.includes('第1') || normalized.includes('秋')) term = '1';
    else if (normalized.includes('第二') || normalized.includes('第2') || normalized.includes('春')) term = '2';
    if (term !== null) return `${rangeMatch[1]}-${rangeMatch[2]}-${term}`;
  }
  return null;
}

function mondayOnOrBefore(year: number, month: number, day: number): SimpleDate {
  return new SimpleDate(year, month, day).previousOrSameMonday();
}

export function inferFromSemesterLabelOrNull(label: string): SimpleDate | null {
  const key = normalizedSemesterKey(label);
  if (key === null) return null;
  const parts = key.split('-');
  if (parts.length !== 3) return null;
  const firstYear = Number(parts[0]);
  const secondYear = Number(parts[1]);
  const term = parts[2];
  if (term === '1') return mondayOnOrBefore(firstYear, 9, 1);
  if (term === '2') return mondayOnOrBefore(secondYear, 2, 24);
  return null;
}

export function inferFromCoursesOrNull(courses: CourseSchedule[]): SimpleDate | null {
  const counts = new Map<string, number>();
  for (const course of courses) {
    const label = course.semester.trim();
    if (label.length === 0) continue;
    counts.set(label, (counts.get(label) ?? 0) + 1);
  }
  let dominant: string | null = null;
  let best = 0;
  for (const label of counts.keys()) {
    const count = counts.get(label) ?? 0;
    if (count > best) {
      best = count;
      dominant = label;
    }
  }
  if (dominant === null) return null;
  return inferFromSemesterLabelOrNull(dominant);
}

export function inferFromCourses(courses: CourseSchedule[], fallback?: SimpleDate): SimpleDate {
  return inferFromCoursesOrNull(courses) ?? fallback ?? defaultForToday();
}

export function defaultForToday(today: SimpleDate = SimpleDate.today()): SimpleDate {
  if (today.month >= 8) {
    return mondayOnOrBefore(today.year, 9, 1);
  }
  return mondayOnOrBefore(today.year, 2, 24);
}

export function normalizeToWeekStart(date: SimpleDate): SimpleDate {
  return date.previousOrSameMonday();
}

// MARK: 教务 .do 解析（对齐 DoScheduleParser）

const segmentRegex = /^\s*(.*?)\s*星期([一二三四五六日天])\s*第\s*(\d+)\s*节?\s*[-—–~～至]\s*第?\s*(\d+)\s*节?\s*(.*)$/;
const weekItemRegex = /第?(\d+)\s*(?:-\s*(\d+))?\s*周(?:\s*[（(]\s*(单|双)\s*[)）]|\s*[单双])?/;
const discreteWeeksRegex = /^第?\s*(\d+(?:\s*[,，、]\s*\d+)+)\s*周?\s*(?:[（(]\s*(单|双)\s*[)）])?\s*周?$/;

function asText(value: object | null | undefined): string {
  if (value === null || value === undefined) return '';
  return String(value);
}

function asInt(value: object | null | undefined): number | null {
  if (value === null || value === undefined) return null;
  const n = Number(value);
  return Number.isFinite(n) ? Math.trunc(n) : null;
}

function asDouble(value: object | null | undefined): number | null {
  if (value === null || value === undefined) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

export function parseWeekRules(weekExpr: string): WeekRule[] {
  const expr = weekExpr.trim();
  if (expr.length === 0) {
    return [{ startWeek: 1, endWeek: DEFAULT_TOTAL_WEEKS, parity: PARITY_ALL }];
  }
  let hasDigit = false;
  for (const ch of expr) {
    if (ch >= '0' && ch <= '9') { hasDigit = true; break; }
  }
  if (!hasDigit) {
    const parity = expr.includes('单') ? PARITY_ODD : (expr.includes('双') ? PARITY_EVEN : PARITY_ALL);
    return [{ startWeek: 1, endWeek: DEFAULT_TOTAL_WEEKS, parity }];
  }

  const discrete = discreteWeeksRegex.exec(expr);
  if (discrete) {
    const parity = discrete[2] === '单' ? PARITY_ODD : (discrete[2] === '双' ? PARITY_EVEN : PARITY_ALL);
    const weeks = discrete[1]
      .split(/[,，、]/)
      .map((s) => Number(s.trim()))
      .filter((n) => Number.isInteger(n) && n >= 1 && n <= MAX_WEEK);
    if (weeks.length > 0) {
      return weeks.map((w) => ({ startWeek: w, endWeek: w, parity }));
    }
  }

  const rules = expr
    .split(/[,，、;；]/)
    .map((part) => parseWeekItem(part))
    .filter((rule): rule is WeekRule => rule !== null);
  return rules.length === 0 ? [{ startWeek: 1, endWeek: DEFAULT_TOTAL_WEEKS, parity: PARITY_ALL }] : rules;
}

function parseWeekItem(part: string): WeekRule | null {
  const match = weekItemRegex.exec(part.trim());
  if (!match) return null;
  const start = Number(match[1]);
  const end = match[2] !== undefined ? Number(match[2]) : start;
  const parity = match[3] === '单' ? PARITY_ODD : (match[3] === '双' ? PARITY_EVEN : PARITY_ALL);
  if (!Number.isInteger(start) || !Number.isInteger(end)) return null;
  if (start < 1 || start > MAX_WEEK || end < 1 || end > MAX_WEEK) return null;
  return start <= end
    ? { startWeek: start, endWeek: end, parity }
    : { startWeek: end, endWeek: start, parity };
}

interface RowMap {
  [key: string]: object | null | undefined;
}

function splitSegments(text: string): string[] {
  const pieces = text.split(/[,，;；\n]/).map((s) => s.trim());
  const result: string[] = [];
  let buffer = '';
  for (const piece of pieces) {
    if (buffer.length > 0) buffer += '，';
    buffer += piece;
    if (buffer.includes('星期') && buffer.includes('节')) {
      result.push(buffer.trim());
      buffer = '';
    }
  }
  if (buffer.length > 0) result.push(buffer.trim());
  return result.filter((s) => s.length > 0);
}

function weekdayFromChinese(value: string): number | null {
  switch (value) {
    case '一': return 1;
    case '二': return 2;
    case '三': return 3;
    case '四': return 4;
    case '五': return 5;
    case '六': return 6;
    case '日': case '天': return 7;
    default: return null;
  }
}

function parseSegment(segment: string, row: RowMap): MeetingTime | null {
  const match = segmentRegex.exec(segment);
  if (!match) return null;
  const weekday = weekdayFromChinese(match[2]);
  const start = Number(match[3]);
  const end = Number(match[4]);
  if (weekday === null || !Number.isInteger(start) || !Number.isInteger(end)) return null;
  let location = match[5].trim();
  if (location.length === 0) {
    location = asText(row['JASMC']).length > 0 ? asText(row['JASMC']) : asText(row['JXLDM_DISPLAY']);
  }
  return {
    weekday,
    startSection: Math.min(start, end),
    endSection: Math.max(start, end),
    weekRules: parseWeekRules(match[1].trim()),
    location,
  };
}

function fallbackMeeting(row: RowMap): MeetingTime | null {
  const weekdayNumber = asInt(row['SKXQ']);
  const start = asInt(row['KSJC']);
  const end = asInt(row['JSJC']);
  if (weekdayNumber === null || start === null || end === null) return null;
  if (weekdayNumber < 1 || weekdayNumber > 7) return null;
  const location = asText(row['JASMC']).length > 0 ? asText(row['JASMC']) : asText(row['JXLDM_DISPLAY']);
  return {
    weekday: weekdayNumber,
    startSection: Math.min(start, end),
    endSection: Math.max(start, end),
    weekRules: parseWeekRules(asText(row['ZCMC'])),
    location,
  };
}

function meetingsOfRow(row: RowMap): MeetingTime[] {
  const arranged = asText(row['YPSJDD']).trim();
  if (arranged.length === 0) {
    const fallback = fallbackMeeting(row);
    return fallback ? [fallback] : [];
  }
  const parsed = splitSegments(arranged)
    .map((segment) => parseSegment(segment, row))
    .filter((m): m is MeetingTime => m !== null);
  if (parsed.length === 0) {
    const fallback = fallbackMeeting(row);
    return fallback ? [fallback] : [];
  }
  return parsed;
}

export function parseSchedule(rawContent: string): CourseSchedule[] {
  let root: object;
  try {
    root = JSON.parse(rawContent) as object;
  } catch {
    throw new Error('invalid json');
  }
  const holder = root as Record<string, object | null>;
  const datas = holder['datas'] as Record<string, Record<string, object | null>> | null;
  if (!datas) return [];

  const rows: RowMap[] = [];
  for (const key of Object.keys(datas)) {
    const table = datas[key];
    if (!table) continue;
    const tableRows = table['rows'];
    if (Array.isArray(tableRows)) {
      for (const row of tableRows) {
        if (row && typeof row === 'object') rows.push(row as RowMap);
      }
    }
  }

  const seen = new Set<string>();
  const unique: RowMap[] = [];
  for (const row of rows) {
    const key = [asText(row['KCM']), asText(row['SKJS']), asText(row['YPSJDD']), asText(row['ZCMC']),
      asText(row['SKXQ']), asText(row['KSJC']), asText(row['JSJC']),
      asText(row['JASMC']), asText(row['JXLDM_DISPLAY'])].join('|');
    if (!seen.has(key)) {
      seen.add(key);
      unique.push(row);
    }
  }

  return unique.map((row) => {
    const display = asText(row['XNXQDM_DISPLAY']).trim();
    const semester = display.length > 0 ? display : asText(row['XNXQDM']).trim();
    return {
      courseName: asText(row['KCM']),
      teacher: asText(row['SKJS']),
      semester,
      credit: asDouble(row['XF']),
      rawWeekText: asText(row['ZCMC']),
      meetings: meetingsOfRow(row),
    } as CourseSchedule;
  });
}

// MARK: 备份编解码（对齐 ScheduleBackupCodec，version 1）

export interface BackupWeekRule { startWeek: number; endWeek: number; parity: string; }
export interface BackupMeeting {
  weekday: string;
  startSection: number;
  endSection: number;
  weekRules: BackupWeekRule[];
  location: string;
}
export interface BackupCourse {
  courseName: string;
  teacher: string;
  semester: string;
  credit: number | null;
  rawWeekText: string;
  meetings: BackupMeeting[];
}
export interface BackupProfile {
  name: string;
  semesterStartDate: string | null;
  courses: BackupCourse[];
}
export interface BackupFile {
  version: number;
  exportedAt: string;
  profiles: BackupProfile[];
}

export function encodeBackup(exportedAt: string, profiles: BackupProfile[]): string {
  const file: BackupFile = { version: 1, exportedAt, profiles };
  return JSON.stringify(file, null, 2);
}

export function decodeBackup(content: string): BackupProfile[] | null {
  try {
    const file = JSON.parse(content) as BackupFile;
    if (file.version !== 1 || !Array.isArray(file.profiles)) return null;
    return file.profiles;
  } catch {
    return null;
  }
}

export function backupToCourse(backup: BackupCourse): CourseSchedule {
  return {
    courseName: backup.courseName,
    teacher: backup.teacher,
    semester: backup.semester,
    credit: backup.credit,
    rawWeekText: backup.rawWeekText,
    meetings: backup.meetings.map((m) => ({
      weekday: Number(m.weekday) || 1,
      startSection: m.startSection,
      endSection: m.endSection,
      weekRules: (m.weekRules ?? []).map((r) => ({
        startWeek: r.startWeek, endWeek: r.endWeek, parity: r.parity,
      })),
      location: m.location,
    })),
  };
}

export function courseToBackup(course: CourseSchedule): BackupCourse {
  return {
    courseName: course.courseName,
    teacher: course.teacher,
    semester: course.semester,
    credit: course.credit,
    rawWeekText: course.rawWeekText,
    meetings: course.meetings.map((m) => ({
      weekday: String(m.weekday),
      startSection: m.startSection,
      endSection: m.endSection,
      weekRules: m.weekRules.map((r) => ({
        startWeek: r.startWeek, endWeek: r.endWeek, parity: r.parity,
      })),
      location: m.location,
    })),
  };
}

// MARK: 应用统一节次-时间对照（对齐 SectionTimes）

export const SECTION_RANGES: string[] = [
  '08:00-08:45', '08:45-09:40', '10:00-10:45', '10:45-11:40',
  '13:30-14:15', '14:15-15:10', '15:30-16:15', '16:15-17:10',
  '18:20-19:05', '19:05-19:50', '20:00-20:45', '20:45-21:30',
];
