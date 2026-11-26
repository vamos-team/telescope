package com.telescope.backend.canvas.service;

import com.telescope.backend.canvas.dto.CanvasEventsRequest;
import com.telescope.backend.canvas.dto.CanvasEventsResponse;
import com.telescope.backend.canvas.dto.TextConflict;
import com.telescope.backend.canvas.dto.TextVersions;
import com.telescope.backend.canvas.entity.Canvas;
import com.telescope.backend.canvas.entity.CanvasCard;
import com.telescope.backend.canvas.entity.CanvasEvent;
import com.telescope.backend.canvas.entity.CanvasLink;
import com.telescope.backend.canvas.entity.CanvasMapping;
import com.telescope.backend.canvas.entity.CanvasRequest;
import com.telescope.backend.canvas.entity.CardKind;
import com.telescope.backend.canvas.repository.CanvasCardRepository;
import com.telescope.backend.canvas.repository.CanvasEventRepository;
import com.telescope.backend.canvas.repository.CanvasLinkRepository;
import com.telescope.backend.canvas.repository.CanvasMappingRepository;
import com.telescope.backend.canvas.repository.CanvasRequestRepository;
import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 캔버스 이벤트 묶음을 저장한다. 규약은 {@code docs/backend/api-spec.md} 5장, 결정 이유는 {@code docs/backend/canvas.md}.
 *
 * <ul>
 *   <li>한 묶음은 한 트랜잭션이다. 하나라도 실패하면(형식 오류, 글 충돌) 묶음 전체를 반영하지 않는다.</li>
 *   <li>캔버스 행을 잠그고 처리하므로 같은 캔버스의 묶음은 한 줄로 서고, 이벤트 번호(seq)는 겹치지 않는다.</li>
 *   <li>같은 requestId가 다시 오면 저장하지 않고 처음 응답을 돌려준다.</li>
 *   <li>위치와 글은 다른 컬럼이라 서로 덮어쓰지 않는다. 글은 기준 버전이 다르면 409 K003으로 거절한다.</li>
 *   <li>다른 사람이 이미 지운 카드·연결을 가리키는 수정 이벤트는 무시하고 기록하지 않는다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CanvasEventService {

    private static final String LAYER_SOURCES = "L1_sources";
    private static final String LAYER_WORKSPACE = "L2_workspace";
    private static final String LAYER_EDITS = "L3_edits";
    private static final List<String> LAYER_ORDER = List.of(LAYER_SOURCES, LAYER_WORKSPACE, LAYER_EDITS);

    /** 이벤트 종류별로 바뀌는 컨텍스트 층. 없는 종류(이동)는 컨텍스트에 영향이 없다. */
    private static final Map<String, String> LAYER_OF = Map.ofEntries(
            Map.entry("add_card", LAYER_SOURCES), Map.entry("add_doc", LAYER_SOURCES),
            Map.entry("add_dash", LAYER_SOURCES), Map.entry("remove_card", LAYER_SOURCES),
            Map.entry("add_memo", LAYER_WORKSPACE), Map.entry("edit_memo", LAYER_WORKSPACE),
            Map.entry("set_card_memo", LAYER_WORKSPACE), Map.entry("set_link_memo", LAYER_WORKSPACE),
            Map.entry("add_rows", LAYER_WORKSPACE), Map.entry("remove_row", LAYER_WORKSPACE),
            Map.entry("create_compare", LAYER_WORKSPACE), Map.entry("add_link", LAYER_WORKSPACE),
            Map.entry("remove_link", LAYER_WORKSPACE),
            Map.entry("edit_cell", LAYER_EDITS), Map.entry("revert_card", LAYER_EDITS),
            Map.entry("select_period", LAYER_EDITS), Map.entry("sort_card", LAYER_EDITS),
            Map.entry("confirm_mapping", LAYER_EDITS));

    private final CanvasAccessService access;
    private final CanvasCardRepository cardRepository;
    private final CanvasLinkRepository linkRepository;
    private final CanvasMappingRepository mappingRepository;
    private final CanvasEventRepository eventRepository;
    private final CanvasRequestRepository requestRepository;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    /**
     * 이벤트 묶음을 적용하고 기록한다(소유자·편집 권한).
     *
     * @throws BusinessException 권한 없음(K001, K002), 형식 오류(C001), 글 충돌(K003, data에 현재 글)
     */
    @Transactional
    public CanvasEventsResponse apply(Long canvasId, Long userId, CanvasEventsRequest request) {
        Canvas canvas = access.loadActiveForUpdate(canvasId);
        access.requireEdit(canvas, userId);

        Optional<CanvasRequest> done = requestRepository.findByCanvasIdAndRequestId(canvasId, request.requestId());
        if (done.isPresent()) {
            return CanvasJson.read(done.get().getResponse(), CanvasEventsResponse.class);
        }

        Batch batch = new Batch(canvas, userId, request.requestId());
        List<Map<String, Object>> events = keepLastMovePerCard(request.events());
        for (int i = 0; i < events.size(); i++) {
            applyOne(batch, new EventFields(events.get(i), i), events.get(i));
        }

        if (batch.firstSeq != null) {
            canvas.markEdited(userId, LocalDateTime.now(clock));
        }
        CanvasEventsResponse response = new CanvasEventsResponse(
                canvas.getLastSeq(), batch.firstChangedLayer(), batch.versions);
        requestRepository.save(CanvasRequest.of(canvasId, request.requestId(), userId,
                batch.firstSeq, canvas.getLastSeq(), CanvasJson.write(response)));
        if (batch.firstSeq != null) {
            publisher.publishEvent(new CanvasEventsCommitted(canvasId, batch.firstSeq, canvas.getLastSeq(),
                    request.requestId(), userId, batch.stored, batch.versions));
        }
        return response;
    }

    /**
     * 한 묶음 안에서 같은 카드의 이동은 마지막 위치만 남긴다. 드래그는 이동 이벤트를 많이 만드는데,
     * 중간 위치는 저장할 필요가 없다. 다른 이벤트의 순서는 그대로 둔다.
     */
    static List<Map<String, Object>> keepLastMovePerCard(List<Map<String, Object>> events) {
        Map<Object, Integer> lastMove = new HashMap<>();
        for (int i = 0; i < events.size(); i++) {
            if ("move_card".equals(events.get(i).get("type"))) {
                lastMove.put(events.get(i).get("cardId"), i);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> e = events.get(i);
            if (!"move_card".equals(e.get("type")) || lastMove.get(e.get("cardId")) == i) {
                result.add(e);
            }
        }
        return result;
    }

    /** 이벤트 한 건을 종류에 따라 적용한다. 반영했으면 기록하고, 대상이 이미 없으면 무시한다. */
    private void applyOne(Batch b, EventFields f, Map<String, Object> raw) {
        String type = f.type();
        boolean applied = switch (type) {
            case "add_card" -> addCard(b, f, CardKind.TABLE, tableProps(f));
            case "add_doc" -> addCard(b, f, CardKind.DOC, props("docId", f.string("docId")));
            case "add_dash" -> addCard(b, f, CardKind.DASH, props("panel", f.string("panel")));
            case "create_compare" -> addCompare(b, f);
            case "add_memo" -> addMemo(b, f);
            case "add_rows" -> updateTable(b, f, p -> p.put("rows", union(p.get("rows"), f.integers("rows"))));
            case "remove_row" -> updateTable(b, f, p -> p.put("rows", without(p.get("rows"), f.integer("r"))));
            case "select_period" -> updateTable(b, f, p -> p.put("vcol", f.integer("c")));
            case "sort_card" -> updateTable(b, f, p -> p.put("sort", sortValue(f)));
            case "edit_cell" -> updateTable(b, f, p -> editCell(p, f));
            case "revert_card" -> updateTable(b, f, p -> p.put("edits", new LinkedHashMap<>()));
            case "edit_memo" -> editMemoText(b, f);
            case "set_card_memo" -> setCardMemo(b, f);
            case "set_link_memo" -> setLinkMemo(b, f);
            case "confirm_mapping" -> confirmMapping(b, f);
            case "add_link" -> addLink(b, f);
            case "remove_link" -> removeLink(b, f);
            case "remove_card" -> removeCard(b, f);
            case "move_card" -> moveCard(b, f);
            default -> throw f.invalid("type", "알 수 없는 이벤트입니다: " + type);
        };
        if (applied) {
            b.record(type, raw);
        }
    }

    // ---- 카드 추가 ----

    private boolean addCard(Batch b, EventFields f, CardKind kind, Map<String, Object> props) {
        UUID id = f.uuid("cardId");
        requireNewCardId(b, f, id);
        cardRepository.save(CanvasCard.create(id, b.canvasId(), kind, f.number("x", 0), f.number("y", 0),
                CanvasJson.write(props), b.userId));
        return true;
    }

    /**
     * 비교 카드 또는 시각화 카드를 만든다({@code view}: {@code compare} 기본, {@code chart}). 둘 다 원본 표 카드 목록
     * ({@code sources})을 갖고 같은 비교 계산을 쓴다. 이미 지워졌거나 표가 아닌 원본은 빼고 저장한다.
     */
    private boolean addCompare(Batch b, EventFields f) {
        String view = f.optionalString("view");
        CardKind kind;
        if (view == null || "compare".equals(view)) {
            kind = CardKind.COMPARE;
        } else if ("chart".equals(view)) {
            kind = CardKind.CHART;
        } else {
            throw f.invalid("view", "compare 또는 chart여야 합니다");
        }
        List<String> sources = new ArrayList<>();
        for (UUID source : f.uuids("sources")) {
            if (card(b, source).filter(c -> c.getKind() == CardKind.TABLE).isPresent()) {
                sources.add(source.toString());
            }
        }
        return addCard(b, f, kind, props("sources", sources));
    }

    /** 메모 카드를 만든다. 작성자는 요청한 사용자다(화면이 보낸 {@code author}는 쓰지 않는다). */
    private boolean addMemo(Batch b, EventFields f) {
        String text = f.string("text");
        addCard(b, f, CardKind.MEMO, new LinkedHashMap<>());
        UUID id = f.uuid("cardId");
        CanvasCard card = card(b, id).orElseThrow();
        b.textVersion(id, card.changeText(text));
        return true;
    }

    private Map<String, Object> tableProps(EventFields f) {
        Map<String, Object> p = new LinkedHashMap<>();
        List<Integer> cols = new ArrayList<>(new TreeSet<>(f.integers("cols")));
        p.put("tableId", f.string("tableId"));
        p.put("rows", new ArrayList<>(new TreeSet<>(f.integers("rows"))));
        p.put("cols", cols);
        p.put("vcol", cols.isEmpty() ? null : cols.get(0));
        p.put("edits", new LinkedHashMap<>());
        p.put("sort", null);
        return p;
    }

    private void requireNewCardId(Batch b, EventFields f, UUID id) {
        if (cardRepository.existsById(id) || linkRepository.existsById(id)) {
            throw f.invalid("cardId", "이미 있는 ID입니다");
        }
    }

    // ---- 표 카드 수정 ----

    private boolean updateTable(Batch b, EventFields f, java.util.function.Consumer<Map<String, Object>> change) {
        Optional<CanvasCard> found = card(b, f.uuid("cardId"));
        if (found.isEmpty()) {
            return false;
        }
        CanvasCard card = found.get();
        if (card.getKind() != CardKind.TABLE) {
            throw f.invalid("cardId", "공시 표 카드가 아닙니다");
        }
        Map<String, Object> props = CanvasJson.readMap(card.getProps());
        change.accept(props);
        card.replaceProps(CanvasJson.write(props));
        return true;
    }

    private static Object sortValue(EventFields f) {
        Object key = f.raw("key");
        return key == null ? null : List.of(key, f.bool("desc"));
    }

    @SuppressWarnings("unchecked")
    private static void editCell(Map<String, Object> props, EventFields f) {
        Map<String, Object> edits = (Map<String, Object>) props.computeIfAbsent("edits", k -> new LinkedHashMap<>());
        edits.put(f.integer("r") + "," + f.integer("c"), f.nullableNumber("value"));
    }

    private static List<Integer> union(Object current, List<Integer> added) {
        TreeSet<Integer> rows = toIntSet(current);
        rows.addAll(added);
        return new ArrayList<>(rows);
    }

    private static List<Integer> without(Object current, int row) {
        TreeSet<Integer> rows = toIntSet(current);
        rows.remove(row);
        return new ArrayList<>(rows);
    }

    private static TreeSet<Integer> toIntSet(Object value) {
        TreeSet<Integer> set = new TreeSet<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                set.add(((Number) o).intValue());
            }
        }
        return set;
    }

    // ---- 글 수정 (버전 비교) ----

    /** 메모 카드 본문을 고친다. 기준 버전이 다르면 409. */
    private boolean editMemoText(Batch b, EventFields f) {
        Optional<CanvasCard> found = card(b, f.uuid("cardId"));
        if (found.isEmpty()) {
            return false;
        }
        CanvasCard card = found.get();
        if (card.getKind() != CardKind.MEMO) {
            throw f.invalid("cardId", "메모 카드가 아닙니다");
        }
        requireVersion(f, card.getId(), "text", card.getText(), card.getTextVersion());
        b.textVersion(card.getId(), card.changeText(f.string("text")));
        return true;
    }

    /** 카드 메모를 고친다(메모 카드 제외). 빈 값이면 지운다. 기준 버전이 다르면 409. */
    private boolean setCardMemo(Batch b, EventFields f) {
        Optional<CanvasCard> found = card(b, f.uuid("cardId"));
        if (found.isEmpty()) {
            return false;
        }
        CanvasCard card = found.get();
        if (card.getKind() == CardKind.MEMO) {
            throw f.invalid("cardId", "메모 카드에는 카드 메모를 붙이지 않습니다");
        }
        requireVersion(f, card.getId(), "memo", card.getMemo(), card.getMemoVersion());
        b.memoVersion(card.getId(), card.changeMemo(f.string("text")));
        return true;
    }

    /** 선 메모를 고친다. 고친 사람이 작성자({@code memoBy})가 되고, 빈 값이면 메모와 작성자를 지운다. 기준 버전이 다르면 409. */
    private boolean setLinkMemo(Batch b, EventFields f) {
        Optional<CanvasLink> found = link(b, f.uuid("linkId"));
        if (found.isEmpty()) {
            return false;
        }
        CanvasLink link = found.get();
        requireVersion(f, link.getId(), "memo", link.getMemo(), link.getMemoVersion());
        b.memoVersion(link.getId(), link.changeMemo(f.string("text"), b.userId));
        return true;
    }

    /**
     * 고치기 시작한 버전({@code baseVersion})이 지금 버전과 같은지 확인한다. 다르면 다른 사람이 먼저 고친 것이므로
     * 묶음 전체를 409 K003으로 거절하고, 화면이 다시 고칠 수 있게 지금 글을 함께 돌려준다.
     */
    private static void requireVersion(EventFields f, UUID targetId, String field, String currentText, int currentVersion) {
        int base = f.integer("baseVersion");
        if (base != currentVersion) {
            throw new BusinessException(ErrorCode.CANVAS_TEXT_CONFLICT, ErrorCode.CANVAS_TEXT_CONFLICT.getMessage(),
                    new TextConflict(targetId, field, currentText, currentVersion));
        }
    }

    // ---- 계정 대응 ----

    /**
     * 계정 대응을 확정({@code true})·거부({@code false})하거나 취소({@code null})한다. 같은 개념·회사의 이전 결정을 덮어쓴다.
     * 취소는 대응 기록을 지운다. 지울 기록이 없으면 반영한 것이 없으므로 기록하지 않는다.
     */
    private boolean confirmMapping(Batch b, EventFields f) {
        String conceptId = f.string("conceptId");
        String company = f.string("company");
        if (f.raw("accepted") == null) {
            Optional<CanvasMapping> existing =
                    mappingRepository.findByCanvasIdAndConceptIdAndCompany(b.canvasId(), conceptId, company);
            existing.ifPresent(mappingRepository::delete);
            return existing.isPresent();
        }
        String cells = CanvasJson.write(f.list("cells"));
        boolean accepted = f.bool("accepted");
        mappingRepository.findByCanvasIdAndConceptIdAndCompany(b.canvasId(), conceptId, company)
                .ifPresentOrElse(
                        m -> m.decide(cells, accepted, b.userId),
                        () -> mappingRepository.save(
                                CanvasMapping.create(b.canvasId(), conceptId, company, cells, accepted, b.userId)));
        return true;
    }

    // ---- 연결 ----

    private boolean addLink(Batch b, EventFields f) {
        UUID id = f.uuid("linkId");
        UUID source = f.uuid("source");
        UUID target = f.uuid("target");
        if (source.equals(target)) {
            throw f.invalid("target", "같은 카드끼리는 연결할 수 없습니다");
        }
        if (cardRepository.existsById(id) || linkRepository.existsById(id)) {
            throw f.invalid("linkId", "이미 있는 ID입니다");
        }
        if (card(b, source).isEmpty() || card(b, target).isEmpty()) {
            return false;  // 양 끝 중 하나가 이미 지워졌다
        }
        // 관계 종류(rel)는 저장하지 않는다. 예전 화면이 보내도 무시한다(#123)
        linkRepository.save(CanvasLink.create(id, b.canvasId(), source, target, b.userId));
        return true;
    }

    private boolean removeLink(Batch b, EventFields f) {
        Optional<CanvasLink> found = link(b, f.uuid("linkId"));
        found.ifPresent(linkRepository::delete);
        return found.isPresent();
    }

    // ---- 카드 삭제·이동 ----

    /**
     * 카드를 지우고, 그 카드에 닿는 연결과 비교·시각화 카드의 원본 목록에서도 뺀다.
     * 비교 카드를 지워도 그 비교로 만든 시각화 카드는 남는다(시각화는 원본 표를 직접 가리킨다).
     */
    private boolean removeCard(Batch b, EventFields f) {
        UUID id = f.uuid("cardId");
        Optional<CanvasCard> found = card(b, id);
        if (found.isEmpty()) {
            return false;
        }
        List<CanvasLink> touching = linkRepository.findByCanvasIdOrderByCreatedAtAsc(b.canvasId()).stream()
                .filter(l -> l.touches(id)).toList();
        linkRepository.deleteAll(touching);
        for (CardKind kind : List.of(CardKind.COMPARE, CardKind.CHART)) {
            for (CanvasCard derived : cardRepository.findByCanvasIdAndKind(b.canvasId(), kind)) {
                Map<String, Object> props = CanvasJson.readMap(derived.getProps());
                if (props.get("sources") instanceof List<?> sources && sources.contains(id.toString())) {
                    props.put("sources", sources.stream().filter(s -> !id.toString().equals(s)).toList());
                    derived.replaceProps(CanvasJson.write(props));
                }
            }
        }
        cardRepository.delete(found.get());
        return true;
    }

    private boolean moveCard(Batch b, EventFields f) {
        Optional<CanvasCard> found = card(b, f.uuid("cardId"));
        found.ifPresent(c -> c.moveTo(f.number("x"), f.number("y")));
        return found.isPresent();
    }

    // ---- 공통 ----

    private Optional<CanvasCard> card(Batch b, UUID id) {
        return cardRepository.findByCanvasIdAndId(b.canvasId(), id);
    }

    private Optional<CanvasLink> link(Batch b, UUID id) {
        return linkRepository.findByCanvasIdAndId(b.canvasId(), id);
    }

    private static Map<String, Object> props(String key, Object value) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(key, value);
        return p;
    }

    /** 한 묶음을 처리하는 동안의 상태: 발급한 이벤트 번호, 기록한 이벤트, 바뀐 글 버전, 처음 바뀐 층. */
    private final class Batch {
        private final Canvas canvas;
        private final Long userId;
        private final UUID requestId;
        private Long firstSeq;
        private int firstLayerIndex = Integer.MAX_VALUE;
        private final List<Map<String, Object>> stored = new ArrayList<>();
        private final Map<String, TextVersions> versions = new LinkedHashMap<>();

        private Batch(Canvas canvas, Long userId, UUID requestId) {
            this.canvas = canvas;
            this.userId = userId;
            this.requestId = requestId;
        }

        private Long canvasId() {
            return canvas.getId();
        }

        /** 반영한 이벤트에 번호를 붙여 기록한다. */
        private void record(String type, Map<String, Object> raw) {
            long seq = canvas.nextSeq();
            if (firstSeq == null) {
                firstSeq = seq;
            }
            eventRepository.save(CanvasEvent.of(canvas.getId(), seq, requestId, userId, type, CanvasJson.write(raw)));
            stored.add(raw);
            String layer = LAYER_OF.get(type);
            if (layer != null) {
                firstLayerIndex = Math.min(firstLayerIndex, LAYER_ORDER.indexOf(layer));
            }
        }

        private void textVersion(UUID id, int version) {
            versions.merge(id.toString(), new TextVersions(version, null), (old, n) -> old.withText(version));
        }

        private void memoVersion(UUID id, int version) {
            versions.merge(id.toString(), new TextVersions(null, version), (old, n) -> old.withMemo(version));
        }

        private String firstChangedLayer() {
            return firstLayerIndex == Integer.MAX_VALUE ? null : LAYER_ORDER.get(firstLayerIndex);
        }
    }
}
