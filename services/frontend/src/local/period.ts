/** 기간을 분기 단위로 비교한다. 연간 수치는 4분기에 끝난다. */
export function quarterIndex(label?: string): number | undefined {
  const value = label?.trim() ?? "";
  const date = /^(\d{4})-(0[1-9]|1[0-2])-\d{2}$/.exec(value);
  if (date) return Number(date[1]) * 4 + Math.ceil(Number(date[2]) / 3);
  const yearFirst = /^(\d{2}|\d{4})(?:년\s*|[.\s]+)([1-4])(?:Q|분기)/i.exec(value);
  const isoQuarter = /^(\d{4})Q([1-4])$/i.exec(value);
  const quarterFirst = /^([1-4])Q(\d{2}|\d{4})(?!\d)/i.exec(value);
  const year = yearFirst?.[1] ?? isoQuarter?.[1] ?? quarterFirst?.[2] ?? /^\d{4}$/.exec(value)?.[0];
  if (!year) return;
  return (Number(year) + (year.length === 2 ? 2000 : 0)) * 4 + Number(yearFirst?.[2] ?? isoQuarter?.[2] ?? quarterFirst?.[1] ?? 4);
}

export function withinQuarter(period: string | undefined, cutoff?: number): boolean {
  const quarter = quarterIndex(period);
  return cutoff === undefined || quarter === undefined || quarter <= cutoff;
}
