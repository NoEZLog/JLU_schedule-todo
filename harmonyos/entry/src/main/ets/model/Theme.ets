/**
 * 主题色板与应用偏好（对应 Android ThemePalette/AppPreferences）。
 */

export interface ThemePalette {
  page: string;
  panel: string;
  panelAlt: string;
  textPrimary: string;
  textSecondary: string;
  iconTint: string;
  buttonBg: string;
  buttonText: string;
  gridHeader: string;
  gridHeaderToday: string;
  gridLeft: string;
  gridDay: string;
  gridDayToday: string;
}

const WARM_LIGHT: ThemePalette = {
  page: '#F7E7CC', panel: '#FFE2BF', panelAlt: '#FFEED8',
  textPrimary: '#5C3C1E', textSecondary: '#7A5A37', iconTint: '#5A3D1F',
  buttonBg: '#EFD7B1', buttonText: '#3A2A1A',
  gridHeader: '#FFE9C9', gridHeaderToday: '#FFD7B5', gridLeft: '#FFEFD6',
  gridDay: '#FFF5E6', gridDayToday: '#FFE8CD',
};
const WARM_DARK: ThemePalette = {
  page: '#221A12', panel: '#33281C', panelAlt: '#2B2116',
  textPrimary: '#F3E7D3', textSecondary: '#C9B697', iconTint: '#E8D5B5',
  buttonBg: '#4A3924', buttonText: '#F3E7D3',
  gridHeader: '#3A2C1B', gridHeaderToday: '#4C3A22', gridLeft: '#2E2315',
  gridDay: '#271E12', gridDayToday: '#342819',
};
const OCEAN_LIGHT: ThemePalette = {
  page: '#EAF5FF', panel: '#E1F1FF', panelAlt: '#F0F8FF',
  textPrimary: '#1F3A56', textSecondary: '#3E5876', iconTint: '#2E4E72',
  buttonBg: '#D6E7FA', buttonText: '#1F3A56',
  gridHeader: '#DCEEFF', gridHeaderToday: '#BFDFFF', gridLeft: '#E8F4FF',
  gridDay: '#F2F8FF', gridDayToday: '#D6EBFF',
};
const OCEAN_DARK: ThemePalette = {
  page: '#101B26', panel: '#1B2C3D', panelAlt: '#16232F',
  textPrimary: '#DDEAF6', textSecondary: '#A3BDD3', iconTint: '#BAD2E8',
  buttonBg: '#274058', buttonText: '#DDEAF6',
  gridHeader: '#1E3247', gridHeaderToday: '#2A4560', gridLeft: '#18293A',
  gridDay: '#142230', gridDayToday: '#1D3143',
};
const MINT_LIGHT: ThemePalette = {
  page: '#E8FAF2', panel: '#DCF4E8', panelAlt: '#ECF9F2',
  textPrimary: '#1D4A3A', textSecondary: '#396458', iconTint: '#2D5A4E',
  buttonBg: '#CFEADF', buttonText: '#1D4A3A',
  gridHeader: '#D7F3E6', gridHeaderToday: '#BFEAD6', gridLeft: '#E7F8EF',
  gridDay: '#F0FBF5', gridDayToday: '#D4F4E4',
};
const MINT_DARK: ThemePalette = {
  page: '#0F1F19', panel: '#1A3028', panelAlt: '#152620',
  textPrimary: '#D9EFE4', textSecondary: '#A2C4B6', iconTint: '#B8D9CB',
  buttonBg: '#24453A', buttonText: '#D9EFE4',
  gridHeader: '#1C362C', gridHeaderToday: '#27493A', gridLeft: '#182B23',
  gridDay: '#14241C', gridDayToday: '#1B3127',
};

/** 深色底淡彩卡片上的固定深色文字（对齐 Android CARD_TEXT_COLOR） */
export const CARD_TEXT = '#37312A';

export const CARD_COLORS: string[] = [
  '#FAD8C0', '#C9E7FF', '#D8F4D2', '#FFE6A8',
  '#E6D7FF', '#FFD7E0', '#D8F0EE', '#FFE1C4',
];

export function currentPalette(theme: string, darkMode: string, systemDark: boolean): ThemePalette {
  const dark = darkMode === 'dark' || (darkMode !== 'light' && systemDark);
  switch (theme) {
    case 'ocean': return dark ? OCEAN_DARK : OCEAN_LIGHT;
    case 'mint': return dark ? MINT_DARK : MINT_LIGHT;
    default: return dark ? WARM_DARK : WARM_LIGHT;
  }
}
