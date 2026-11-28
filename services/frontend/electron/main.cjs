// Electron 데스크톱 창. `--dev`면 Vite 개발 서버(실시간 반영), 아니면 빌드 결과 dist/index.html을 연다.
// package.json이 ES 모듈("type": "module")이라 이 파일은 CommonJS(.cjs)로 둔다.
const { app, BrowserWindow } = require("electron");
const path = require("node:path");

const DEV_URL = "http://localhost:5173";
const dev = process.argv.includes("--dev");

function load(win) {
  if (!dev) return win.loadFile(path.join(__dirname, "../dist/index.html"));
  // 개발 서버가 아직 안 떴으면 0.5초마다 다시 시도 (npm run electron:dev는 서버와 창을 같이 띄움)
  win.loadURL(DEV_URL).catch(() => setTimeout(() => load(win), 500));
}

app.whenReady().then(() => {
  const win = new BrowserWindow({ width: 1440, height: 900, title: "Telescope", autoHideMenuBar: true });
  load(win);
});
app.on("window-all-closed", () => app.quit());
