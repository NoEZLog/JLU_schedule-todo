/**
 * 多课表持久化（语义对齐 Android ImportedScheduleStorage / iOS ProfileStore）。
 * 依赖 @ohos.file.fs，ArkUI/DevEco 环境运行；核心解析逻辑在 Core.ets（可 Node 验证）。
 */
import { fs } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import {
  CourseSchedule, MeetingTime, WeekRule, SimpleDate,
  parseSchedule,
  inferFromCourses, defaultForToday, normalizeToWeekStart,
  decodeBackup, encodeBackup, backupToCourse, courseToBackup, BackupProfile,
} from './Core.ets';

const DEFAULT_PROFILE_NAME = '默认课表';

export enum ImportMode {
  OVERWRITE_ACTIVE = 0,
  CREATE_NEW = 1,
}

export class ProfileInfo {
  id: string = '';
  name: string = '';
  isActive: boolean = false;
  updatedAt: number = 0;
  semesterStart: SimpleDate = defaultForToday();
}

export class ImportResult {
  profileId: string = '';
  profileName: string = '';
  courseCount: number = 0;
  isNewProfile: boolean = false;
  semesterStartDate: SimpleDate | null = null;
}

export class ManualCourseInput {
  courseName: string = '';
  teacher: string = '';
  location: string = '';
  weekday: number = 1;
  startSection: number = 1;
  endSection: number = 2;
  startWeek: number = 1;
  endWeek: number = 1;
  parity: string = 'ALL';
  semester: string = '手动添加';
}

interface StoredProfileMeta {
  id: string;
  name: string;
  coursesFile: string;
  createdAt: number;
  updatedAt: number;
  semesterStartDate?: string;
}

interface StoredMeta {
  activeId: string;
  profiles: StoredProfileMeta[];
}

function fail(message: string): never {
  throw new Error(message);
}

export class ProfileStore {
  static instance: ProfileStore | null = null;

  private dir: string = '';

  static init(context: common.UIAbilityContext): void {
    ProfileStore.instance = new ProfileStore(context.filesDir);
  }

  constructor(filesDir: string) {
    this.dir = filesDir + '/timetables';
    if (!fs.accessSync(this.dir)) {
      fs.mkdirSync(this.dir, true);
    }
  }

