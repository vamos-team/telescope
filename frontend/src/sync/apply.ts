// 다른 사람이 보낸 이벤트(화면 형식)를 화면 상태(카드·연결)에 반영한다.
// 내가 조작할 때 스토어가 하는 변경과 같은 결과가 나와야 한다(stores/canvas.ts의 각 동작과 짝).
import type { CanvasEvent } from "../api";
import type { Card, Link, MemoCard, TableCard } from "../stores/canvas";

export interface CanvasState { cards: Card[]; links: Link[] }

const nums = (v: unknown) => (Array.isArray(v) ? v.map(Number) : []);
const sortedUnique = (v: number[]) => [...new Set(v)].sort((a, b) => a - b);
const pos = (e: CanvasEvent) => ({ x: Number(e.x ?? 0), y: Number(e.y ?? 0) });

/**
 * 이벤트 한 건을 반영한다. 대상 카드·연결이 이미 없으면 아무것도 하지 않는다(서버와 같은 규칙).
 *
 * @param authorName 이벤트를 보낸 사람 이름(메모 카드·선 메모 작성자 표시용)
 * @param authorId 이벤트를 보낸 사람 id(메모를 단 사람만 고치게 판단할 때 씀)
 * @return 화면 상태가 바뀌었으면 true
 */
export function applyRemote(s: CanvasState, e: CanvasEvent, authorName: string, authorId?: number): boolean {
  const id = String(e.card_id ?? "");
  const card = s.cards.find((c) => c.id === id);
  const table = card?.kind === "table" ? (card as TableCard) : undefined;
  switch (e.type) {
    case "add_card": {
      if (card) return false;
      const cols = sortedUnique(nums(e.cols));
      s.cards.push({ id, kind: "table", tableId: String(e.table_id), rows: sortedUnique(nums(e.rows)), cols,
        vcol: cols[0], edits: {}, sort: null, checked: false, ...pos(e) });
      return true;
    }
    case "add_doc": return addOnce(s, card, { id, kind: "doc", docId: String(e.doc_id), ...pos(e) });
    case "add_dash": return addOnce(s, card, { id, kind: "dash", panel: String(e.panel), ...pos(e) });
    case "add_memo": return addOnce(s, card, { id, kind: "memo", author: authorName, authorId, text: String(e.text ?? ""), ...pos(e) });
    case "create_compare": {
      const sources = (e.sources as string[]) ?? [];
      return addOnce(s, card, e.view === "chart" ? { id, kind: "chart", sources, version: 0, ...pos(e) } : { id, kind: "compare", sources, version: 0, ...pos(e) });
    }
    case "add_rows": if (!table) return false; table.rows = sortedUnique([...table.rows, ...nums(e.rows)]); return true;
    case "remove_row": if (!table) return false; table.rows = table.rows.filter((r) => r !== Number(e.r)); return true;
    case "select_period": if (!table) return false; table.vcol = Number(e.c); return true;
    case "sort_card":
      if (!table) return false;
      table.sort = e.key == null ? null : { key: e.key as string | number, desc: Boolean(e.desc) };
      return true;
    case "edit_cell":
      if (!table) return false;
      table.edits[`${e.r},${e.c}`] = e.value == null ? null : Number(e.value);
      return true;
    case "revert_card": if (!table) return false; table.edits = {}; return true;
    case "edit_memo": if (card?.kind !== "memo") return false; (card as MemoCard).text = String(e.text ?? ""); return true;
    case "set_card_memo":
      if (!card) return false;
      card.memo = String(e.text ?? "") || undefined; card.memoBy = card.memo ? authorName : undefined; card.memoById = card.memo ? authorId : undefined; return true;
    case "move_card": if (!card) return false; card.x = Number(e.x); card.y = Number(e.y); return true;
    case "remove_card": return removeCard(s, id);
    case "clear_cards": if (!s.cards.length) return false; s.cards = []; s.links = []; return true;
    case "add_link": {
      const linkId = String(e.link_id);
      if (s.links.some((l) => l.id === linkId)) return false;
      if (!s.cards.some((c) => c.id === e.source) || !s.cards.some((c) => c.id === e.target)) return false;
      s.links.push({ id: linkId, source: String(e.source), target: String(e.target) });
      return true;
    }
    case "set_link_memo": {
      const l = s.links.find((x) => x.id === e.link_id); if (!l) return false;
      l.memo = String(e.text ?? "") || undefined; l.memoBy = l.memo ? authorName : undefined; l.memoById = l.memo ? authorId : undefined; return true;
    }
    case "remove_link": { const before = s.links.length; s.links = s.links.filter((l) => l.id !== e.link_id); return s.links.length !== before; }
    default: return false;  // confirm_mapping(화면 상태 없음), set_share(공유 변경은 알림으로 처리) 등
  }
}

function addOnce(s: CanvasState, existing: Card | undefined, card: Card): boolean {
  if (existing) return false;
  s.cards.push(card);
  return true;
}

/** 카드를 지우고, 닿는 연결과 비교·시각화 카드의 원본 목록을 정리한다(스토어의 remove와 같은 결과). */
function removeCard(s: CanvasState, id: string): boolean {
  if (!s.cards.some((c) => c.id === id)) return false;
  s.links = s.links.filter((l) => l.source !== id && l.target !== id);
  s.cards = s.cards.filter((c) => c.id !== id);
  for (const c of s.cards) if (c.kind === "compare" || c.kind === "chart") c.sources = c.sources.filter((x) => x !== id);
  return true;
}
