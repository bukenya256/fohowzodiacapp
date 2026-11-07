// Zodiac Admin - Windows desktop app. Opens the admin dashboard in its own window (no browser, no typing).
const { app, BrowserWindow, Menu, shell, session, powerMonitor } = require('electron');
const fs = require('fs');
const path = require('path');

// ---- Security settings (change if you want) ----
const AUTO_SIGNOUT_MS = 5 * 60 * 1000; // away from the window longer than this => signed out
const MIN_SPLASH_MS = 2200;            // minimum time the logo screen stays visible

function readAdminUrl() {
  const t = fs.readFileSync(path.join(__dirname, 'ADMIN_URL.txt'), 'utf8');
  return t.split(/\r?\n/).map(s => s.trim()).find(s => s && !s.startsWith('#'));
}
const ADMIN_URL = readAdminUrl();
const ADMIN_HOST = new URL(ADMIN_URL).hostname;

if (!app.requestSingleInstanceLock()) { app.quit(); }

let splash = null, win = null, leftAt = 0;

function createSplash() {
  splash = new BrowserWindow({
    width: 560, height: 470, frame: false, resizable: false, movable: true,
    backgroundColor: '#440000', alwaysOnTop: true, skipTaskbar: true,
    icon: path.join(__dirname, 'assets', 'icon.png'),
    webPreferences: { sandbox: true, contextIsolation: true }
  });
  splash.loadFile(path.join(__dirname, 'splash.html'));
}

function isAdminUrl(u) {
  try { const x = new URL(u); return (x.protocol === 'http:' || x.protocol === 'https:') && x.hostname === ADMIN_HOST; }
  catch (_) { return false; }
}

function createMain() {
  win = new BrowserWindow({
    width: 1366, height: 860, minWidth: 420, minHeight: 600, show: false,
    backgroundColor: '#440000', title: 'Zodiac Admin',
    icon: path.join(__dirname, 'assets', 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, devTools: false, spellcheck: false }
  });
  win.setMenuBarVisibility(false);

  // Links to other websites open in the normal browser, never inside the admin window.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (isAdminUrl(url)) { win.loadURL(url); }
    else if (/^https?:/i.test(url)) { shell.openExternal(url); }
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => {
    if (url.startsWith('file:')) return;
    if (!isAdminUrl(url)) { e.preventDefault(); if (/^https?:/i.test(url)) shell.openExternal(url); }
  });

  // No internet / site unreachable -> friendly page with a Retry button.
  win.webContents.on('did-fail-load', (_e, code, _d, _url, isMain) => {
    if (isMain && code !== -3) {
      win.loadFile(path.join(__dirname, 'offline.html'), { query: { u: ADMIN_URL } });
    }
  });

  // Away from the window for a while -> sign out so the password is needed again.
  const away = () => { if (!leftAt) leftAt = Date.now(); };
  const back = () => {
    if (leftAt && Date.now() - leftAt > AUTO_SIGNOUT_MS) signOut();
    leftAt = 0;
  };
  win.on('blur', away);
  win.on('minimize', away);
  win.on('hide', away);
  win.on('focus', back);
  win.on('restore', back);
  win.on('show', back);
  powerMonitor.on('lock-screen', away);
  powerMonitor.on('suspend', away);
  powerMonitor.on('unlock-screen', () => { if (win && win.isFocused()) back(); });

  const minTime = new Promise(r => setTimeout(r, MIN_SPLASH_MS));
  const loaded = new Promise(r => win.webContents.once('did-finish-load', r));
  win.loadURL(ADMIN_URL);
  Promise.all([minTime, loaded]).catch(() => {}).then(() => {
    if (splash && !splash.isDestroyed()) splash.close();
    if (win && !win.isDestroyed()) { win.show(); win.focus(); }
  });
  // Never get stuck on the logo screen if the site is slow.
  setTimeout(() => {
    if (splash && !splash.isDestroyed()) splash.close();
    if (win && !win.isDestroyed() && !win.isVisible()) win.show();
  }, 20000);
}

async function signOut() {
  if (!win || win.isDestroyed()) return;
  try {
    await win.webContents.executeJavaScript('try{sessionStorage.clear()}catch(e){}', true).catch(() => {});
    await session.defaultSession.clearStorageData();
  } catch (_) {}
  win.loadURL(ADMIN_URL);
}

app.on('second-instance', () => {
  if (win) { if (win.isMinimized()) win.restore(); win.focus(); }
});

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  session.defaultSession.setPermissionRequestHandler((_wc, permission, cb) => cb(permission === 'notifications'));
  createSplash();
  createMain();
});

app.on('window-all-closed', () => app.quit());
