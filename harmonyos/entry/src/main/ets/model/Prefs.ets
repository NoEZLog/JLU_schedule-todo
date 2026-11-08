/**
 * 应用偏好（对应 Android AppPreferences），基于 @ohos.data.preferences。
 */
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

export class Prefs {
  private static store: preferences.Preferences | null = null;

  static init(context: common.UIAbilityContext): void {
    Prefs.store = preferences.getPreferencesSync(context, { name: 'app_settings' });
  }

  static getString(key: string, defaultValue: string): string {
    return Prefs.store?.getSync(key, defaultValue) as string ?? defaultValue;
  }

  static setString(key: string, value: string): void {
    Prefs.store?.putSync(key, value);
    Prefs.store?.flush();
  }

  static getBool(key: string, defaultValue: boolean): boolean {
    return Prefs.store?.getSync(key, defaultValue) as boolean ?? defaultValue;
  }

  static setBool(key: string, value: boolean): void {
    Prefs.store?.putSync(key, value);
    Prefs.store?.flush();
  }

  static getNumber(key: string, defaultValue: number): number {
    const saved = Prefs.store?.getSync(key, defaultValue) as number;
    return saved ?? defaultValue;
  }

  static setNumber(key: string, value: number): void {
    Prefs.store?.putSync(key, value);
    Prefs.store?.flush();
  }

  // 便捷项（key 与 Android 端保持一致）
  static theme(): string { return Prefs.getString('theme_color', 'warm'); }
  static setTheme(value: string): void { Prefs.setString('theme_color', value); }
  static darkMode(): string { return Prefs.getString('dark_mode', 'system'); }
  static setDarkMode(value: string): void { Prefs.setString('dark_mode', value); }
  static fontScale(): number {
    const saved = Prefs.getNumber('timetable_font_scale', 1.0);
    if (saved > 1.08) return 1.15;
    if (saved >= 0.98) return 1.0;
    return 0.95;
  }
  static setFontScale(value: number): void {
    const safe = value < 0.98 ? 0.95 : (value > 1.08 ? 1.15 : 1.0);
    Prefs.setNumber('timetable_font_scale', safe);
  }
  static showNonCurrent(): boolean { return Prefs.getBool('show_non_current_courses', true); }
  static setShowNonCurrent(value: boolean): void { Prefs.setBool('show_non_current_courses', value); }
  static reminderEnabled(): boolean { return Prefs.getBool('daily_reminder_enabled', false); }
  static setReminderEnabled(value: boolean): void { Prefs.setBool('daily_reminder_enabled', value); }
  static reminderMinute(): number { return Prefs.getNumber('daily_reminder_minute', 450); }
  static setReminderMinute(value: number): void { Prefs.setNumber('daily_reminder_minute', value); }
}
