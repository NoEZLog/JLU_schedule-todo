import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { ConfigurationConstant } from '@kit.AbilityKit';
import { ProfileStore } from '../core/ProfileStore.ets';
import { Prefs } from '../model/Prefs.ets';
import { ReminderService } from '../model/ReminderService.ets';

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    ProfileStore.init(this.context);
    Prefs.init(this.context);
    AppStorage.setOrCreate('dataVersion', 0);
    AppStorage.setOrCreate('themeVersion', 0);
    AppStorage.setOrCreate('systemDark', this.context.config?.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK);
  }

  onDestroy(): void {}

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        console.error('Failed to load content. Cause: ' + JSON.stringify(err));
        return;
      }
    });
  }

  onWindowStageDestroy(): void {}

  onForeground(): void {
    ReminderService.refreshIfEnabled();
  }

  onBackground(): void {}

  onConfigurationUpdate(newConfig: ConfigurationConstant.Configuration): void {
    AppStorage.setOrCreate('systemDark', newConfig.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK);
    AppStorage.setOrCreate('themeVersion', Date.now());
  }
}
