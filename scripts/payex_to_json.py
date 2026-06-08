import argparse
import json
import re
import math
from pathlib import Path
from typing import Any, Dict, List, Optional


TIME_RANGE_RE = re.compile(r"(\d{1,2})\s*[-~－]\s*(\d{1,2})")
AMOUNT_RE = re.compile(r"(\d{1,3}(?:,\d{3})*|\d+)\s*元")
DAY_RANGE_RE = re.compile(r"週一至週五|週六至週日|週一至週日|平日|假日")
VEHICLE_RE = re.compile(r"大型重型機車|大型重機|重型機車|小型車|機車|大客車|大型車")
MONTHLY_KEYWORDS = ("月租", "月票", "元/月")
SEGMENT_SPLIT_RE = re.compile(r"[，。,；;、]")
FREE_KEYWORDS = ("免費", "不收費", "免收費", "暫不收費")
TIME_BUCKETS = {
    "00_06": (0, 6),
    "06_12": (6, 12),
    "12_18": (12, 18),
    "18_24": (18, 24),
}
WEEKDAY_ORDER = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
SMALL_CAR_LOW_MAX = 40.0
SMALL_CAR_MEDIUM_MAX = 80.0
MOTORCYCLE_LOW_LT = 20.0
MOTORCYCLE_MEDIUM_LT = 30.0


def normalize_amount(text: str) -> Optional[int]:
    m = AMOUNT_RE.search(text)
    if not m:
        return None
    return int(m.group(1).replace(",", ""))


def sanitize_numeric_commas(text: str) -> str:
    # Remove comma separators inside numbers so clause splitting does not break amounts.
    return re.sub(r"(?<=\d),(?=\d)", "", text)


def normalize_vehicle(text: str) -> Optional[str]:
    m = VEHICLE_RE.search(text)
    if not m:
        return None
    token = m.group(0)
    mapping = {
        "大型重型機車": "heavy_motorcycle",
        "大型重機": "heavy_motorcycle",
        "重型機車": "heavy_motorcycle",
        "小型車": "small_car",
        "機車": "motorcycle",
        "大客車": "bus",
        "大型車": "large_vehicle",
    }
    return mapping.get(token)


def normalize_day(text: str) -> Optional[str]:
    m = DAY_RANGE_RE.search(text)
    if not m:
        return None
    token = m.group(0)
    mapping = {
        "週一至週五": "weekday",
        "平日": "weekday",
        "週六至週日": "weekend",
        "假日": "holiday",
        "週一至週日": "all_days",
    }
    return mapping.get(token)


def extract_time_ranges(text: str) -> List[Dict[str, str]]:
    ranges: List[Dict[str, str]] = []
    for m in TIME_RANGE_RE.finditer(text):
        start = int(m.group(1))
        end = int(m.group(2))
        ranges.append({"start": f"{start:02d}:00", "end": f"{end:02d}:00"})
    return ranges


def split_segments(text: str) -> List[str]:
    return [s.strip() for s in SEGMENT_SPLIT_RE.split(text) if s.strip()]


def extract_mentioned_vehicles(text: str) -> List[str]:
    found: List[str] = []
    heavy_tokens = ("大型重型機車", "大型重機", "重型機車")
    text_without_heavy = text
    for token in heavy_tokens:
        text_without_heavy = text_without_heavy.replace(token, "")

    if "小型車" in text:
        found.append("small_car")
    if any(token in text for token in heavy_tokens):
        found.append("heavy_motorcycle")
    if "機車" in text_without_heavy:
        found.append("motorcycle")
    if "大客車" in text:
        found.append("bus")
    if "大型車" in text:
        found.append("large_vehicle")
    return sorted(set(found))


def derive_motorcycle_policy(payex: str, pricing_result: Dict[str, Any]) -> str:
    mentioned = "motorcycle" in pricing_result.get("vehicles_mentioned", [])
    rule_vehicles = [
        r.get("vehicle")
        for sec in ("hourly", "per_entry", "monthly")
        for r in pricing_result.get(sec, {}).get("rules", [])
    ]
    has_priced_rule = "motorcycle" in rule_vehicles

    if has_priced_rule:
        return "priced"

    if mentioned and ("機車" in payex) and any(k in payex for k in FREE_KEYWORDS):
        return "free"

    if mentioned:
        return "mentioned_no_rate"

    return "not_mentioned"


