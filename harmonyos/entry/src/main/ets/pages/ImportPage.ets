import { webview } from '@kit.ArkWeb';
import { util } from '@kit.ArkTS';
import { ProfileStore } from '../core/ProfileStore.ets';
import {
  parseCacheEntries, isLikelyScheduleUrl, CacheEntry, ParseResult,
} from '../core/ImportParser.ets';
import { currentPalette, ThemePalette } from '../model/Theme.ets';
import { Prefs } from '../model/Prefs.ets';
import { ImportMode } from '../core/ProfileStore.ets';
import { router } from '@kit.ArkUI';

/** 注入页面的 XHR/fetch 钩子：把响应累积到 window.__jluCaptures（与 Android 同源逻辑） */
const HOOK_SCRIPT = `
(function () {
    if (window.__jluHooked) return;
    window.__jluHooked = true;
    window.__jluCaptures = [];
    function send(url, text) {
        try {
            if (!url || !text) return;
            window.__jluCaptures.push({ url: String(url), text: String(text) });
        } catch (e) {}
    }
    var _open = XMLHttpRequest.prototype.open;
    var _send = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url) {
        this.__jluUrl = url || '';
        return _open.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function() {
        this.addEventListener('load', function() {
            send(this.__jluUrl || '', this.responseText || '');
        });
        return _send.apply(this, arguments);
    };
    if (window.fetch) {
        var _fetch = window.fetch;
        window.fetch = function(input, init) {
            var url = (typeof input === 'string') ? input : ((input && input.url) || '');
            return _fetch(input, init).then(function(resp) {
                try {
                    var clone = resp.clone();
                    clone.text().then(function(text) { send(url, text); }).catch(function(){});
                } catch (e) {}
                return resp;
            });
        };
    }
})();
`;

@Entry
@Component
struct ImportPage {
  controller: webview.WebviewController = new webview.WebviewController();
  @StorageProp('systemDark') systemDark: boolean = false;
  @State addressText: string = 'https://iedu.jlu.edu.cn/';
  @State captureStatus: string = '当前捕获状态：等待课表响应';
  @State resultMessage: string = '';
  @State dismissAfterDone: boolean = false;
  private captured: CacheEntry[] = [];
  private seen: Set<string> = new Set<string>();
  private sequence: number = 0;
  private parseResult: ParseResult | null = null;

  palette(): ThemePalette {
    return currentPalette(Prefs.theme(), Prefs.darkMode(), this.systemDark);
  }

  build() {
    Column() {
      Row() {
        TextInput({ text: this.addressText })
          .onChange((value: string) => {
            this.addressText = value;
          })
          .layoutWeight(1)
          .height(40)
          .backgroundColor(this.palette().panelAlt)
          .fontColor(this.palette().textPrimary)
        Button('前往')
          .backgroundColor(this.palette().buttonBg)
          .fontColor(this.palette().buttonText)
          .height(36)
          .margin({ left: 6 })
          .onClick(() => {
            this.go();
          })
      }
      .padding(8)

      Stack({ alignContent: Alignment.BottomEnd }) {
        Column() {
          Web({ src: this.addressText, controller: this.controller })
            .javaScriptAccess(true)
            .domStorageAccess(true)
            .mixedMode(MixedMode.Compatibility)
            .width('100%')
            .layoutWeight(1)
            .javaScriptOnDocumentStart([
              {
                script: HOOK_SCRIPT,
                scriptRules: ['*'],
                runMode: ScriptRunMode.CONCURRENT_WITH_PAGE,
              },
            ])
            .onPageBegin(() => {
              // 双保险：文档开始后再注入一次
              this.controller.runJavaScript(HOOK_SCRIPT);
            })
            .onPageEnd(() => {
              this.controller.getUrl().then((webUrl: string) => {
                this.addressText = webUrl;
              }).catch(() => {});
              this.pullCaptures();
            })
        }
        .width('100%')
        .height('100%')

        Button('从此处导入')
          .backgroundColor(this.palette().buttonBg)
          .fontColor(this.palette().buttonText)
          .margin(16)
          .onClick(() => {
            this.chooseImportMode();
          })
      }
      .layoutWeight(1)

      Text(this.captureStatus)
        .fontSize(11)
        .fontColor(this.palette().textSecondary)
        .width('100%')
        .padding({ left: 10, right: 10, top: 4, bottom: 4 })
        .backgroundColor(this.palette().panel)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.palette().page)
    .overlay(this.resultView(), { align: Alignment.Center })
  }

