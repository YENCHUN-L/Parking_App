#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional
from urllib import error, request


VEHICLES = ["small_car", "motorcycle", "heavy_motorcycle", "bus"]
WEEKDAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]


@dataclass
class PayexRecord:
    id: str
    payex: str


def load_dotenv_file(path: Path) -> None:
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


def parse_payex_id_only(path: Path) -> List[PayexRecord]:
    text = path.read_text(encoding="utf-8")
    pattern = re.compile(r"ID:\s*(.*?)\r?\nPayex:\s*(.*?)(?:\r?\n-+|\Z)", re.S)
    out: List[PayexRecord] = []
    for m in pattern.finditer(text):
        pid = m.group(1).strip()
        payex = m.group(2).strip()
        if not pid:
            continue
        out.append(PayexRecord(id=pid, payex=payex))
    return out


def clamp_schema(payload: Dict[str, Any], fallback_id: str, fallback_payex: str) -> Dict[str, Any]:
    result = {
        "id": str(payload.get("id") or fallback_id),
        "payex": str(payload.get("payex") or fallback_payex),
        "vehicle_weekday_amount": {},
    }
    src_map = payload.get("vehicle_weekday_amount") or {}
    for vehicle in VEHICLES:
        src_week = src_map.get(vehicle) or {}
        clean_week = {}
        for day in WEEKDAYS:
            val = src_week.get(day)
            if val is None:
                clean_week[day] = None
                continue
            try:
                parsed = float(val)
                clean_week[day] = parsed if math.isfinite(parsed) else None
            except Exception:
                clean_week[day] = None
        result["vehicle_weekday_amount"][vehicle] = clean_week
    return result


def build_prompt(rec: PayexRecord) -> str:
    return f"""
你是停車費率結構化助手。請只回傳 JSON，禁止多餘文字。

任務：根據以下 payex 文字，判斷各車型在週一到週日的「停車價格」。
如果資料不足，該欄位填 null。

解析規則（通用）：
1) 目標是各車型在 mon~sun 的停車價格數字（只填數字，例如 40）。
2) 忽略所有月租相關金額（例如 xxx元/月、月票、半年票、年租）。
3) 可使用計時/計次等非月租價格作為該車型價格來源。
4) 時段判定：白天 = 06:00-22:00；夜間 = 22:00-06:00。
5) 只採用「白天有覆蓋」的價格；純夜間時段價格一律忽略。
6) 純夜間範例（要忽略）：22-08、20-08、18-06、00-06、23-07、24-08。
7) 若同一天有多個可用價格（例如展覽/非展覽、不同時段），在符合白天條件的價格中取最低金額。
8) 若規則寫「週一至週日」或「全日」且可合理套用，則 7 天都可填同一價格。
9) 若未明確提及星期（例如僅寫「計時40元/時」），則預設視為適用週一至週日。
10) 若該車型只有夜間價格、沒有任何白天可用價格，則填 null。
11) 未提及的車型一律填 null。

關鍵示例：
- 「100元/時(08-22)、70元/時(08-22)、40元/時(22-08)」=> 應輸出 70（忽略夜間 40）。

車型鍵名固定：
- small_car (小客車)
- motorcycle (機車)
- heavy_motorcycle (重型機車)
- bus (大客車)

星期鍵名固定：mon,tue,wed,thu,fri,sat,sun

輸出 JSON 格式固定如下：
{{
  "id": "{rec.id}",
  "payex": "{rec.payex}",
  "vehicle_weekday_amount": {{
    "small_car": {{"mon": null, "tue": null, "wed": null, "thu": null, "fri": null, "sat": null, "sun": null}},
    "motorcycle": {{"mon": null, "tue": null, "wed": null, "thu": null, "fri": null, "sat": null, "sun": null}},
    "heavy_motorcycle": {{"mon": null, "tue": null, "wed": null, "thu": null, "fri": null, "sat": null, "sun": null}},
    "bus": {{"mon": null, "tue": null, "wed": null, "thu": null, "fri": null, "sat": null, "sun": null}}
  }}
}}

要解析的資料：
ID: {rec.id}
Payex: {rec.payex}
""".strip()


def is_all_null_result(payload: Dict[str, Any]) -> bool:
    vmap = payload.get("vehicle_weekday_amount") or {}
    for vehicle in VEHICLES:
        week = vmap.get(vehicle) or {}
        for day in WEEKDAYS:
            if week.get(day) is not None:
                return False
    return True