  private decodeUtf8(buffer: ArrayBuffer): string {
    return util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buffer));
  }

  // MARK: 路径

  private metaPath(): string {
    return this.dir + '/meta.json';
  }

  private coursesPath(fileName: string): string {
    return this.dir + '/' + fileName;
  }

  // MARK: 读写

  private readMeta(): StoredMeta | null {
    const path = this.metaPath();
    if (!fs.accessSync(path)) return null;
    try {
      const stat = fs.statSync(path);
      if (stat.size <= 0) return null;
      const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
      const buf = new ArrayBuffer(stat.size);
      fs.readSync(file.fd, buf);
      fs.closeSync(file);
      const text = util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buf));
      return JSON.parse(text) as StoredMeta;
    } catch {
      // meta 损坏：保留现场，交由 salvage 恢复
      try {
        fs.renameSync(path, this.dir + '/meta.json.corrupt-' + Date.now());
      } catch {
        // 忽略重命名失败
      }
      return null;
    }
  }

  private writeMeta(meta: StoredMeta): void {
    this.writeTextAtomic(this.metaPath(), JSON.stringify(meta, null, 2));
  }

  private writeTextAtomic(path: string, content: string): void {
    const tmp = path + '.' + Date.now() + '.tmp';
    const file = fs.openSync(tmp, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    fs.writeSync(file.fd, content);
    fs.closeSync(file);
    fs.renameSync(tmp, path);
  }

  private readCoursesFile(fileName: string): CourseSchedule[] {
    const path = this.coursesPath(fileName);
    if (!fs.accessSync(path)) return [];
    try {
      const stat = fs.statSync(path);
      if (stat.size <= 0) return [];
      const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
      const buf = new ArrayBuffer(stat.size);
      fs.readSync(file.fd, buf);
      fs.closeSync(file);
      const text = util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buf));
      const persisted = JSON.parse(text) as PersistedCourseJSON[];
      return persisted.map((p) => this.toCourseSchedule(p));
    } catch {
      return [];
    }
  }

  private writeCoursesFile(fileName: string, courses: CourseSchedule[]): void {
    const persisted = courses.map((c) => this.toPersisted(c));
    this.writeTextAtomic(this.coursesPath(fileName), JSON.stringify(persisted, null, 2));
  }

  private toPersisted(course: CourseSchedule): PersistedCourseJSON {
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
        weekRules: m.weekRules.map((r) => ({ startWeek: r.startWeek, endWeek: r.endWeek, parity: r.parity })),
        location: m.location,
      })),
    };
  }

  private toCourseSchedule(p: PersistedCourseJSON): CourseSchedule {
    return {
      courseName: p.courseName,
      teacher: p.teacher,
      semester: p.semester,
      credit: p.credit,
      rawWeekText: p.rawWeekText,
      meetings: (p.meetings ?? []).map((m) => ({
        weekday: Number(m.weekday) || 1,
        startSection: m.startSection,
        endSection: m.endSection,
        weekRules: (m.weekRules ?? []).map((r) => ({
          startWeek: r.startWeek, endWeek: r.endWeek,
          parity: r.parity,
        })),
        location: m.location,
      })),
    };
  }

  // MARK: 初始化 / 恢复

  private ensureInitialized(): StoredMeta {
    const meta = this.readMeta();
    if (meta !== null && meta.profiles.length > 0) {
      const migrated = this.fillMissingSemesterStarts(meta);
      this.writeMeta(migrated);
      return migrated;
    }
    const salvaged = this.salvageOrphans();
    if (salvaged !== null) {
      this.writeMeta(salvaged);
      return salvaged;
    }
    return this.initializeDefault();
  }

  private salvageOrphans(): StoredMeta | null {
    let names: string[] = [];
    try {
      names = fs.listFileSync(this.dir);
    } catch {
      return null;
    }
    const orphanFiles = names
      .filter((name) => name.startsWith('courses_') && name.endsWith('.json'))
      .sort((a, b) => this.fileMtime(b) - this.fileMtime(a));
    if (orphanFiles.length === 0) return null;

    const profiles: StoredProfileMeta[] = [];
    for (let i = 0; i < orphanFiles.length; i++) {
      const fileName = orphanFiles[i];
      const id = fileName.substring('courses_'.length, fileName.length - '.json'.length);
      if (id.length === 0) continue;
      const courses = this.readCoursesFile(fileName);
      profiles.push({
        id,
        name: i === 0 ? DEFAULT_PROFILE_NAME : `恢复课表${i + 1}`,
        coursesFile: fileName,
        createdAt: this.fileMtime(fileName),
        updatedAt: this.fileMtime(fileName),
        semesterStartDate: inferFromCourses(courses).isoString(),
      });
    }
    if (profiles.length === 0) return null;
    return { activeId: profiles[0].id, profiles };
  }

  private fileMtime(fileName: string): number {
    try {
      return fs.statSync(this.coursesPath(fileName)).mtime;
    } catch {
      return 0;
    }
  }

  private initializeDefault(): StoredMeta {
    const id = this.randomUUID();
    const fileName = `courses_${id}.json`;
    const courses = this.loadLegacyCourses();
    this.writeCoursesFile(fileName, courses);
    const now = Date.now();
    const meta: StoredMeta = {
      activeId: id,
      profiles: [{
        id, name: DEFAULT_PROFILE_NAME, coursesFile: fileName,
        createdAt: now, updatedAt: now,
        semesterStartDate: inferFromCourses(courses).isoString(),
      }],
    };
    this.writeMeta(meta);
    return meta;
  }

  private loadLegacyCourses(): CourseSchedule[] {
    const legacy = this.dir.replace('/timetables', '') + '/imported_schedule.do';
    if (!fs.accessSync(legacy)) return [];
    try {
      const stat = fs.statSync(legacy);
      const file = fs.openSync(legacy, fs.OpenMode.READ_ONLY);
      const buf = new ArrayBuffer(stat.size);
      fs.readSync(file.fd, buf);
      fs.closeSync(file);
      const text = util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buf));
      return parseSchedule(text);
    } catch {
      return [];
    } finally {
      try {
        fs.unlinkSync(legacy);
      } catch {
        // 忽略删除失败
      }
    }
  }

  private fillMissingSemesterStarts(meta: StoredMeta): StoredMeta {
    let changed = false;
    for (const profile of meta.profiles) {
      if (profile.semesterStartDate === undefined || profile.semesterStartDate.length === 0) {
        profile.semesterStartDate = inferFromCourses(this.readCoursesFile(profile.coursesFile)).isoString();
        changed = true;
      }
    }
    return meta;
  }

  private randomUUID(): string {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (ch) => {
      const rand = Math.trunc(Math.random() * 16);
      const value = ch === 'x' ? rand : (rand & 0x3) | 0x8;
      return value.toString(16);
    });
  }

  private semesterStartOf(profile: StoredProfileMeta): SimpleDate {
    if (profile.semesterStartDate !== undefined) {
      const parsed = SimpleDate.parse(profile.semesterStartDate);
      if (parsed !== null) return normalizeToWeekStart(parsed);
    }
    return defaultForToday();
  }

  // MARK: 公开 API

  listProfiles(): ProfileInfo[] {
    const meta = this.ensureInitialized();
    return meta.profiles.map((p) => ({
      id: p.id,
      name: p.name,
      isActive: p.id === meta.activeId,
      updatedAt: p.updatedAt,
      semesterStart: this.semesterStartOf(p),
    } as ProfileInfo));
  }

  setActiveProfile(profileId: string): boolean {
    const meta = this.ensureInitialized();
    if (!meta.profiles.some((p) => p.id === profileId)) return false;
    meta.activeId = profileId;
    this.writeMeta(meta);
    return true;
  }

  createEmptyProfile(name: string): ProfileInfo {
    const meta = this.ensureInitialized();
    const now = Date.now();
    const id = this.randomUUID();
    const trimmed = name.trim();
    const profileName = trimmed.length > 0 ? trimmed : `课表${this.timestampLabel()}`;
    const fileName = `courses_${id}.json`;
    this.writeCoursesFile(fileName, []);
    const start = defaultForToday();
    meta.profiles.push({
      id, name: profileName, coursesFile: fileName,
      createdAt: now, updatedAt: now, semesterStartDate: start.isoString(),
    });
    meta.activeId = id;
    this.writeMeta(meta);
    const info = new ProfileInfo();
    info.id = id; info.name = profileName; info.isActive = true;
    info.updatedAt = now; info.semesterStart = start;
    return info;
  }

  renameProfile(profileId: string, newName: string): boolean {
    const trimmed = newName.trim();
    if (trimmed.length === 0) return false;
    const meta = this.ensureInitialized();
    const target = meta.profiles.find((p) => p.id === profileId);
    if (target === undefined) return false;
    target.name = trimmed;
    target.updatedAt = Date.now();
    this.writeMeta(meta);
    return true;
  }

  deleteProfile(profileId: string): boolean {
    const meta = this.ensureInitialized();
    const target = meta.profiles.find((p) => p.id === profileId);
    if (target === undefined || meta.profiles.length <= 1) return false;
    const remained = meta.profiles.filter((p) => p.id !== profileId);
    meta.activeId = meta.activeId === profileId ? remained[0].id : meta.activeId;
    meta.profiles = remained;
    // 先写 meta 再删课程文件，避免悬空引用
    this.writeMeta(meta);
    try {
      fs.unlinkSync(this.coursesPath(target.coursesFile));
    } catch {
      // 文件可能已不存在
    }
    return true;
  }

  importParsed(courses: CourseSchedule[], mode: ImportMode,
               newProfileName: string | null = null,
               semesterStartDate: SimpleDate | null = null): ImportResult {
    const merged = this.mergeDuplicates(courses);
    const meta = this.ensureInitialized();
    const now = Date.now();
    const start = semesterStartDate ?? inferFromCourses(merged);

    if (mode === ImportMode.OVERWRITE_ACTIVE) {
      const active = meta.profiles.find((p) => p.id === meta.activeId) ?? meta.profiles[0];
      this.writeCoursesFile(active.coursesFile, merged);
      active.updatedAt = now;
      active.semesterStartDate = start.isoString();
      meta.activeId = active.id;
      this.writeMeta(meta);
      const result = new ImportResult();
      result.profileId = active.id; result.profileName = active.name;
      result.courseCount = merged.length; result.isNewProfile = false;
      result.semesterStartDate = start;
      return result;
    }

    const id = this.randomUUID();
    const trimmed = newProfileName?.trim() ?? '';
    const profileName = trimmed.length > 0 ? trimmed : `课表${this.timestampLabel()}`;
    const fileName = `courses_${id}.json`;
    this.writeCoursesFile(fileName, merged);
    meta.profiles.push({
      id, name: profileName, coursesFile: fileName,
      createdAt: now, updatedAt: now, semesterStartDate: start.isoString(),
    });
    meta.activeId = id;
    this.writeMeta(meta);
    const result = new ImportResult();
    result.profileId = id; result.profileName = profileName;
    result.courseCount = merged.length; result.isNewProfile = true;
    result.semesterStartDate = start;
    return result;
  }

  addManualCourse(input: ManualCourseInput): void {
    const name = input.courseName.trim();
    if (name.length === 0) fail('课程名不能为空');
    if (input.startSection < 1 || input.startSection > 12) fail('开始节必须在 1-12 之间');
    if (input.endSection < 1 || input.endSection > 12) fail('结束节必须在 1-12 之间');
    if (input.endSection < input.startSection) fail('结束节不能小于开始节');
    if (input.startWeek < 1 || input.startWeek > 30) fail('开始周必须在 1-30 之间');
    if (input.endWeek < 1 || input.endWeek > 30) fail('结束周必须在 1-30 之间');
    if (input.endWeek < input.startWeek) fail('结束周不能小于开始周');

    const meta = this.ensureInitialized();
    const active = meta.profiles.find((p) => p.id === meta.activeId) ?? meta.profiles[0];
    const courses = this.readCoursesFile(active.coursesFile);
    const rule: WeekRule = { startWeek: input.startWeek, endWeek: input.endWeek, parity: input.parity };
    const meeting: MeetingTime = {
      weekday: input.weekday,
      startSection: input.startSection,
      endSection: input.endSection,
      weekRules: [rule],
      location: input.location,
    };
    courses.push({
      courseName: name, teacher: input.teacher, semester: input.semester,
      credit: null, rawWeekText: `${input.startWeek}-${input.endWeek}周`,
      meetings: [meeting],
    });
    this.writeCoursesFile(active.coursesFile, courses);
    active.updatedAt = Date.now();
    this.writeMeta(meta);
  }

  loadActiveCourses(): CourseSchedule[] {
    const meta = this.ensureInitialized();
    const active = meta.profiles.find((p) => p.id === meta.activeId) ?? meta.profiles[0];
    return this.readCoursesFile(active.coursesFile);
  }

  getActiveSemesterStartDate(): SimpleDate {
    const meta = this.ensureInitialized();
    const active = meta.profiles.find((p) => p.id === meta.activeId) ?? meta.profiles[0];
    return this.semesterStartOf(active);
  }

  setActiveSemesterStartDate(date: SimpleDate): void {
    const meta = this.ensureInitialized();
    const active = meta.profiles.find((p) => p.id === meta.activeId) ?? meta.profiles[0];
    active.semesterStartDate = normalizeToWeekStart(date).isoString();
    active.updatedAt = Date.now();
    this.writeMeta(meta);
  }

  exportBackup(): string {
    const meta = this.ensureInitialized();
    const profiles: BackupProfile[] = meta.profiles.map((p) => ({
      name: p.name,
      semesterStartDate: p.semesterStartDate ?? null,
      courses: this.readCoursesFile(p.coursesFile).map((c) => courseToBackup(c)),
    }));
    return encodeBackup(new Date().toISOString(), profiles);
  }

  importBackup(content: string): number {
    const backupProfiles = decodeBackup(content);
    if (backupProfiles === null) fail('备份文件格式不正确');
    const names = fs.listFileSync(this.dir);
    for (const name of names) {
      if (name.startsWith('courses_') && name.endsWith('.json')) {
        try {
          fs.unlinkSync(this.dir + '/' + name);
        } catch {
          // 忽略删除失败
        }
      }
    }
    const now = Date.now();
    const newProfiles: StoredProfileMeta[] = [];
    for (const backup of backupProfiles) {
      const id = this.randomUUID();
      const fileName = `courses_${id}.json`;
      this.writeCoursesFile(fileName, backup.courses.map((c) => backupToCourse(c)));
      newProfiles.push({
        id,
        name: backup.name.length > 0 ? backup.name : `课表${this.timestampLabel()}`,
        coursesFile: fileName,
        createdAt: now, updatedAt: now,
        semesterStartDate: backup.semesterStartDate ?? undefined,
      });
    }
    if (newProfiles.length > 0) {
      this.writeMeta({ activeId: newProfiles[0].id, profiles: newProfiles });
    } else {
      this.initializeDefault();
    }
    return newProfiles.length;
  }

  exportActiveTimetableText(periodRanges: string[]): string {
    const meta = this.ensureInitialized();
    const active = meta.profiles.find((p) => p.id === meta.activeId) ?? meta.profiles[0];
    const courses = this.readCoursesFile(active.coursesFile);
    const start = this.semesterStartOf(active);
    let maxWeek = 0;
    for (const course of courses) {
      for (const meeting of course.meetings) {
        for (const rule of meeting.weekRules) {
          if (rule.endWeek > maxWeek) maxWeek = rule.endWeek;
        }
      }
    }
    let out = `课表：${active.name}\n学期开始：${start.isoString()}（第1周周一）\n总周数：${maxWeek}\n`;
    const labels = ['一', '二', '三', '四', '五', '六', '日'];
    for (let weekday = 1; weekday <= 7; weekday++) {
      out += `\n【周${labels[weekday - 1]}】\n`;
      const items: CourseScheduleMeetingPair[] = [];
      for (const course of courses) {
        for (const meeting of course.meetings) {
          if (meeting.weekday === weekday) items.push({ course, meeting });
        }
      }
      items.sort((a, b) => a.meeting.startSection - b.meeting.startSection);
      if (items.length === 0) out += '（无课程）\n';
      for (const item of items) {
        const startText = periodRanges[item.meeting.startSection - 1]?.split('-')[0] ?? '--:--';
        const endText = periodRanges[item.meeting.endSection - 1]?.split('-')[1] ?? '--:--';
        out += `第${item.meeting.startSection}-${item.meeting.endSection}节 ${startText}-${endText}  ${item.course.courseName}`;
        if (item.meeting.location.length > 0) out += `  ${item.meeting.location}`;
        if (item.course.teacher.length > 0) out += `  ${item.course.teacher}`;
        if (item.course.rawWeekText.length > 0) out += `  ${item.course.rawWeekText}`;
        out += '\n';
      }
    }
    return out;
  }

  private mergeDuplicates(courses: CourseSchedule[]): CourseSchedule[] {
    const seen = new Set<string>();
    const result: CourseSchedule[] = [];
    for (const course of courses) {
      const meetings = [...course.meetings].sort((a, b) =>
        (a.weekday * 100 + a.startSection) - (b.weekday * 100 + b.startSection));
      let key = `${course.courseName}|${course.teacher}|${course.semester}|${course.credit ?? -1}|${course.rawWeekText}|`;
      for (const meeting of meetings) {
        key += `${meeting.weekday}:${meeting.startSection}-${meeting.endSection}@${meeting.location}#`;
        key += meeting.weekRules.map((r) => `${r.startWeek}-${r.endWeek}(${r.parity})`).join(',');
        key += ';';
      }
      if (!seen.has(key)) {
        seen.add(key);
        result.push(course);
      }
    }
    return result;
  }

  private timestampLabel(): string {
    const now = new Date();
    const pad = (n: number): string => String(n).padStart(2, '0');
    return `${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}`;
  }
}

interface CourseScheduleMeetingPair {
  course: CourseSchedule;
  meeting: MeetingTime;
}

interface PersistedWeekRuleJSON {
  startWeek: number;
  endWeek: number;
  parity: string;
}

interface PersistedMeetingJSON {
  weekday: string;
  startSection: number;
  endSection: number;
  weekRules: PersistedWeekRuleJSON[];
  location: string;
}

interface PersistedCourseJSON {
  courseName: string;
  teacher: string;
  semester: string;
  credit: number | null;
  rawWeekText: string;
  meetings: PersistedMeetingJSON[];
}

function fail(message: string): never {
  throw new Error(message);
}
