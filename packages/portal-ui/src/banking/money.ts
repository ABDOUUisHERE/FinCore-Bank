import type { Currency } from "./api";
export function minor(value: string): bigint {
  if (!/^-?(0|[1-9][0-9]{0,22})(\.[0-9]{1,2})?$/.test(value)) throw new Error("Invalid exact decimal amount");
  const negative = value.startsWith("-");
  const [whole, fraction = ""] = (negative ? value.slice(1) : value).split(".");
  const result = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"));
  return negative ? -result : result;
}
export function decimal(value: bigint): string {
  const absolute = value < 0n ? -value : value;
  return (value < 0n ? "-" : "") + String(absolute / 100n) + "." + String(absolute % 100n).padStart(2, "0");
}
export function amount(value: string): string {
  if (!/^(0|[1-9][0-9]{0,17})(\.[0-9]{1,2})?$/.test(value) || minor(value) <= 0n) throw new Error("Enter a positive amount with at most two decimal places.");
  return decimal(minor(value));
}
export function money(value: string, currency: Currency): string {
  const exact = minor(value);
  const absolute = exact < 0n ? -exact : exact;
  return (exact < 0n ? "-" : "") + new Intl.NumberFormat("en").format(absolute / 100n) + "." + String(absolute % 100n).padStart(2, "0") + " " + currency;
}
export function exportRows(filename: string, headings: string[], rows: (string | number | null)[][]) {
  const cell = (value: string | number | null) => {
    const text = String(value ?? "");
    return '"' + (/^[=+\-@\t\r]/.test(text) ? "'" + text : text).replaceAll('"', '""') + '"';
  };
  const csv = [headings, ...rows].map(row => row.map(cell).join(",")).join("\r\n");
  const url = URL.createObjectURL(new Blob(["\uFEFF", csv], { type: "text/csv;charset=utf-8" }));
  const anchor = document.createElement("a"); anchor.href = url; anchor.download = filename; anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
