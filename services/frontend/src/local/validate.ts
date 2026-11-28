// 서버의 업로드 허용 확장자와 로컬 데모의 파일 검증
export const FORMATS = ["pdf", "xlsx", "xlsm", "csv", "pptx", "html", "htm", "xml", "txt", "md", "json"];
export const ACCEPT = FORMATS.map((f) => "." + f).join(",");
export const TEXT_FORMATS = /\.(txt|md|csv|json|html?|xml)$/i;
// 바이너리 형식은 확장자가 아니라 파일 머리(시그니처)로 확인. xlsx · xlsm · pptx는 zip 컨테이너
const ZIP = [0x50, 0x4b, 0x03, 0x04];
const SIGS: Record<string, number[]> = { pdf: [0x25, 0x50, 0x44, 0x46, 0x2d], xlsx: ZIP, xlsm: ZIP, pptx: ZIP };
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
// text: 텍스트 파일 본문 (바이너리면 null). head: 바이너리 파일 앞 바이트 (시그니처 확인)
export function checkUpload(name: string, text: string | null, head?: Uint8Array): { code: UploadCode; msg: string; company?: string } {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (!name.includes(".") || !FORMATS.includes(ext))
    return { code: "FORMAT_MISMATCH", msg: `지원하지 않는 형식입니다(.${ext || "확장자 없음"}). ${FORMATS.join(", ")} 파일만 올릴 수 있습니다.` };
  if (TEXT_FORMATS.test(name) && !(text ?? "").trim())
    return { code: "FORMAT_MISMATCH", msg: "파일 내용이 비어 있거나 글자로 읽을 수 없습니다. 인코딩(UTF-8)과 내용을 확인하세요." };
  if (!TEXT_FORMATS.test(name) && !(head && SIGS[ext]?.every((byte, i) => head[i] === byte)))
    return { code: "FORMAT_MISMATCH", msg: `파일 내용이 .${ext} 형식이 아닙니다. 확장자와 실제 파일 형식을 확인하세요.` };
  if (ext === "json" && text) { try { JSON.parse(text); } catch { return { code: "FORMAT_MISMATCH", msg: "JSON 형식이 올바르지 않습니다." }; } }
  const explicitCompany = text?.match(/^\s*(?:대상\s*회사|회사|company)\s*[:=]\s*([^\r\n]+)/im)?.[1] ?? "";
  const subject = `${name} ${explicitCompany}`;
  const companies = COMPANIES4.filter(([, re]) => re.test(subject)).map(([company]) => company);
  const other = subject.match(OTHERS)?.[0];
  if (companies.length === 1 && !other) return { code: null, msg: "", company: companies[0] };
  return { code: "COMPANY_MISMATCH", msg: `${other ? `'${other}' 이름이 포함되어 있습니다. ` : ""}대상 회사를 확인해 주세요. 파일 이름이나 본문의 '대상 회사:' 항목에 SKT · SKB · KT · LGU+ 중 한 곳을 적어 주세요.` };
}
