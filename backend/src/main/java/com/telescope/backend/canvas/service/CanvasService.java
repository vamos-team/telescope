package com.telescope.backend.canvas.service;

import com.telescope.backend.canvas.dto.CanvasActivityResponse;
import com.telescope.backend.canvas.dto.CanvasCreateRequest;
import com.telescope.backend.canvas.dto.CanvasDetailResponse;
import com.telescope.backend.canvas.dto.CanvasLinkResponse;
import com.telescope.backend.canvas.dto.CanvasSharesResponse;
import com.telescope.backend.canvas.dto.CanvasSummaryResponse;
import com.telescope.backend.canvas.dto.ShareMember;
import com.telescope.backend.canvas.dto.ShareMemberResponse;
import com.telescope.backend.canvas.dto.UserRef;
import com.telescope.backend.canvas.entity.Canvas;
import com.telescope.backend.canvas.entity.CanvasCard;
import com.telescope.backend.canvas.entity.CanvasEvent;
import com.telescope.backend.canvas.entity.CanvasLink;
import com.telescope.backend.canvas.entity.CanvasRole;
import com.telescope.backend.canvas.entity.CanvasShare;
import com.telescope.backend.canvas.entity.CardKind;
import com.telescope.backend.canvas.repository.CanvasCardRepository;
import com.telescope.backend.canvas.repository.CanvasEventRepository;
import com.telescope.backend.canvas.repository.CanvasLinkRepository;
import com.telescope.backend.canvas.repository.CanvasRepository;
import com.telescope.backend.canvas.repository.CanvasShareRepository;
import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 캔버스 목록·생성·이름 변경·삭제·열기, 활동 로그, 공유 명단.
 * 카드·연결 조작은 {@link CanvasEventService}가 맡는다. 권한 규칙은 {@link CanvasAccessService}.
 */
@Service
@RequiredArgsConstructor
public class CanvasService {

    static final String SET_SHARE = "set_share";

    /** 활동 로그에 보이는 이벤트와 문구. 이동·행 선택처럼 잦고 사소한 조작은 남기지 않는다. */
    static final Map<String, String> ACTIVITY_TEXT = Map.ofEntries(
            Map.entry("add_card", "공시 표를 올림"), Map.entry("add_doc", "자료를 올림"),
            Map.entry("add_dash", "대시보드를 올림"),
            Map.entry("add_memo", "메모를 남김"), Map.entry("edit_memo", "메모를 고침"),
            Map.entry("set_card_memo", "카드 메모를 고침"), Map.entry("set_link_memo", "선 메모를 고침"),
            Map.entry("edit_cell", "값을 수정함"), Map.entry("create_compare", "비교 카드를 만듦"),
            Map.entry("confirm_mapping", "계정 대응을 확정함"), Map.entry("add_link", "카드를 연결함"),
            Map.entry("remove_link", "연결을 지움"),
            Map.entry("remove_card", "카드를 지움"), Map.entry(SET_SHARE, "공유 권한을 바꿈"));

    private static final int ACTIVITY_LIMIT = 200;

