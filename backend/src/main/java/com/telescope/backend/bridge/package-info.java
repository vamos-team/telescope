/**
 * 같은 앱 안에서 기능 사이를 잇는 구현. 서비스를 나누면 이 구현을 HTTP 호출 구현으로 바꾼다.
 * 기능 패키지(canvas, document, user)는 서로를 직접 참조하지 않고 각자의 인터페이스만 쓴다.
 */
package com.telescope.backend.bridge;