def derive_heavy_motorcycle_policy(payex: str, pricing_result: Dict[str, Any]) -> str:
    mentioned = "heavy_motorcycle" in pricing_result.get("vehicles_mentioned", [])
    rule_vehicles = [
        r.get("vehicle")
        for sec in ("hourly", "per_entry", "monthly")
        for r in pricing_result.get(sec, {}).get("rules", [])
    ]
    has_priced_rule = "heavy_motorcycle" in rule_vehicles

    if has_priced_rule:
        return "priced"

    if mentioned:
        return "mentioned_no_rate"

    return "not_mentioned"


def hourly_equivalent_rate(rule: Dict[str, Any]) -> Optional[float]:
    rate = rule.get("rate")
    unit = rule.get("rate_unit")
    if rate is None:
        return None
    if unit == "hour":
        return float(rate)
    if unit == "half_hour":
        return float(rate) * 2.0
    return None


def _is_small_car_hourly_rule(rule: Dict[str, Any]) -> bool:
    # Generic hourly rules (vehicle=None) are treated as applicable to small_car.
    vehicle = rule.get("vehicle")
    return vehicle in (None, "small_car")


def min_hourly_rate(pricing_result: Dict[str, Any]) -> Optional[float]:
    candidates: List[float] = []
    for rule in pricing_result.get("hourly", {}).get("rules", []):
        if not _is_small_car_hourly_rule(rule):
            continue
        value = hourly_equivalent_rate(rule)
        if value is not None:
            candidates.append(value)
    if not candidates:
        return None
    return min(candidates)


def is_surcharge_rule(rule: Dict[str, Any]) -> bool:
    note = str(rule.get("note") or "")
    return (
        ("加收" in note)
        or ("充電" in note)
        or ("未充電" in note)
    )


def static_small_car_base_rate(pricing_result: Dict[str, Any]) -> Optional[float]:
    ranged: List[float] = []
    generic: List[float] = []

    for rule in pricing_result.get("hourly", {}).get("rules", []):
        if not _is_small_car_hourly_rule(rule):
            continue
        if is_surcharge_rule(rule):
            continue

        value = hourly_equivalent_rate(rule)
        if value is None:
            continue

        if rule_hours(rule):
            ranged.append(value)
        else:
            generic.append(value)

    if ranged:
        return max(ranged)
    if generic:
        return max(generic)
    return min_hourly_rate(pricing_result)


def percentile(values: List[float], p: float) -> float:
    if not values:
        return 0.0
    sorted_values = sorted(values)
    idx = int(math.floor((len(sorted_values) - 1) * p))
    return sorted_values[idx]


def classify_hourly_price(value: Optional[float]) -> str:
    if value is None:
        return "unknown"
    if value <= SMALL_CAR_LOW_MAX:
        return "low"
    if value <= SMALL_CAR_MEDIUM_MAX:
        return "medium"
    return "high"


def classify_price(value: Optional[float], vehicle: Optional[str], rate_unit: Optional[str]) -> str:
    if value is None:
        return "unknown"

    normalized_value = float(value)
    if rate_unit == "half_hour":
        normalized_value *= 2.0

    if vehicle in (None, "small_car"):
        return classify_hourly_price(normalized_value)

    if vehicle == "motorcycle":
        if normalized_value < MOTORCYCLE_LOW_LT:
            return "low"
        if normalized_value < MOTORCYCLE_MEDIUM_LT:
            return "medium"
        return "high"

    return "unknown"


def parse_hour_token(token: str) -> int:
    return int(token.split(":", 1)[0])


def expand_range_to_hours(start: int, end: int) -> List[int]:
    if start == end:
        return list(range(24))
    if start < end:
        return list(range(start, end))
    return list(range(start, 24)) + list(range(0, end))


def rule_hours(rule: Dict[str, Any]) -> Optional[List[int]]:
    ranges = rule.get("time_ranges") or []
    if not ranges:
        return None

    hours: List[int] = []
    for r in ranges:
        start = parse_hour_token(r["start"])
        end = parse_hour_token(r["end"])
        hours.extend(expand_range_to_hours(start, end))
    return sorted(set(hours))


