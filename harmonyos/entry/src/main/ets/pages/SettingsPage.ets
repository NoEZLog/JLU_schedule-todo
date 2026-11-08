import {
  CourseSchedule, SimpleDate, SECTION_RANGES,
} from '../core/Core.ets';
import { ProfileStore } from '../core/ProfileStore.ets';
import {
  currentPalette, ThemePalette,
} from '../model/Theme.ets';
import { Prefs } from '../model/Prefs.ets';
import { ReminderService } from '../model/ReminderService.ets';
import { documentViewPicker } from '@kit.CoreFileKit';
import { fs } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';

@Component
export struct SettingsPage {
  @StorageProp('dataVersion') @Watch('reload') dataVersion: number = 0;
  @StorageProp('themeVersion') @Watch('reload') themeVersion: number = 0;
  @StorageProp('systemDark') systemDark: boolean = false;
  @State courses: CourseSchedule[] = [];
  @State semesterStart: SimpleDate = SimpleDate.today();
  @State profiles: ProfileRow[] = [];
  @State toast: string = '';
  @State showProfileManage: boolean = false;
  @State renameText: string = '';
  renameController: CustomDialogController = new CustomDialogController({
    builder: RenameDialog({
      initialName: '',
      onConfirm: (newName: string): void => {
        if (this.renameTargetId.length > 0 && ProfileStore.instance?.renameProfile(this.renameTargetId, newName) ?? false) {
          this.toast = '已重命名';
          this.bumpData();
        } else {
          this.toast = '名称不能为空';
        }
      },
    }),
    autoCancel: true,
    customStyle: false,
  });
  renameTargetId: string = '';
  private themeOptions: string[] = ['暖色', '海蓝', '薄荷'];
  private themeValues: string[] = ['warm', 'ocean', 'mint'];
  private appearanceOptions: string[] = ['跟随系统', '浅色', '深色'];
  private appearanceValues: string[] = ['system', 'light', 'dark'];
  private fontOptions: string[] = ['小号', '标准', '大号'];
  private fontValues: number[] = [0.88, 1.0, 1.14];

  aboutToAppear(): void {
    this.reload();
  }

  reload(): void {
    this.courses = ProfileStore.instance?.loadActiveCourses() ?? [];
    this.semesterStart = ProfileStore.instance?.getActiveSemesterStartDate() ?? SimpleDate.today();
    const list = ProfileStore.instance?.listProfiles() ?? [];
    const rows: ProfileRow[] = [];
    for (const info of list) {
      rows.push({
        id: info.id,
        name: info.name,
        isActive: info.isActive,
        updatedText: this.formatTime(info.updatedAt),
      });
    }
    this.profiles = rows;
  }

  palette(): ThemePalette {
    return currentPalette(Prefs.theme(), Prefs.darkMode(), this.systemDark);
  }