    private final CanvasRepository canvasRepository;
    private final CanvasShareRepository shareRepository;
    private final CanvasCardRepository cardRepository;
    private final CanvasLinkRepository linkRepository;
    private final CanvasEventRepository eventRepository;
    private final CanvasAccessService access;
    private final CanvasPeople people;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    /**
     * 내가 볼 수 있는 캔버스 목록(최근 수정 순).
     *
     * @param filter {@code all}(소유 + 초대받은) / {@code mine}(소유) / {@code shared}(초대받은)
     */
    @Transactional(readOnly = true)
    public List<CanvasSummaryResponse> list(Long userId, String filter) {
        List<Canvas> canvases = switch (filter == null ? "all" : filter) {
            case "all" -> canvasRepository.findVisible(userId);
            case "mine" -> canvasRepository.findOwned(userId);
            case "shared" -> canvasRepository.findShared(userId);
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT, "filter: all, mine, shared 중 하나여야 합니다");
        };
        if (canvases.isEmpty()) {
            return List.of();
        }
        List<Long> ids = canvases.stream().map(Canvas::getId).toList();
        Map<Long, CanvasRole> invited = shareRepository.findByUserIdAndCanvasIdIn(userId, ids).stream()
                .collect(Collectors.toMap(CanvasShare::getCanvasId, CanvasShare::getRole));
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : cardRepository.countByCanvasIds(ids)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        Map<Long, UserRef> refs = people.refs(canvases.stream().flatMap(c -> summaryPeople(c).stream()).toList());
        return canvases.stream()
                .map(c -> summary(c, c.getOwnerId().equals(userId) ? CanvasRole.OWNER : invited.get(c.getId()),
                        counts.getOrDefault(c.getId(), 0L), refs))
                .toList();
    }

    /**
     * 새 캔버스를 만든다. 만든 사람이 소유자가 되고, {@code editorIds}는 편집 권한으로 바로 초대된다.
     *
     * @throws BusinessException 없는 사용자를 초대하면 {@code 404 C002}
     */
    @Transactional
    public CanvasSummaryResponse create(Long userId, CanvasCreateRequest request) {
        Canvas canvas = canvasRepository.save(Canvas.create(request.name(), request.quarter(), userId));
        Set<Long> editors = new LinkedHashSet<>(request.editorIds() == null ? List.of() : request.editorIds());
        editors.remove(userId);
        List<CanvasShare> shares = editors.stream().map(id -> CanvasShare.of(canvas.getId(), id, CanvasRole.EDIT)).toList();
        saveSharesOrNotFound(shares);
        return summary(canvas, CanvasRole.OWNER, 0, people.refs(summaryPeople(canvas)));
    }

    /** 이름을 바꾼다(소유자·편집). 비면 "제목 없는 캔버스". */
    @Transactional
    public CanvasSummaryResponse rename(Long canvasId, Long userId, String name) {
        Canvas canvas = access.loadActive(canvasId);
        CanvasRole role = access.requireEdit(canvas, userId);
        canvas.rename(name);
        canvas.markEdited(userId, LocalDateTime.now(clock));
        Canvas saved = canvasRepository.save(canvas);
        return summary(saved, role, cardCount(canvasId), people.refs(summaryPeople(saved)));
    }

    /**
     * 캔버스를 지운다(소유자만). 행은 남기고 삭제 표시만 해서 이 캔버스로 만든 결과물이 참조를 잃지 않게 한다.
     * 커밋 뒤 실시간 전달(#83)이 다른 권한자의 구독을 끊는다.
     */
    @Transactional
    public void delete(Long canvasId, Long userId) {
        Canvas canvas = access.loadActiveForUpdate(canvasId);
        access.requireOwner(canvas, userId);
        canvas.delete(LocalDateTime.now(clock));
        Set<Long> members = shareRepository.findByCanvasIdOrderByIdAsc(canvasId).stream()
                .map(CanvasShare::getUserId).collect(Collectors.toCollection(LinkedHashSet::new));
        publisher.publishEvent(new CanvasAccessChanged(canvasId, members, Map.of(), true));
    }

    /**
     * 캔버스를 연다(권한자 모두). 서버에 저장된 상태를 그대로 돌려주므로 새로고침·재접속·서버 재시작 뒤에도 같다.
     * 일부를 복원하지 못해도(비교 원본이나 시각화의 비교 카드가 사라짐) 나머지를 돌려주고 {@code warnings}에 담는다.
     */
    @Transactional(readOnly = true)
    public CanvasDetailResponse open(Long canvasId, Long userId) {
        Canvas canvas = access.loadActive(canvasId);
        CanvasRole role = access.requireView(canvas, userId);
        List<CanvasCard> cards = cardRepository.findByCanvasIdOrderByCreatedAtAsc(canvasId);
        List<CanvasLink> linkRows = linkRepository.findByCanvasIdOrderByCreatedAtAsc(canvasId);
        // 카드 작성자·선 메모 작성자·소유자·마지막 수정자를 모아 한 번에 읽는다
        List<Long> personIds = new ArrayList<>(summaryPeople(canvas));
        cards.forEach(c -> personIds.add(c.getCreatedBy()));
        linkRows.forEach(l -> personIds.add(l.getMemoBy()));
        Map<Long, UserRef> refs = people.refs(personIds);

        Set<String> cardIds = cards.stream().map(c -> c.getId().toString()).collect(Collectors.toSet());
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> cardViews = new ArrayList<>();
        for (CanvasCard card : cards) {
            Map<String, Object> view = cardView(card, refs);
            warnings.addAll(restoreWarnings(card, view, cardIds));
            cardViews.add(view);
        }
        List<CanvasLinkResponse> links = linkRows.stream()
                .map(l -> new CanvasLinkResponse(l.getId(), l.getSourceCardId(), l.getTargetCardId(),
                        l.getMemo(), l.getMemoVersion(), CanvasPeople.of(refs, l.getMemoBy())))
                .toList();
        return new CanvasDetailResponse(summary(canvas, role, cards.size(), refs), cardViews, links,
                canvas.getLastSeq(), warnings);
    }

    /** 활동 로그(권한자 모두). 최근 순 200건. 작성자는 이벤트를 보낸 인증 사용자다. */
    @Transactional(readOnly = true)
    public List<CanvasActivityResponse> activities(Long canvasId, Long userId) {
        Canvas canvas = access.loadActive(canvasId);
        access.requireView(canvas, userId);
        List<CanvasEvent> events = eventRepository.findByCanvasIdAndTypeInOrderBySeqDesc(
                canvasId, ACTIVITY_TEXT.keySet(), PageRequest.of(0, ACTIVITY_LIMIT));
        Map<Long, UserRef> refs = people.refs(events.stream().map(CanvasEvent::getAuthorId).toList());
        return events.stream()
                .map(e -> new CanvasActivityResponse(CanvasPeople.of(refs, e.getAuthorId()), ACTIVITY_TEXT.get(e.getType()),
                        cardIdOf(e), e.getCreatedAt()))
                .toList();
    }

    /** 공유 명단(권한자 모두). 소유자는 {@code ownerId}·{@code owner}로만 나온다. 이름·부서를 함께 준다(#149). */
    @Transactional(readOnly = true)
    public CanvasSharesResponse shares(Long canvasId, Long userId) {
        Canvas canvas = access.loadActive(canvasId);
        access.requireView(canvas, userId);
        return sharesOf(canvas);
    }

    /**
     * 공유 명단을 통째로 바꾼다(소유자만). 목록에서 빠진 사람은 권한이 사라진다.
     * 변경은 이벤트({@code set_share})로 기록해 활동 로그와 이벤트 번호 순서에 들어가게 하고,
     * 커밋 뒤 실시간 전달(#83)이 권한이 사라진 사람의 구독을 끊는다.
     *
     * @throws BusinessException 소유자를 넣거나 같은 사람이 두 번 나오면 {@code 400 C001}, 없는 사용자면 {@code 404 C002}
     */
    @Transactional
    public CanvasSharesResponse replaceShares(Long canvasId, Long userId, List<ShareMember> members) {
        Canvas canvas = access.loadActiveForUpdate(canvasId);
        access.requireOwner(canvas, userId);
        Map<Long, CanvasRole> wanted = validateMembers(canvas, members);

        Map<Long, CanvasShare> current = shareRepository.findByCanvasIdOrderByIdAsc(canvasId).stream()
                .collect(Collectors.toMap(CanvasShare::getUserId, Function.identity()));
        Set<Long> revoked = new LinkedHashSet<>();
        Map<Long, CanvasRole> changed = new LinkedHashMap<>();
        for (CanvasShare share : current.values()) {
            CanvasRole role = wanted.get(share.getUserId());
            if (role == null) {
                shareRepository.delete(share);
                revoked.add(share.getUserId());
            } else if (role != share.getRole()) {
                share.changeRole(role);
                changed.put(share.getUserId(), role);
            }
        }
        List<CanvasShare> added = new ArrayList<>();
        wanted.forEach((id, role) -> {
            if (!current.containsKey(id)) {
                added.add(CanvasShare.of(canvasId, id, role));
                changed.put(id, role);
            }
        });
        saveSharesOrNotFound(added);

        if (!revoked.isEmpty() || !changed.isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", SET_SHARE);
            payload.put("members", members);
            long seq = canvas.nextSeq();
            UUID requestId = UUID.randomUUID();
            eventRepository.save(CanvasEvent.of(canvasId, seq, requestId, userId, SET_SHARE, CanvasJson.write(payload)));
            publisher.publishEvent(new CanvasEventsCommitted(canvasId, seq, seq, requestId, people.ref(userId),
                    List.of(payload), Map.of()));
            publisher.publishEvent(new CanvasAccessChanged(canvasId, revoked, changed, false));
        }
        return sharesOf(canvas);
    }

    private Map<Long, CanvasRole> validateMembers(Canvas canvas, List<ShareMember> members) {
        Map<Long, CanvasRole> wanted = new LinkedHashMap<>();
        for (ShareMember m : members) {
            if (m.userId().equals(canvas.getOwnerId())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "members: 소유자는 공유 명단에 넣을 수 없습니다");
            }
            if (m.role() == CanvasRole.OWNER) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "members.role: edit 또는 view만 가능합니다");
            }
            if (wanted.put(m.userId(), m.role()) != null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "members: 같은 사람이 두 번 있습니다");
            }
        }
        return wanted;
    }

    /** 초대를 저장한다. 없는 사용자(FK 위반)면 404로 바꾼다. 저장 한 번으로 존재 확인까지 끝나서 미리 조회하지 않는다. */
    private void saveSharesOrNotFound(List<CanvasShare> shares) {
        if (shares.isEmpty()) {
            return;
        }
        try {
            shareRepository.saveAllAndFlush(shares);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "초대할 사용자를 찾을 수 없습니다.");
        }
    }

    /** 공유 명단 응답. 소유자와 명단의 사람을 한 번에 읽어 이름·부서를 붙인다. */
    private CanvasSharesResponse sharesOf(Canvas canvas) {
        List<CanvasShare> shares = shareRepository.findByCanvasIdOrderByIdAsc(canvas.getId());
        List<Long> ids = new ArrayList<>(List.of(canvas.getOwnerId()));
        shares.forEach(s -> ids.add(s.getUserId()));
        Map<Long, UserRef> refs = people.refs(ids);
        List<ShareMemberResponse> members = shares.stream()
                .map(s -> ShareMemberResponse.of(s.getUserId(), s.getRole(), CanvasPeople.of(refs, s.getUserId())))
                .toList();
        return new CanvasSharesResponse(canvas.getOwnerId(), CanvasPeople.of(refs, canvas.getOwnerId()), members);
    }

    private long cardCount(Long canvasId) {
        return cardRepository.countByCanvasIds(List.of(canvasId)).stream()
                .findFirst().map(r -> (Long) r[1]).orElse(0L);
    }

    /** 목록 한 줄에 나오는 사람(소유자, 마지막 수정자). 이름을 한 번에 읽으려고 먼저 모은다. */
    private static List<Long> summaryPeople(Canvas c) {
        return List.of(c.getOwnerId(), updatedByOf(c));
    }

    /** 마지막으로 고친 사람. 아직 아무도 고치지 않았으면 소유자. */
    private static Long updatedByOf(Canvas c) {
        return c.getLastEditedBy() != null ? c.getLastEditedBy() : c.getOwnerId();
    }

    /** 목록 한 줄. 사람 이름은 미리 읽어 둔 표({@code refs})에서 꺼낸다. */
    private static CanvasSummaryResponse summary(Canvas c, CanvasRole myRole, long cardCount, Map<Long, UserRef> refs) {
        LocalDateTime updatedAt = c.getLastEditedAt() != null ? c.getLastEditedAt() : c.getCreatedAt();
        return new CanvasSummaryResponse(c.getId(), c.getName(), c.getQuarter(), CanvasPeople.of(refs, c.getOwnerId()),
                myRole, updatedAt, CanvasPeople.of(refs, updatedByOf(c)), cardCount);
    }

    /** 카드 하나를 화면이 쓰는 모양(공통 필드 + 종류별 필드)으로 편다. 작성자 이름은 미리 읽어 둔 표에서 꺼낸다. */
    private static Map<String, Object> cardView(CanvasCard card, Map<Long, UserRef> refs) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", card.getId());
        view.put("kind", card.getKind());
        view.put("x", card.getX());
        view.put("y", card.getY());
        view.putAll(CanvasJson.readMap(card.getProps()));
        if (card.getKind() == CardKind.MEMO) {
            view.put("text", card.getText());
            view.put("textVersion", card.getTextVersion());
        } else {
            view.put("memo", card.getMemo());
            view.put("memoVersion", card.getMemoVersion());
        }
        view.put("createdBy", CanvasPeople.of(refs, card.getCreatedBy()));
        return view;
    }

    /** 다른 카드를 가리키는 카드의 대상이 사라졌는지 확인한다. 표·문서 원본이 사라진 경우는 조회 서비스가 생기면 더한다. */
    private static List<String> restoreWarnings(CanvasCard card, Map<String, Object> view, Set<String> cardIds) {
        List<String> warnings = new ArrayList<>();
        boolean derived = card.getKind() == CardKind.COMPARE || card.getKind() == CardKind.CHART;
        if (derived && view.get("sources") instanceof List<?> sources) {
            Set<Object> missing = new HashSet<>(sources);
            missing.removeAll(cardIds);
            if (!missing.isEmpty()) {
                String name = card.getKind() == CardKind.CHART ? "시각화 카드 " : "비교 카드 ";
                warnings.add(name + card.getId() + "의 원본 카드 " + missing.size() + "개가 없습니다.");
            }
        }
        return warnings;
    }

    private static UUID cardIdOf(CanvasEvent event) {
        Object id = CanvasJson.readMap(event.getPayload()).get("cardId");
        try {
            return id == null ? null : UUID.fromString(id.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
