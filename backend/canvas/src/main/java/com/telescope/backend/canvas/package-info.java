/**
 * 협업 캔버스: 캔버스, 공유 권한(소유자·편집·보기), 카드·연결 상태, 편집 이벤트.
 *
 * <p>규약은 {@code docs/backend/api-spec.md} 4·5장, 설계와 결정 이유는 {@code docs/backend/canvas.md}.
 * 실시간 전달(WebSocket)은 #83에서 이 패키지가 발행하는 커밋 후 이벤트를 받아 보낸다.
 */
package com.telescope.backend.canvas;