  formatTime(millis: number): string {
    const date = new Date(millis);
    const pad = (n: number): string => String(n).padStart(2, '0');
    return `${date.getFullYear()}/${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }

  bumpData(): void {
    AppStorage.setOrCreate('dataVersion', Date.now());
    this.reload();
  }

  bumpTheme(): void {
    AppStorage.setOrCreate('themeVersion', Date.now());
  }

  build() {
    Scroll() {
      Column() {
        Text('设置')
          .fontSize(20).fontWeight(FontWeight.Bold)
          .fontColor(this.palette().textPrimary)
          .width('100%')

        this.sectionLabel('课表管理')
        ForEach(this.profiles, (profile: ProfileRow) => {
          Column() {
            Row() {
              Text(profile.name)
                .fontSize(15).fontWeight(FontWeight.Medium)
                .fontColor(this.palette().textPrimary)
              if (profile.isActive) {
                Text('当前')
                  .fontSize(10)
                  .fontColor(this.palette().buttonText)
                  .padding({ left: 6, right: 6, top: 2, bottom: 2 })
                  .backgroundColor(this.palette().buttonBg)
                  .borderRadius(10)
                  .margin({ left: 6 })
              }
              Blank()
              Text('⋯')
                .fontSize(16)
                .fontColor(this.palette().iconTint)
                .padding(6)
                .bindMenu([
                  {
                    value: profile.isActive ? '已是当前课表' : '切换到此课表',
                    action: (): void => {
                      if (profile.isActive) {
                        this.toast = '已是当前课表';
                      } else if (ProfileStore.instance?.setActiveProfile(profile.id) ?? false) {
                        this.toast = `已切换到：${profile.name}`;
                        this.bumpData();
                      }
                    },
                  },
                  {
                    value: '重命名',
                    action: (): void => {
                      this.promptRename(profile);
                    },
                  },
                  {
                    value: '删除',
                    action: (): void => {
                      if (ProfileStore.instance?.deleteProfile(profile.id) ?? false) {
                        this.toast = '已删除课表';
                        this.bumpData();
                      } else {
                        this.toast = '至少保留一个课表，无法删除';
                      }
                    },
                  },
                ])
            }
            .width('100%')
            Text(`最近更新：${profile.updatedText}`)
              .fontSize(11)
              .fontColor(this.palette().textSecondary)
              .width('100%')
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%')
          .padding(12)
          .backgroundColor(this.palette().panelAlt)
          .borderRadius(12)
          .onClick(() => {
            if (profile.isActive) {
              this.toast = '已是当前课表';
            } else if (ProfileStore.instance?.setActiveProfile(profile.id) ?? false) {
              this.toast = `已切换到：${profile.name}`;
              this.bumpData();
            }
          })
        }, (profile: ProfileRow) => profile.id + profile.updatedText)

        Button('新建空课表')
          .width('100%')
          .backgroundColor(this.palette().buttonBg)
          .fontColor(this.palette().buttonText)
          .margin({ top: 8 })
          .onClick(() => {
            const info = ProfileStore.instance?.createEmptyProfile('');
            if (info !== undefined && info !== null) {
              this.toast = `已新建并切换到：${info.name}`;
              this.bumpData();
            }
          })

        this.sectionLabel('学期开始周（第一周起始日期）')
        Row() {
          Text(this.semesterStart.displayText())
            .fontSize(14)
            .fontColor(this.palette().textPrimary)
            .layoutWeight(1)
          Button('修改')
            .backgroundColor(this.palette().buttonBg)
            .fontColor(this.palette().buttonText)
            .onClick(() => {
              this.showDatePicker();
            })
        }
        .width('100%')

        this.sectionLabel('主题色')
        Row({ space: 8 }) {
          ForEach(this.themeOptions, (label: string, index: number) => {
            Button(label)
              .layoutWeight(1)
              .fontSize(13)
              .backgroundColor(Prefs.theme() === this.themeValues[index]
                ? this.palette().buttonBg : this.palette().panelAlt)
              .fontColor(Prefs.theme() === this.themeValues[index]
                ? this.palette().buttonText : this.palette().textSecondary)
              .onClick(() => {
                Prefs.setTheme(this.themeValues[index]);
                this.bumpTheme();
              })
          }, (label: string) => label)
        }
        .width('100%')

        this.sectionLabel('外观模式')
        Row({ space: 8 }) {
          ForEach(this.appearanceOptions, (label: string, index: number) => {
            Button(label)
              .layoutWeight(1)
              .fontSize(13)
              .backgroundColor(Prefs.darkMode() === this.appearanceValues[index]
                ? this.palette().buttonBg : this.palette().panelAlt)
              .fontColor(Prefs.darkMode() === this.appearanceValues[index]
                ? this.palette().buttonText : this.palette().textSecondary)
              .onClick(() => {
                Prefs.setDarkMode(this.appearanceValues[index]);
                this.bumpTheme();
              })
          }, (label: string) => label)
        }
        .width('100%')

        this.sectionLabel('课表字号')
        Row({ space: 8 }) {
          ForEach(this.fontOptions, (label: string, index: number) => {
            Button(label)
              .layoutWeight(1)
              .fontSize(13)
              .backgroundColor(Prefs.fontScale() === this.fontValues[index]
                ? this.palette().buttonBg : this.palette().panelAlt)
              .fontColor(Prefs.fontScale() === this.fontValues[index]
                ? this.palette().buttonText : this.palette().textSecondary)
              .onClick(() => {
                Prefs.setFontScale(this.fontValues[index]);
                this.bumpTheme();
              })
          }, (label: string) => label)
        }
        .width('100%')

        this.sectionLabel('非本周课程')
        Row() {
          Text('显示非本周课程')
            .fontSize(14)
            .fontColor(this.palette().textPrimary)
            .layoutWeight(1)
          Toggle({ type: ToggleType.Switch, isOn: Prefs.showNonCurrent() })
            .selectedColor(this.palette().iconTint)
            .onChange((isOn: boolean) => {
              Prefs.setShowNonCurrent(isOn);
              this.bumpData();
            })
        }
        .width('100%')

        this.sectionLabel('上课提醒')
        Row() {
          Text('每天早上发送今日课程提醒')
            .fontSize(14)
            .fontColor(this.palette().textPrimary)
            .layoutWeight(1)
          Toggle({ type: ToggleType.Switch, isOn: Prefs.reminderEnabled() })
            .selectedColor(this.palette().iconTint)
            .onChange((isOn: boolean) => {
              if (isOn) {
                ReminderService.requestAndSchedule().then((granted: boolean) => {
                  if (!granted) {
                    Prefs.setReminderEnabled(false);
                    this.toast = '未获得通知权限';
                    this.bumpTheme();
                  } else {
                    Prefs.setReminderEnabled(true);
                  }
                });
              } else {
                Prefs.setReminderEnabled(false);
                ReminderService.clearPending();
              }
            })
        }
        .width('100%')
        Row() {
          Text(`每天 ${this.reminderTimeText()} 提醒`)
            .fontSize(13)
            .fontColor(this.palette().textSecondary)
            .layoutWeight(1)
          Button('修改时间')
            .backgroundColor(this.palette().buttonBg)
            .fontColor(this.palette().buttonText)
            .onClick(() => {
              this.showTimePicker();
            })
        }
        .width('100%')
        .margin({ top: 6 })

        this.sectionLabel('数据管理')
        Button('导出当前课表为文本')
          .width('100%')
          .backgroundColor(this.palette().panelAlt)
          .fontColor(this.palette().textPrimary)
          .onClick(() => {
            const text = ProfileStore.instance?.exportActiveTimetableText(SECTION_RANGES) ?? '';
            this.saveTextFile('JLU课表.txt', text, '已导出课表文本');
          })
        Row({ space: 8 }) {
          Button('备份全部课表')
            .layoutWeight(1)
            .backgroundColor(this.palette().panelAlt)
            .fontColor(this.palette().textPrimary)
            .onClick(() => {
              const json = ProfileStore.instance?.exportBackup() ?? '';
              const date = new Date();
              const pad = (n: number): string => String(n).padStart(2, '0');
              const name = `JLU_schedule_backup_${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}_${pad(date.getHours())}${pad(date.getMinutes())}.json`;
              this.saveTextFile(name, json, '备份已完成');
            })
          Button('恢复备份')
            .layoutWeight(1)
            .backgroundColor(this.palette().panelAlt)
            .fontColor(this.palette().textPrimary)
            .onClick(() => {
              this.pickBackupFile();
            })
        }
        .width('100%')
        .margin({ top: 8 })

        Text('吉林大学课表应用 · 鸿蒙版 1.2.0')
          .fontSize(11)
          .fontColor(this.palette().textSecondary)
          .width('100%')
          .margin({ top: 16, bottom: 24 })
      }
      .padding(16)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.palette().page)
    .align(Alignment.Top)
    .scrollable(ScrollDirection.Vertical)
    .overlay(this.toastView(), { align: Alignment.Bottom })
  }

  @Builder
  toastView() {
    Column() {
      if (this.toast.length > 0) {
        Text(this.toast)
          .fontSize(13)
          .fontColor(this.palette().textPrimary)
          .padding({ left: 14, right: 14, top: 8, bottom: 8 })
          .backgroundColor(this.palette().panel)
          .borderRadius(20)
          .margin({ bottom: 24 })
          .onClick(() => {
            this.toast = '';
          })
      }
    }
  }

  reminderTimeText(): string {
    const minute = Prefs.reminderMinute();
    const pad = (n: number): string => String(n).padStart(2, '0');
    return `${pad(Math.floor(minute / 60))}:${pad(minute % 60)}`;
  }

  @Builder
  sectionLabel(text: string) {
    Text(text)
      .fontSize(15).fontWeight(FontWeight.Bold)
      .fontColor(this.palette().textSecondary)
      .width('100%')
      .margin({ top: 14, bottom: 4 })
  }

  promptRename(profile: ProfileRow): void {
    this.renameTargetId = profile.id;
    this.renameController.open();
  }

  showDatePicker(): void {
    DatePickerDialog.show({
      start: new Date('2000-1-1'),
      end: new Date('2100-12-31'),
      selected: new Date(this.semesterStart.year, this.semesterStart.month - 1, this.semesterStart.day),
      onDateAccept: (value: Date) => {
        const date = new SimpleDate(value.getFullYear(), value.getMonth() + 1, value.getDate());
        ProfileStore.instance?.setActiveSemesterStartDate(date);
        this.bumpData();
      },
    });
  }

  showTimePicker(): void {
    const minute = Prefs.reminderMinute();
    TimePickerDialog.show({
      selected: new Date(2000, 0, 1, Math.floor(minute / 60), minute % 60),
      useMilitaryTime: true,
      onAccept: (value: TimePickerResult) => {
        if (value.hour !== undefined && value.minute !== undefined) {
          Prefs.setReminderMinute(value.hour * 60 + value.minute);
          if (Prefs.reminderEnabled()) {
            ReminderService.refreshIfEnabled();
          }
          this.bumpTheme();
        }
      },
    });
  }

  saveTextFile(fileName: string, content: string, successMessage: string): void {
    const picker = new documentViewPicker.DocumentViewPicker();
    const options = new documentViewPicker.DocumentSaveOptions();
    options.newFileNames = [fileName];
    picker.save(options).then((urls: Array<string>) => {
      if (urls.length === 0) return;
      const file = fs.openSync(urls[0], fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
      fs.writeSync(file.fd, content);
      fs.closeSync(file);
      this.toast = successMessage;
    }).catch(() => {
      this.toast = '导出失败';
    });
  }

  pickBackupFile(): void {
    const picker = new documentViewPicker.DocumentViewPicker();
    const options = new documentViewPicker.DocumentSelectOptions();
    options.fileSuffixFilters = ['.json', '.txt'];
    picker.select(options).then((urls: Array<string>) => {
      if (urls.length === 0) return;
      try {
        const file = fs.openSync(urls[0], fs.OpenMode.READ_ONLY);
        const stat = fs.statSync(file.fd);
        const buf = new ArrayBuffer(stat.size);
        fs.readSync(file.fd, buf);
        fs.closeSync(file);
        const content = util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buf));
        this.getUIContext().showAlertDialog({
          title: '恢复备份',
          message: '恢复将覆盖当前全部课表，且无法撤销，确定继续吗？',
          primaryButton: {
            value: '取消',
            action: (): void => {},
          },
          secondaryButton: {
            value: '确定恢复',
            action: (): void => {
              try {
                const count = ProfileStore.instance?.importBackup(content) ?? 0;
                this.toast = `已恢复 ${count} 个课表`;
                this.bumpData();
              } catch {
                this.toast = '恢复失败：文件格式不正确';
              }
            },
          },
        });
      } catch {
        this.toast = '无法读取文件';
      }
    }).catch(() => {
      // 用户取消
    });
  }
}

interface ProfileRow {
  id: string;
  name: string;
  isActive: boolean;
  updatedText: string;
}

@CustomDialog
struct RenameDialog {
  controller?: CustomDialogController;
  initialName: string = '';
  onConfirm: (newName: string) => void = (_newName: string): void => {};
  @State name: string = '';
  @StorageProp('systemDark') systemDark: boolean = false;

  aboutToAppear(): void {
    this.name = this.initialName;
  }

  palette(): ThemePalette {
    return currentPalette(Prefs.theme(), Prefs.darkMode(), this.systemDark);
  }

  build() {
    Column() {
      Text('重命名课表')
        .fontSize(16).fontWeight(FontWeight.Bold)
        .fontColor(this.palette().textPrimary)
        .margin({ bottom: 12 })
      TextInput({ text: this.name, placeholder: '课表名称' })
        .onChange((value: string) => {
          this.name = value;
        })
        .height(40)
      Row() {
        Button('取消')
          .backgroundColor(this.palette().panelAlt)
          .fontColor(this.palette().textPrimary)
          .onClick(() => {
            this.controller?.close();
          })
        Button('保存')
          .backgroundColor(this.palette().buttonBg)
          .fontColor(this.palette().buttonText)
          .margin({ left: 10 })
          .onClick(() => {
            this.controller?.close();
            this.onConfirm(this.name.trim());
          })
      }
      .justifyContent(FlexAlign.End)
      .width('100%')
      .margin({ top: 14 })
    }
    .padding(16)
    .backgroundColor(this.palette().panelAlt)
    .borderRadius(16)
  }
}
