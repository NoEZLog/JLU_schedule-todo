import { TimetablePage } from './TimetablePage.ets';
import { TodayPage } from './TodayPage.ets';
import { SettingsPage } from './SettingsPage.ets';
import { currentPalette } from '../model/Theme.ets';
import { Prefs } from '../model/Prefs.ets';

@Entry
@Component
struct Index {
  @StorageProp('themeVersion') themeVersion: number = 0;
  @StorageProp('systemDark') systemDark: boolean = false;

  palette(): ThemePalette {
    return currentPalette(Prefs.theme(), Prefs.darkMode(), this.systemDark);
  }

  build() {
    Tabs({ barPosition: BarPosition.End }) {
      TabContent() {
        TimetablePage()
      }
      .tabBar(this.tabLabel('课表'))

      TabContent() {
        TodayPage()
      }
      .tabBar(this.tabLabel('日程'))

      TabContent() {
        SettingsPage()
      }
      .tabBar(this.tabLabel('设置'))
    }
    .barBackgroundColor(this.palette().nav)
    .backgroundColor(this.palette().page)
  }

  @Builder
  tabLabel(text: string) {
    Text(text)
      .fontSize(13)
      .fontColor(this.palette().textSecondary)
  }
}