  @Builder
  resultView() {
    Column() {
      if (this.resultMessage.length > 0) {
        Column() {
          Text(this.resultMessage)
            .fontSize(13)
            .fontColor(this.palette().textPrimary)
            .textAlign(TextAlign.Center)
          Button(this.dismissAfterDone ? '返回课表' : '知道了')
            .backgroundColor(this.palette().buttonBg)
            .fontColor(this.palette().buttonText)
            .margin({ top: 12 })
            .onClick(() => {
              this.resultMessage = '';
              if (this.dismissAfterDone) {
                AppStorage.setOrCreate('dataVersion', Date.now());
                router.back();
              }
              this.dismissAfterDone = false;
            })
        }
        .padding(16)
        .backgroundColor(this.palette().panelAlt)
        .borderRadius(14)
        .margin(24)
      }
    }
  }

  go(): void {
    let raw = this.addressText.trim();
    if (!raw.startsWith('http://') && !raw.startsWith('https://')) {
      raw = 'https://' + raw;
      this.addressText = raw;
    }
    this.controller.loadUrl(raw);
  }

  pullCaptures(): void {
    this.controller.runJavaScript('JSON.stringify(window.__jluCaptures || [])')
      .then((json: string) => {
        const text = json.startsWith('"') ? JSON.parse(json) as string : json;
        const list = JSON.parse(text) as Array<{ url: string; text: string }>;
        for (const item of list) {
          this.record(item.url, item.text);
        }
        this.updateStatus();
      })
      .catch(() => {});
  }

  record(url: string, text: string): void {
    if (url.length === 0 || text.length < 400) return;
    if (!isLikelyScheduleUrl(url)) return;
    if (!text.includes('"datas"') || !text.includes('"rows"')) return;
    const key = `JS|${url}|${text.substring(0, 128)}`;
    if (this.seen.has(key)) return;
    this.seen.add(key);
    this.sequence += 1;
    this.captured.push({
      url,
      fileName: `00${this.sequence}_js.json`,
      content: text,
      sequence: this.sequence,
    });
    this.parseResult = parseCacheEntries(this.captured);
    this.updateStatus();
  }

  updateStatus(): void {
    let count = 0;
    for (const entry of this.captured) {
      if (isLikelyScheduleUrl(entry.url)) count += 1;
    }
    this.captureStatus = `当前捕获状态：已捕获 ${count} 个疑似课表响应`;
  }

  chooseImportMode(): void {
    this.parseResult = parseCacheEntries(this.captured);
    if ((this.parseResult?.courses.length ?? 0) === 0) {
      this.resultMessage = '未找到可解析的课表数据，请在网页中打开课表详情后重试。';
      return;
    }
    this.getUIContext().showAlertDialog({
      title: '导入方式',
      message: '选择导入方式',
      primaryButton: {
        value: '新建课表',
        action: (): void => {
          this.startImport(ImportMode.CREATE_NEW, null);
        },
      },
      secondaryButton: {
        value: '覆盖当前课表',
        action: (): void => {
          this.startImport(ImportMode.OVERWRITE_ACTIVE, null);
        },
      },
    });
  }

  startImport(mode: ImportMode, name: string | null): void {
    const result = this.parseResult;
    if (result === null || result.courses.length === 0) {
      this.resultMessage = '未找到可解析的课表数据，请在网页中打开课表详情后重试。';
      return;
    }
    try {
      const imported = ProfileStore.instance?.importParsed(
        result.courses, mode, name, result.inferredSemesterStartDate);
      if (imported === undefined || imported === null) {
        this.resultMessage = '写入课表失败';
        return;
      }
      let message = `已从缓存导入 ${imported.courseCount} 条课程，当前课表：${imported.profileName}`;
      if (result.selectedSemester.length > 0) {
        message += `\n学期：${result.selectedSemester}`;
      }
      if (imported.semesterStartDate !== null) {
        message += `\n第一周起始日期：${imported.semesterStartDate.isoString()}`;
      }
      this.resultMessage = message;
      this.dismissAfterDone = true;
      this.captured = [];
      this.seen.clear();
      this.updateStatus();
    } catch (error) {
      this.resultMessage = `写入课表失败：${(error as Error).message}`;
    }
  }
}
