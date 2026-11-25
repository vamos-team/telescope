// dist/의 JS·CSS를 index.html 안에 넣어 docs/planning/demo/index.html 한 파일로 만든다 (file://로 열 수 있게). frontend 폴더에서 실행
import { readFileSync, writeFileSync } from "node:fs";
const html = readFileSync("dist/index.html", "utf8");
const read = (src) => readFileSync("dist/" + src.replace(/^\.\//, ""), "utf8");
const out = html
  .replace(/<script type="module" crossorigin src="([^"]+)"><\/script>/, (_, s) => `<script type="module">${read(s).replace(/<\/script/gi, "<\\/script")}</script>`)
  .replace(/<link rel="stylesheet" crossorigin href="([^"]+)">/, (_, s) => `<style>${read(s)}</style>`);
if (/src="\.\/assets|href="\.\/assets/.test(out)) throw new Error("인라인되지 않은 자산이 남았습니다");
const OUT = "../docs/planning/demo/index.html";
writeFileSync(OUT, out);
console.log(OUT, (out.length / 1024).toFixed(0) + " KB");