def min_hourly_rate_for_bucket(pricing_result: Dict[str, Any], bucket_start: int, bucket_end: int) -> Optional[float]:
    bucket_hours = set(range(bucket_start, bucket_end))
    candidates: List[float] = []

    for rule in pricing_result.get("hourly", {}).get("rules", []):
        if not _is_small_car_hourly_rule(rule):
            continue
        value = hourly_equivalent_rate(rule)
        if value is None:
            continue

        hours = rule_hours(rule)
        if hours is None:
            candidates.append(value)
            continue

        if bucket_hours.intersection(hours):
            candidates.append(value)

    if not candidates:
        return None
    return min(candidates)


def weekdays_for_rule(rule: Dict[str, Any]) -> List[str]:
    day_scope = rule.get("day_scope")
    if day_scope == "weekday":
        return ["mon", "tue", "wed", "thu", "fri"]
    if day_scope == "weekend" or day_scope == "holiday":
        return ["sat", "sun"]
    if day_scope == "all_days" or day_scope is None:
        return WEEKDAY_ORDER
    return WEEKDAY_ORDER


def min_hourly_rate_for_weekday(pricing_result: Dict[str, Any], weekday: str) -> Optional[float]:
    candidates: List[float] = []

    for rule in pricing_result.get("hourly", {}).get("rules", []):
        if not _is_small_car_hourly_rule(rule):
            continue
        value = hourly_equivalent_rate(rule)
        if value is None:
            continue

        supported_days = weekdays_for_rule(rule)
        if weekday in supported_days:
            candidates.append(value)

    if not candidates:
        return None
    return min(candidates)


def min_hourly_rate_for_weekday_bucket(
    pricing_result: Dict[str, Any], weekday: str, bucket_start: int, bucket_end: int
) -> Optional[float]:
    bucket_hours = set(range(bucket_start, bucket_end))
    candidates: List[float] = []

    for rule in pricing_result.get("hourly", {}).get("rules", []):
        if not _is_small_car_hourly_rule(rule):
            continue
        value = hourly_equivalent_rate(rule)
        if value is None:
            continue

        supported_days = weekdays_for_rule(rule)
        if weekday not in supported_days:
            continue

        hours = rule_hours(rule)
        if hours is None:
            candidates.append(value)
            continue

        if bucket_hours.intersection(hours):
            candidates.append(value)

    if not candidates:
        return None
    return min(candidates)


def has_heavy_motorcycle_inclusive_text(text: str) -> bool:
    return any(
        token in text
        for token in (
            "含大型重型機車",
            "含重型機車",
            "大型重型機車",
            "大型重機",
            "大型重型機車比照",
            "大型重機比照",
        )
    )


def split_sections(payex: str) -> Dict[str, str]:
    sections: Dict[str, str] = {
        "hourly": "",
        "per_entry": "",
        "other": "",
    }

    text = sanitize_numeric_commas(payex)
    clauses = [c.strip() for c in re.split(r"[，。,；;]", text) if c.strip()]

    context = "other"
    for clause in clauses:
        if any(k in clause for k in MONTHLY_KEYWORDS):
            context = "other"
        elif "計次" in clause:
            context = "per_entry"
        elif "計時" in clause:
            context = "hourly"

        if sections[context]:
            sections[context] += "，" + clause
        else:
            sections[context] = clause

    return sections


