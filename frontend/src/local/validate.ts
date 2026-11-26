// 사용자 데이터 입력 검증 (MVP 기준 두 가지): 포맷 불일치 / 4사 회사 불일치
// 실제 서비스에서는 업로드 API가 같은 규칙으로 검사하고 오류 코드(FORMAT_MISMATCH, COMPANY_MISMATCH)를 돌려준다
export const FORMATS = ["txt", "md", "csv", "json", "pdf", "xlsx", "docx", "hwp"];
export const TEXT_FORMATS = /\.(txt|md|csv|json)$/i;
// 대상 4사와 부르는 이름. 회사 키는 데이터의 company 값
export const COMPANIES4: [string, RegExp][] = [
  ["SKB", /skb|sk\s?브로드밴드|에스케이브로드밴드/i],
  ["SKT", /skt|sk\s?텔레콤|에스케이텔레콤/i],
  ["LGU+", /lgu\+?|lg\s?유플러스|엘지유플러스|lg\s?u\+/i],
  ["KT", /(^|[^a-z])kt([^a-z]|$)|케이티/i],
];
// 4사가 아닌 회사 이름이 보이면 회사 불일치 이유에 함께 적음 (예시 목록)
const OTHERS = /삼성|네이버|카카오|현대|LG전자|SK하이닉스|쿠팡|애플|구글/;

export type UploadCode = "FORMAT_MISMATCH" | "COMPANY_MISMATCH" | null;
// text: 텍스트 파일 본문 (읽지 못하는 형식이면 null → 파일 이름으로만 회사 확인)
export function checkUpload(name: string, text: string | null): { code: UploadCode; msg: string; company?: string } {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (!name.includes(".") || !FORMATS.includes(ext))
    return { code: "FORMAT_MISMATCH", msg: `지원하지 않는 형식입니다(.${ext || "확장자 없음"}). ${FORMATS.join(", ")} 파일만 올릴 수 있습니다.` };
  if (TEXT_FORMATS.test(name) && !(text ?? "").trim())
    return { code: "FORMAT_MISMATCH", msg: "파일 내용이 비어 있거나 글자로 읽을 수 없습니다. 인코딩(UTF-8)과 내용을 확인하세요." };
  if (ext === "json" && text) { try { JSON.parse(text); } catch { return { code: "FORMAT_MISMATCH", msg: "JSON 형식이 올바르지 않습니다." }; } }
  const hay = `${name} ${text ?? ""}`;
  const hit = COMPANIES4.find(([, re]) => re.test(hay));
  if (!hit) {
    const other = hay.match(OTHERS)?.[0];
    return { code: "COMPANY_MISMATCH", msg: `${other ? `'${other}' 자료로 보입니다. ` : ""}SKT · SKB · KT · LGU+ 자료만 올릴 수 있습니다. 파일 이름이나 본문에 회사명을 넣어 주세요.` };
  }
  return { code: null, msg: "", company: hit[0] };
}
