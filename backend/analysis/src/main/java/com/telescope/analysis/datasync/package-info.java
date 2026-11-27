/**
 * 재무 데이터(DuckDB) 적재 배치의 실행과 이력, 지금 쓰는 판(버전) 관리(#135).
 *
 * <p>Spring 스케줄러가 정해진 때에 시작하고, 실행은 ai/server의 작업으로 한다. 결과는 backend가 주기적으로 확인해
 * 새 판을 활성화한다. 설계와 결정 이유는 {@code docs/backend/datasync.md}.
 */
package com.telescope.analysis.datasync;