def call_chat_completions(
    api_key: str,
    model: str,
    prompt: str,
    base_url: str,
    timeout_sec: int,
) -> Dict[str, Any]:
    url = base_url.rstrip("/") + "/chat/completions"
    body = {
        "model": model,
        "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": "You extract structured parking pricing data."},
            {"role": "user", "content": prompt},
        ],
    }
    data = json.dumps(body).encode("utf-8")
    req = request.Request(
        url,
        data=data,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
    )
    try:
        with request.urlopen(req, timeout=timeout_sec) as resp:
            raw = resp.read().decode("utf-8")
    except error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {e.code} {url}: {detail}") from e
    except Exception as e:
        raise RuntimeError(f"request failed: {e}") from e

    parsed = json.loads(raw)
    content = parsed["choices"][0]["message"]["content"]
    try:
        return json.loads(content)
    except Exception as e:
        raise RuntimeError(f"LLM did not return valid JSON content: {content[:300]}") from e


def load_existing_items(path: Path) -> Dict[str, Dict[str, Any]]:
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        items = data.get("items")
        if not isinstance(items, list):
            return {}
        out = {}
        for item in items:
            pid = str(item.get("id", "")).strip()
            if pid:
                out[pid] = item
        return out
    except Exception:
        return {}


def write_output(path: Path, items: List[Dict[str, Any]], source_file: Path, model: str) -> None:
    root = {
        "meta": {
            "source": str(source_file).replace("\\", "/"),
            "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "generator": "scripts/rebuild_payex_structured_with_llm.py",
            "model": model,
            "count": len(items),
            "schema": {
                "items": {
                    "id": "string",
                    "payex": "string",
                    "vehicle_weekday_amount": {
                        "small_car|motorcycle|heavy_motorcycle|bus": {
                            "mon|tue|wed|thu|fri|sat|sun": "number|null"
                        }
                    },
                }
            },
        },
        "items": items,
    }
    path.write_text(json.dumps(root, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def main() -> int:
    # Load local .env first so argparse defaults can read OPENAI_* values.
    load_dotenv_file(Path(".env"))
    load_dotenv_file(Path(__file__).resolve().parents[1] / ".env")

    parser = argparse.ArgumentParser(description="Use LLM to rebuild payex_structured.json from payex_id_only.txt")
    parser.add_argument("--input", default="Output/payex_id_only.txt")
    parser.add_argument("--output", default="Output/payex_structured.json")
    parser.add_argument("--model", default=os.getenv("OPENAI_MODEL", "gpt-4o-mini"))
    parser.add_argument("--fallback-model", default=os.getenv("OPENAI_FALLBACK_MODEL", "gpt-4o-mini"))
    parser.add_argument("--base-url", default=os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1"))
    parser.add_argument("--sleep-sec", type=float, default=0.2)
    parser.add_argument("--timeout-sec", type=int, default=120)
    parser.add_argument("--resume", action="store_true", help="Skip IDs already in output file")
    parser.add_argument("--limit", type=int, default=0, help="Only process first N records (0 = all)")
    args = parser.parse_args()

    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        print("ERROR: OPENAI_API_KEY is required", file=sys.stderr)
        return 2

    input_path = Path(args.input)
    output_path = Path(args.output)
    if not input_path.exists():
        print(f"ERROR: input file not found: {input_path}", file=sys.stderr)
        return 2

    records = parse_payex_id_only(input_path)
    if args.limit > 0:
        records = records[: args.limit]
    if not records:
        print("ERROR: no records parsed from input", file=sys.stderr)
        return 2

    existing = load_existing_items(output_path) if args.resume else {}
    out_items: List[Dict[str, Any]] = []
    if args.resume and existing:
        # Keep existing items first in stable order.
        for rec in records:
            if rec.id in existing:
                out_items.append(existing[rec.id])

    processed = 0
    failed: List[str] = []
    for idx, rec in enumerate(records, start=1):
        if args.resume and rec.id in existing:
            print(f"[{idx}/{len(records)}] skip {rec.id} (already exists)")
            continue
        prompt = build_prompt(rec)
        try:
            payload = call_chat_completions(
                api_key=api_key,
                model=args.model,
                prompt=prompt,
                base_url=args.base_url,
                timeout_sec=args.timeout_sec,
            )
            if is_all_null_result(payload) and args.fallback_model and args.fallback_model != args.model:
                payload_fb = call_chat_completions(
                    api_key=api_key,
                    model=args.fallback_model,
                    prompt=prompt,
                    base_url=args.base_url,
                    timeout_sec=args.timeout_sec,
                )
                if not is_all_null_result(payload_fb):
                    payload = payload_fb
            clean = clamp_schema(payload, rec.id, rec.payex)
            out_items.append(clean)
            processed += 1
            print(f"[{idx}/{len(records)}] ok {rec.id}")
        except Exception as e:
            failed.append(rec.id)
            print(f"[{idx}/{len(records)}] FAIL {rec.id}: {e}", file=sys.stderr)

        write_output(output_path, out_items, input_path, args.model)
        time.sleep(max(args.sleep_sec, 0.0))

    print(f"done: processed={processed}, failed={len(failed)}, output={output_path}")
    if failed:
        print("failed_ids=" + ",".join(failed), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