def parse_hourly(hourly_text: str) -> Dict[str, Any]:
    if not hourly_text:
        return {
            "exists": False,
            "has_half_hour_pricing": False,
            "rules": [],
            "raw": "",
        }

    has_half_hour = ("半小時" in hourly_text) or ("半小時計" in hourly_text)

    # Split by punctuation and parse each segment as a possible rule.
    text = sanitize_numeric_commas(hourly_text)
    segments = split_segments(text)
    rules: List[Dict[str, Any]] = []
    current_vehicle: Optional[str] = None
    current_day_scope: Optional[str] = None

    for seg in segments:
        vehicle = normalize_vehicle(seg)
        day_scope = normalize_day(seg)
        if vehicle is not None:
            current_vehicle = vehicle
        if day_scope is not None:
            current_day_scope = day_scope

        amount = normalize_amount(seg)
        if amount is None:
            continue

        rate_unit = "hour"
        if "元/半小時" in seg:
            rate_unit = "half_hour"
        elif "元/次" in seg:
            rate_unit = "entry"

        rules.append(
            {
                "vehicle": vehicle if vehicle is not None else current_vehicle,
                "day_scope": day_scope if day_scope is not None else current_day_scope,
                "time_ranges": extract_time_ranges(seg),
                "rate": amount,
                "rate_unit": rate_unit,
                "price_level": classify_price(amount, vehicle if vehicle is not None else current_vehicle, rate_unit),
                "note": seg,
            }
        )

        # Example: "小型車(含大型重型機車)50元/時" should also be filterable by heavy motorcycle.
        if (vehicle if vehicle is not None else current_vehicle) == "small_car" and has_heavy_motorcycle_inclusive_text(seg):
            rules.append(
                {
                    "vehicle": "heavy_motorcycle",
                    "day_scope": day_scope if day_scope is not None else current_day_scope,
                    "time_ranges": extract_time_ranges(seg),
                    "rate": amount,
                    "rate_unit": rate_unit,
                    "price_level": classify_price(amount, "heavy_motorcycle", rate_unit),
                    "note": seg + " (derived:heavy_motorcycle_inclusive)",
                }
            )

    return {
        "exists": True,
        "has_half_hour_pricing": has_half_hour,
        "rules": rules,
        "raw": hourly_text,
    }


def parse_per_entry(per_entry_text: str) -> Dict[str, Any]:
    if not per_entry_text:
        return {"exists": False, "rules": [], "raw": ""}

    text = sanitize_numeric_commas(per_entry_text)
    segments = split_segments(text)
    rules: List[Dict[str, Any]] = []
    current_vehicle: Optional[str] = None
    current_day_scope: Optional[str] = None

    for seg in segments:
        vehicle = normalize_vehicle(seg)
        day_scope = normalize_day(seg)
        if vehicle is not None:
            current_vehicle = vehicle
        if day_scope is not None:
            current_day_scope = day_scope

        amount = normalize_amount(seg)
        if amount is None:
            continue
        rules.append(
            {
                "vehicle": vehicle if vehicle is not None else current_vehicle,
                "day_scope": day_scope if day_scope is not None else current_day_scope,
                "rate": amount,
                "rate_unit": "entry",
                "price_level": classify_price(amount, vehicle if vehicle is not None else current_vehicle, "entry"),
                "note": seg,
            }
        )

        if (vehicle if vehicle is not None else current_vehicle) == "small_car" and has_heavy_motorcycle_inclusive_text(seg):
            rules.append(
                {
                    "vehicle": "heavy_motorcycle",
                    "day_scope": day_scope if day_scope is not None else current_day_scope,
                    "rate": amount,
                    "rate_unit": "entry",
                    "price_level": classify_price(amount, "heavy_motorcycle", "entry"),
                    "note": seg + " (derived:heavy_motorcycle_inclusive)",
                }
            )

    return {"exists": True, "rules": rules, "raw": per_entry_text}


def parse_payex(payex: str) -> Dict[str, Any]:
    sections = split_sections(payex)

    result = {
        "source_text": payex,
        "vehicles_mentioned": extract_mentioned_vehicles(payex),
        "hourly": parse_hourly(sections["hourly"]),
        "per_entry": parse_per_entry(sections["per_entry"]),
        "other_text": sections["other"],
    }
    result["motorcycle_policy"] = derive_motorcycle_policy(payex, result)
    result["heavy_motorcycle_policy"] = derive_heavy_motorcycle_policy(payex, result)
    return result


