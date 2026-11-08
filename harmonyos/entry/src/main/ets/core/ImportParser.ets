/**
 * 导入缓存解析（对齐 Android ScheduleImportCacheParser / iOS ScheduleImportCacheParser）。
 * 纯 TS 语法子集，可在 Node 下验证。
 */
import {
  CourseSchedule, SimpleDate, parseSchedule, normalizedSemesterKey, inferFromCoursesOrNull,
} from './Core.ets';

export const TARGET_SCHEDULE_FILE = 'cxxszhxqkb.do';
export const MIN_SCHEDULE_PAYLOAD_BYTES = 80;

export interface CacheEntry {
  url: string;
  fileName: string;
  content: string;
  sequence: number;
}

export interface RejectedEntry {
  entry: CacheEntry;
  reason: string;
}

export interface ParseResult {
  courses: CourseSchedule[];
  selectedSemester: string;
  inferredSemesterStartDate: SimpleDate | null;
  scannedEntries: number;
  parsedBatchCount: number;
  rejectedEntries: RejectedEntry[];
}

interface Batch {
  entry: CacheEntry;
  courses: CourseSchedule[];
  semesterKey: string;
  semesterLabel: string;
}

export function isLikelyScheduleUrl(url: string): boolean {
  const lower = url.toLowerCase();
  return lower.includes(TARGET_SCHEDULE_FILE) || lower.includes('modules/xskcb');
}

export function looksLikeSchedulePayload(url: string, text: string, size: number): boolean {
  if (size < MIN_SCHEDULE_PAYLOAD_BYTES) return false;
  if (!isLikelyScheduleUrl(url)) return false;
  if (!text.includes('"datas"') || !text.includes('"rows"')) return false;
  const hasCourseField = text.includes('"KCM"');
  const hasTimeField = text.includes('"YPSJDD"') || text.includes('"SKXQ"');
  return hasCourseField && hasTimeField;
}

function semesterKey(label: string): string {
  const raw = label.trim();
  if (raw.length === 0) return '';
  return normalizedSemesterKey(raw) ?? raw;
}

function dominantSemesterKey(courses: CourseSchedule[]): string {
  const counts = new Map<string, number>();
  for (const course of courses) {
    const key = semesterKey(course.semester);
    if (key.length > 0) counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  let dominant = '';
  let best = 0;
  for (const key of counts.keys()) {
    const count = counts.get(key) ?? 0;
    if (count > best) { best = count; dominant = key; }
  }
  return dominant;
}

function dominantSemesterLabel(courses: CourseSchedule[]): string {
  const key = dominantSemesterKey(courses);
  if (key.length === 0) return '';
  const counts = new Map<string, number>();
  for (const course of courses) {
    const label = course.semester.trim();
    if (label.length > 0 && semesterKey(label) === key) {
      counts.set(label, (counts.get(label) ?? 0) + 1);
    }
  }
  let dominant = '';
  let best = 0;
  for (const label of counts.keys()) {
    const count = counts.get(label) ?? 0;
    if (count > best) { best = count; dominant = label; }
  }
  return dominant;
}

function prioritize(entries: CacheEntry[]): CacheEntry[] {
  const score = (entry: CacheEntry): number[] => {
    const lowerUrl = entry.url.toLowerCase();
    const lowerName = entry.fileName.toLowerCase();
    return [
      lowerUrl.includes(TARGET_SCHEDULE_FILE) ? 1 : 0,
      lowerUrl.includes('modules/xskcb') ? 1 : 0,
      (lowerName.includes('.do') || lowerName.includes('.json')) ? 1 : 0,
      entry.sequence,
      entry.content.length,
    ];
  };
  return [...entries].sort((a, b) => {
    const sa = score(a);
    const sb = score(b);
    for (let i = 0; i < sa.length; i++) {
      if (sa[i] !== sb[i]) return sb[i] - sa[i];
    }
    return 0;
  });
}

export function parseCacheEntries(entries: CacheEntry[]): ParseResult {
  const candidates = prioritize(entries);
  const batches: Batch[] = [];
  const rejected: RejectedEntry[] = [];

  for (const entry of candidates) {
    const size = entry.content.length;
    if (size <= 0) { rejected.push({ entry, reason: '文件为空' }); continue; }
    if (size > 2 * 1024 * 1024) { rejected.push({ entry, reason: '文件过大' }); continue; }
    if (!looksLikeSchedulePayload(entry.url, entry.content, size)) {
      rejected.push({ entry, reason: '不是课表响应' }); continue;
    }
    let parsed: CourseSchedule[];
    try {
      parsed = parseSchedule(entry.content);
    } catch {
      rejected.push({ entry, reason: '解析失败' }); continue;
    }
    const valid = parsed.filter((c) => c.courseName.trim().length > 0 && c.meetings.length > 0);
    if (valid.length === 0) { rejected.push({ entry, reason: '课表为空' }); continue; }
    batches.push({
      entry,
      courses: valid,
      semesterKey: dominantSemesterKey(valid),
      semesterLabel: dominantSemesterLabel(valid),
    });
  }

  if (batches.length === 0) {
    return {
      courses: [], selectedSemester: '', inferredSemesterStartDate: null,
      scannedEntries: candidates.length, parsedBatchCount: 0, rejectedEntries: rejected,
    };
  }

  let latest = batches[0];
  for (const batch of batches) {
    if (batch.entry.sequence > latest.entry.sequence) latest = batch;
  }
  const selectedBatches = latest.semesterKey.length === 0
    ? [latest]
    : batches.filter((b) => b.semesterKey === latest.semesterKey);
  const ordered = [...selectedBatches].sort((a, b) => a.entry.sequence - b.entry.sequence);
  let selectedCourses: CourseSchedule[] = [];
  for (const batch of ordered) selectedCourses = selectedCourses.concat(batch.courses);
  if (latest.semesterKey.length > 0) {
    selectedCourses = selectedCourses.filter((course) => {
      const key = semesterKey(course.semester);
      return key.length === 0 || key === latest.semesterKey;
    });
  }

  return {
    courses: selectedCourses,
    selectedSemester: latest.semesterLabel.length > 0 ? latest.semesterLabel : latest.semesterKey,
    inferredSemesterStartDate: inferFromCoursesOrNull(selectedCourses),
    scannedEntries: candidates.length,
    parsedBatchCount: batches.length,
    rejectedEntries: rejected,
  };
}
