// 서버에서 연 캔버스(카드·연결)를 화면 카드로 바꾸고, 목업 계산 엔진(local/server.ts)에 다시 넣을 이벤트를 만든다.
// 서버 모드에서도 비교·카드 태그·컨텍스트는 아직 목업이 계산하므로(ai 연동 전), 목업 세션을 같은 상태로 맞춘다.
import type { CanvasEvent } from "../api";
import type { Card, Link } from "../stores/canvas";
import type { CanvasSnapshot, ServerCard } from "./http";

/** 서버 카드 → 화면 카드. 모르는 종류는 버린다. */
export function toLocalCard(c: ServerCard, authorName: (id: number | undefined) => string): Card | null {
  const base = { id: c.id, x: c.x, y: c.y, prompt: c.prompt ?? undefined };
  switch (c.kind) {
    case "table": {
      const sort = Array.isArray(c.sort) ? { key: c.sort[0] as string | number, desc: Boolean(c.sort[1]) } : null;
      return { ...base, kind: "table", tableId: String(c.tableId), rows: (c.rows as number[]) ?? [], cols: (c.cols as number[]) ?? [],
        vcol: Number(c.vcol ?? 0), edits: (c.edits as Record<string, number | null>) ?? {}, sort, checked: false };
    }
    case "compare": return { ...base, kind: "compare", sources: (c.sources as string[]) ?? [], version: 0 };
    case "doc": return { ...base, kind: "doc", docId: String(c.docId) };
    case "dash": return { ...base, kind: "dash", panel: String(c.panel) };
    case "chart": return { ...base, kind: "chart", compareId: String(c.compareId) };
    case "prompt": return { id: c.id, x: c.x, y: c.y, kind: "prompt", author: authorName(c.createdBy?.id), text: c.text ?? "" };
    default: return null;
  }
}

/** 서버에서 연 캔버스를 화면 카드·연결로 바꾼다. */
export function fromSnapshot(s: CanvasSnapshot, authorName: (id: number | undefined) => string): { cards: Card[]; links: Link[] } {
  return {
    cards: s.cards.map((c) => toLocalCard(c, authorName)).filter((c): c is Card => c !== null),
    links: s.links.map((l) => ({ id: l.id, source: l.source, target: l.target, rel: l.rel, prompt: l.prompt ?? undefined })),
  };
}

/** 화면 카드·연결을 목업 계산 엔진에 다시 넣을 이벤트로 바꾼다(비교·태그 계산용). 위치는 계산에 쓰지 않는다. */
export function replayEvents(cards: Card[], links: Link[]): CanvasEvent[] {
  const events: CanvasEvent[] = [];
  for (const c of cards) {
    if (c.kind === "table") {
      events.push({ type: "add_card", card_id: c.id, table_id: c.tableId, rows: c.rows, cols: c.cols });
      if (c.vcol !== c.cols[0]) events.push({ type: "select_period", card_id: c.id, c: c.vcol });
      for (const [rc, value] of Object.entries(c.edits)) {
        const [r, col] = rc.split(",").map(Number);
        events.push({ type: "edit_cell", card_id: c.id, r, c: col, value });
      }
    } else if (c.kind === "doc") events.push({ type: "add_doc", card_id: c.id, doc_id: c.docId });
    else if (c.kind === "dash") events.push({ type: "add_dash", card_id: c.id, panel: c.panel });
    else if (c.kind === "prompt") events.push({ type: "add_prompt", card_id: c.id, text: c.text });
  }
  // 비교 카드는 원본 표 카드가 모두 들어간 뒤에 만든다
  for (const c of cards) if (c.kind === "compare") events.push({ type: "create_compare", card_id: c.id, sources: c.sources });
  for (const c of cards) if (c.prompt) events.push({ type: "set_card_prompt", card_id: c.id, text: c.prompt });
  for (const l of links) {
    events.push({ type: "add_link", link_id: l.id, source: l.source, target: l.target, rel: l.rel });
    if (l.prompt) events.push({ type: "set_link_prompt", link_id: l.id, text: l.prompt });
  }
  return events;
}