def build_output(parks: List[Dict[str, Any]]) -> Dict[str, Any]:
    items: List[Dict[str, Any]] = []

    for p in parks:
        payex = (p.get("payex") or "").strip()
        if not payex:
            continue
        pricing = parse_payex(payex)

        items.append(
            {
                "id": p.get("id"),
                "payex": payex,
                "pricing": pricing,
            }
        )

    for item in items:
        pricing = item["pricing"]
        # Top-level rate is non-time-based small-car base rate (ignore night buckets and surcharge rules).
        min_rate = static_small_car_base_rate(pricing)
        pricing["min_hourly_rate"] = min_rate
        pricing["hourly_price_level"] = classify_hourly_price(min_rate)

        pricing["hourly_price_by_time"] = {}
        for bucket_name, (bucket_start, bucket_end) in TIME_BUCKETS.items():
            bucket_rate = min_hourly_rate_for_bucket(pricing, bucket_start, bucket_end)
            pricing["hourly_price_by_time"][bucket_name] = {
                "min_hourly_rate": bucket_rate,
                "price_level": classify_hourly_price(bucket_rate),
            }

        pricing["hourly_price_by_weekday"] = {}
        for weekday in WEEKDAY_ORDER:
            weekday_rate = min_hourly_rate_for_weekday(pricing, weekday)
            pricing["hourly_price_by_weekday"][weekday] = {
                "min_hourly_rate": weekday_rate,
                "price_level": classify_hourly_price(weekday_rate),
            }

        # Direct lookup table for app: pricing.hourly_price_lookup[weekday][time_bucket]
        pricing["hourly_price_lookup"] = {}
        for weekday in WEEKDAY_ORDER:
            pricing["hourly_price_lookup"][weekday] = {}
            for bucket_name, (bucket_start, bucket_end) in TIME_BUCKETS.items():
                rate = min_hourly_rate_for_weekday_bucket(pricing, weekday, bucket_start, bucket_end)
                pricing["hourly_price_lookup"][weekday][bucket_name] = {
                    "min_hourly_rate": rate,
                    "price_level": classify_hourly_price(rate),
                }

    return {
        "meta": {
            "count": len(items),
            "schema_version": "v1",
            "note": "Rule-based initial extraction. Use LLM as second pass for difficult patterns.",
            "hourly_price_level_rule": {
                "method": "fixed_threshold_small_car_only",
                "vehicle_scope": "small_car_only",
                "low_max": SMALL_CAR_LOW_MAX,
                "medium_max": SMALL_CAR_MEDIUM_MAX,
                "labels": ["low", "medium", "high", "unknown"],
            },
            "hourly_price_level_by_time_rule": {
                "method": "fixed_threshold_small_car_only",
                "vehicle_scope": "small_car_only",
                "buckets": TIME_BUCKETS,
                "low_max": SMALL_CAR_LOW_MAX,
                "medium_max": SMALL_CAR_MEDIUM_MAX,
                "labels": ["low", "medium", "high", "unknown"],
            },
            "hourly_price_level_by_weekday_rule": {
                "method": "fixed_threshold_small_car_only",
                "vehicle_scope": "small_car_only",
                "weekdays": WEEKDAY_ORDER,
                "low_max": SMALL_CAR_LOW_MAX,
                "medium_max": SMALL_CAR_MEDIUM_MAX,
                "labels": ["low", "medium", "high", "unknown"],
            },
            "hourly_price_lookup_rule": {
                "method": "fixed_threshold_small_car_only",
                "vehicle_scope": "small_car_only",
                "weekdays": WEEKDAY_ORDER,
                "buckets": TIME_BUCKETS,
                "low_max": SMALL_CAR_LOW_MAX,
                "medium_max": SMALL_CAR_MEDIUM_MAX,
                "labels": ["low", "medium", "high", "unknown"],
                "usage": "lookup by current weekday and time bucket without extra filtering",
            },
            "motorcycle_price_level_rule": {
                "method": "fixed_threshold",
                "vehicle_scope": "motorcycle_only",
                "low_lt": MOTORCYCLE_LOW_LT,
                "medium_lt": MOTORCYCLE_MEDIUM_LT,
                "labels": ["low", "medium", "high", "unknown"],
            },
        },
        "items": items,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert parking payex text to structured JSON")
    parser.add_argument(
        "--input",
        default="DataSource/TCMSV_alldesc.json",
        help="Path to source alldesc JSON",
    )
    parser.add_argument(
        "--output",
        default="Output/payex_structured.json",
        help="Path to output structured JSON",
    )
    args = parser.parse_args()

    input_path = Path(args.input)
    output_path = Path(args.output)

    raw = json.loads(input_path.read_text(encoding="utf-8"))
    parks = raw.get("data", {}).get("park", [])
    result = build_output(parks)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"Wrote {result['meta']['count']} items to {output_path.as_posix()}")


if __name__ == "__main__":
    main()
