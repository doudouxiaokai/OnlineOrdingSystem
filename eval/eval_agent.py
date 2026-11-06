#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
eval_agent.py —— 订餐 Agent 端到端评测脚本（增强版）

评测对象：Spring Boot 后端的 Agent 编排链路（纯自研 runtime，非 LangChain）。
  1) HTTP 同步：POST /api/agent/chat（显式规划 / PlannerOrchestrator）
  2) WebSocket 流式：/ws/agent（plan / step / done 事件，验证流式链路正确性）

每条用例的评测维度：
  1. 意图识别   —— Agent 是否调用了正确的工具（expected.name 支持多候选，任一命中即过）；
  2. 目标达成   —— 最终回复是否包含用户要的关键信息（reply_contains）；
  3. 安全       —— 回复是否泄露内部 ID（restaurantId/dishId/orderId 等）；
  4. 流式正确性 —— （--ws）是否收到 plan 事件、step 状态流转是否合法、done 是否带内容。

汇总指标：完成率、平均时延、平均规划步数、累计 token 消耗。

用法：
  python eval_agent.py                                  # HTTP 单轮 + 多轮
  python eval_agent.py --base-url http://127.0.0.1:8080
  python eval_agent.py --ws                             # 走 WebSocket 流式评测
  python eval_agent.py --out report.md                  # 输出 Markdown 报告
  python eval_agent.py --json report.json               # 输出 JSON
  python eval_agent.py --plot                           # 生成 matplotlib 图表（eval_report.png）
  python eval_agent.py --cases cases.json               # 外部单轮用例

依赖：requests；--ws 需 websocket-client；--plot 需 matplotlib。
"""

import argparse
import json
import re
import sys
import time

import requests

DEFAULT_BASE_URL = "http://127.0.0.1:8080"
NO_TOOL = "__NO_TOOL__"

# 所有 Agent 工具名（用于 ID 泄露检测的兜底扫掠）
KNOWN_TOOLS = (
    "list_restaurants", "list_dishes", "create_order", "get_order_history",
    "check_stock", "quote_order", "pay_order", "search_restaurants",
)


# ---------------------------------------------------------------------------
# 基础请求：HTTP 同步 + WebSocket 流式
# ---------------------------------------------------------------------------
def call_agent(base_url, user_id, session_id, text, timeout=60):
    """调用后端 /api/agent/chat，返回完整响应 JSON。"""
    url = base_url.rstrip("/") + "/api/agent/chat"
    body = {"userId": user_id, "sessionId": session_id, "text": text}
    t0 = time.time()
    resp = requests.post(url, json=body, timeout=timeout)
    latency = time.time() - t0
    if resp.status_code != 200:
        raise RuntimeError("HTTP {}: {}".format(resp.status_code, resp.text[:300]))
    data = resp.json()
    data["_latency"] = latency
    return data


def call_agent_ws(base_url, user_id, session_id, text, timeout=90):
    """通过 WebSocket /ws/agent 调用，收集 plan/step/done 事件流。

    返回：
      {plan, steps(list), done, error, latency, events(全量)}
    """
    try:
        import websocket
    except ImportError:
        raise RuntimeError("缺少 websocket-client，请先 pip install websocket-client")

    ws_url = base_url.replace("http://", "ws://").replace("https://", "wss://").rstrip("/") + "/ws/agent"

    plan, done, error = None, None, None
    steps, events = [], []
    t0 = time.time()
    ws = websocket.create_connection(ws_url, timeout=timeout)
    try:
        ws.send(json.dumps({"userId": user_id, "sessionId": session_id, "text": text}))
        while True:
            raw = ws.recv()
            if raw is None or raw == "":
                break
            evt = json.loads(raw)
            events.append(evt)
            kind = evt.get("type")
            if kind == "plan":
                plan = evt
            elif kind == "step":
                steps.append(evt)
            elif kind == "done":
                done = evt
                break
            elif kind == "error":
                error = evt
                break
    finally:
        ws.close()

    return {
        "plan": plan,
        "steps": steps,
        "done": done,
        "error": error,
        "latency": time.time() - t0,
        "events": events,
    }


# ---------------------------------------------------------------------------
# 结果提取
# ---------------------------------------------------------------------------
def extract_tool_names(response):
    """从后端响应里提取工具调用名列表（兼容显式规划的 plan 结构与旧结构）。"""
    if not isinstance(response, dict):
        return []
    tools = []

    # 显式规划：response["plan"] = [{stepId, tool, status, ...}]
    plan = response.get("plan")
    if isinstance(plan, list):
        for s in plan:
            if isinstance(s, dict) and s.get("tool"):
                tools.append(s["tool"])

    # 旧结构：response["steps"] 是 [{toolName, ...}]
    raw_steps = response.get("steps")
    if isinstance(raw_steps, list):
        for s in raw_steps:
            if isinstance(s, dict):
                name = s.get("toolName") or s.get("tool") or s.get("name")
                if name:
                    tools.append(str(name))

    # response["tools"] = [{name, ...}]
    raw_tools = response.get("tools")
    if isinstance(raw_tools, list):
        for t in raw_tools:
            if isinstance(t, dict) and t.get("name"):
                tools.append(t["name"])

    # 兜底：回复文本里出现工具名痕迹
    text = extract_reply_text(response)
    if isinstance(text, str) and not tools:
        for kw in KNOWN_TOOLS:
            if kw in text:
                tools.append(kw)
    return tools


def extract_reply_text(response):
    """提取给用户的自然语言回复。"""
    if not isinstance(response, dict):
        return ""
    for key in ("content", "reply", "text", "finalReply"):
        v = response.get(key)
        if isinstance(v, str) and v:
            return v
    return ""


def has_id_leak(reply_text):
    """检查自然语言回复里是否泄露了 ID。"""
    if not reply_text:
        return False
    if "restaurantId" in reply_text or "dishId" in reply_text or "orderId" in reply_text:
        return True
    # 形如「xxx店 r1」「猪脚饭 d12」这类裸 ID 痕迹
    if re.search(r"[\u4e00-\u9fa5][rRdD]\d{1,4}", reply_text):
        return True
    return False


# ---------------------------------------------------------------------------
# 判定
# ---------------------------------------------------------------------------
def _expected_names(case):
    """把 expected.name 归一化成列表（支持单值或列表，多候选任一命中）。"""
    exp = case.get("expected", {})
    name = exp.get("name")
    if name is None:
        return None
    if isinstance(name, (list, tuple)):
        return [str(x) for x in name]
    return [str(name)]


def evaluate_tool(exp_names, tool_names):
    """工具名判定，返回问题列表。"""
    problems = []
    if exp_names is None:
        return problems
    if NO_TOOL in exp_names:
        if tool_names:
            problems.append("误触发工具 " + ",".join(tool_names))
    else:
        if not tool_names:
            problems.append("未触发任何工具，期望 " + "/".join(exp_names))
        elif not any(n in tool_names for n in exp_names):
            problems.append("工具不匹配：期望 {}，实际 {}".format(
                "/".join(exp_names), ",".join(tool_names)))
    return problems


def evaluate_reply(case, reply_text):
    """「真实达到目的」+ ID 泄露判定。"""
    problems = []
    must_contain = case.get("reply_contains")
    if must_contain:
        if not any(k in reply_text for k in must_contain):
            problems.append("回复缺少关键信息：{}（回复={}）".format(
                must_contain, reply_text[:80]))
    if has_id_leak(reply_text):
        problems.append("回复泄露了 ID")
    return problems


def evaluate_single(case, tool_names, reply_text, metrics=None):
    """统一判定（HTTP / WS 复用）。"""
    metrics = metrics or {}
    problems = []
    problems += evaluate_tool(_expected_names(case), tool_names)
    problems += evaluate_reply(case, reply_text)

    ok = not problems
    return {
        "ok": ok,
        "detail": "；".join(problems) if problems else "OK",
        "tools": tool_names,
        "reply": (reply_text or "")[:100],
        "metrics": metrics,
    }


def evaluate_ws_flow(ws_result, exp_names):
    """WebSocket 流式链路正确性判定（在工具/回复判定之外追加）。"""
    problems = []
    plan = ws_result.get("plan")
    is_no_tool = bool(exp_names) and NO_TOOL in exp_names
    if plan is None:
        # 无关话题（NO_TOOL）本就不该有 plan 事件，不算错
        if not is_no_tool:
            problems.append("未收到 plan 事件")
    else:
        plan_steps = plan.get("plan", {}).get("steps", [])
        ids = [s.get("stepId") for s in plan_steps if isinstance(s, dict)]
        if len(ids) != len(set(ids)):
            problems.append("plan 存在重复 stepId")

    if ws_result.get("error"):
        problems.append("WS 返回 error：" + json.dumps(ws_result["error"], ensure_ascii=False)[:120])
    done = ws_result.get("done")
    if done is None:
        problems.append("未收到 done 事件")
    elif not done.get("content"):
        problems.append("done 事件缺少 content")

    # step 状态流转：每个 stepId 至少出现过 RUNNING，且终态 DONE/FAILED
    steps = ws_result.get("steps", [])
    by_id = {}
    for s in steps:
        by_id.setdefault(s.get("stepId"), []).append(s.get("status"))
    for sid, statuses in by_id.items():
        if "RUNNING" not in statuses:
            problems.append("步骤 {} 未经历 RUNNING".format(sid))
        if not any(st in ("DONE", "FAILED", "SKIPPED") for st in statuses):
            problems.append("步骤 {} 无终态".format(sid))

    return problems


# ---------------------------------------------------------------------------
# 用例
# ---------------------------------------------------------------------------
DEFAULT_SINGLE_CASES = [
    {"id": 1, "desc": "查餐厅", "input": "附近有哪些餐厅",
     "expected": {"name": "list_restaurants"}, "reply_contains": ["餐厅", "店"]},
    {"id": 2, "desc": "看菜单-店名", "input": "大牌猪脚饭有什么菜",
     "expected": {"name": "list_dishes"}, "reply_contains": ["菜"]},
    {"id": 3, "desc": "看菜单-另一家店", "input": "川味居的菜单看看",
     "expected": {"name": "list_dishes"}, "reply_contains": ["菜"]},
    {"id": 4, "desc": "下单-未选店先查店", "input": "来一份猪脚饭",
     "expected": {"name": ["search_restaurants", "list_restaurants"]}},
    {"id": 5, "desc": "查历史订单", "input": "我上次点了什么",
     "expected": {"name": "get_order_history"}},
    {"id": 6, "desc": "无关-寒暄", "input": "你好",
     "expected": {"name": NO_TOOL}},
    {"id": 7, "desc": "无关-天气", "input": "今天天气怎么样",
     "expected": {"name": NO_TOOL}},
    {"id": 8, "desc": "搜索营业中餐厅", "input": "有没有营业中的川菜馆",
     "expected": {"name": ["search_restaurants", "list_restaurants"]}},
    {"id": 9, "desc": "算价-报价", "input": "点两份猪脚饭大概多少钱",
     "expected": {"name": ["quote_order", "list_dishes"]}},
    {"id": 10, "desc": "查库存", "input": "招牌脆皮猪脚饭还有货吗",
     "expected": {"name": ["check_stock", "list_dishes"]}},
    # ---- 多样化话术（同一意图的不同表达，测鲁棒性）----
    {"id": 11, "desc": "查餐厅-口语", "input": "附近有啥吃的",
     "expected": {"name": "list_restaurants"}},
    {"id": 12, "desc": "查餐厅-推荐", "input": "给我推荐几家店呗",
     "expected": {"name": ["list_restaurants", "search_restaurants"]}},
    {"id": 13, "desc": "查菜单-口语", "input": "大牌猪脚饭都卖些啥",
     "expected": {"name": "list_dishes"}},
    {"id": 14, "desc": "查菜单-招牌", "input": "看看大牌猪脚饭有哪些招牌菜",
     "expected": {"name": "list_dishes"}},
    {"id": 15, "desc": "下单-口语", "input": "整一份猪脚饭",
     "expected": {"name": ["search_restaurants", "list_restaurants"]}},
    {"id": 16, "desc": "历史-口语", "input": "我上次吃了啥",
     "expected": {"name": "get_order_history"}},
    {"id": 17, "desc": "历史-最近", "input": "最近我都点过些什么",
     "expected": {"name": "get_order_history"}},
    {"id": 18, "desc": "无关-在吗", "input": "在吗",
     "expected": {"name": NO_TOOL}},
    {"id": 19, "desc": "无关-能力边界", "input": "你会做饭吗",
     "expected": {"name": NO_TOOL}},
    {"id": 20, "desc": "搜索-辣味", "input": "我想吃点辣的",
     "expected": {"name": ["search_restaurants", "list_restaurants"]}},
]

# 多轮：真实 ID 流转（后端内部完成 name->ID 翻译，这里验证「最终下单成功」）
DEFAULT_MULTI_CASES = [
    {
        "id": "M1",
        "desc": "完整点餐：看店→看菜→下单",
        "turns": [
            {"user": "有什么店",
             "expected": {"name": "list_restaurants"}, "reply_contains": ["餐厅", "店"]},
            {"user": "大牌猪脚饭有什么菜",
             "expected": {"name": "list_dishes"}, "reply_contains": ["菜"]},
            {"user": "来一份招牌脆皮猪脚饭",
             "expected": {"name": "create_order"}, "reply_contains": None},
        ],
    },
]


def load_cases(path):
    if not path:
        return DEFAULT_SINGLE_CASES
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------------------
# HTTP / WS 两套执行入口
# ---------------------------------------------------------------------------
def run_http(base_url, cases, delay):
    results = []
    for case in cases:
        try:
            resp = call_agent(base_url, "eval-user", "eval-single", case["input"])
            tools = extract_tool_names(resp)
            reply = extract_reply_text(resp)
            metrics = {
                "latency": round(resp.get("_latency", 0), 2),
                "token": resp.get("tokenUsed", 0),
                "steps": len(resp.get("plan", [])) if isinstance(resp.get("plan"), list) else resp.get("steps", 0),
            }
            verdict = evaluate_single(case, tools, reply, metrics)
        except Exception as exc:  # noqa: BLE001
            verdict = {"ok": False, "detail": "接口异常：" + str(exc),
                       "tools": [], "reply": "", "metrics": {}}
        results.append({"id": case.get("id"), "desc": case.get("desc", ""),
                        "input": case.get("input"), **verdict})
        time.sleep(delay)
    return results


def run_ws(base_url, cases, delay):
    results = []
    for case in cases:
        try:
            ws = call_agent_ws(base_url, "eval-user", "eval-single", case["input"])
            plan = ws["plan"] or {}
            plan_steps = plan.get("plan", {}).get("steps", [])
            tools = [s.get("tool") for s in plan_steps if isinstance(s, dict) and s.get("tool")]
            done = ws["done"] or {}
            reply = done.get("content", "")

            problems = []
            problems += evaluate_tool(_expected_names(case), tools)
            problems += evaluate_reply(case, reply)
            problems += evaluate_ws_flow(ws, _expected_names(case))
            ok = not problems
            verdict = {
                "ok": ok,
                "detail": "；".join(problems) if problems else "OK",
                "tools": tools,
                "reply": reply[:100],
                "metrics": {
                    "latency": round(ws.get("latency", 0), 2),
                    "token": done.get("tokenUsed", 0),
                    "steps": len(plan_steps),
                },
            }
        except Exception as exc:  # noqa: BLE001
            verdict = {"ok": False, "detail": "WS 异常：" + str(exc),
                       "tools": [], "reply": "", "metrics": {}}
        results.append({"id": case.get("id"), "desc": case.get("desc", ""),
                        "input": case.get("input"), **verdict})
        time.sleep(delay)
    return results


# ---------------------------------------------------------------------------
# 报告：文本 / Markdown / JSON / matplotlib 图
# ---------------------------------------------------------------------------
def summarize(results):
    total = len(results)
    passed = [r for r in results if r["ok"]]
    latencies = [r["metrics"].get("latency", 0) for r in results if r["metrics"].get("latency")]
    tokens = [r["metrics"].get("token", 0) for r in results]
    steps = [r["metrics"].get("steps", 0) for r in results]
    return {
        "total": total,
        "passed": len(passed),
        "failed": total - len(passed),
        "pass_rate": len(passed) / total if total else 0,
        "avg_latency": round(sum(latencies) / len(latencies), 2) if latencies else 0,
        "avg_steps": round(sum(steps) / len(steps), 2) if steps else 0,
        "total_token": sum(tokens),
    }


def render_text(results, summary, mode):
    lines = []
    lines.append("=" * 64)
    lines.append("订餐 Agent 端到端评测报告（{}）".format(mode))
    lines.append("=" * 64)
    lines.append("评测点总数 : {}".format(summary["total"]))
    lines.append("通过数     : {}".format(summary["passed"]))
    lines.append("失败数     : {}".format(summary["failed"]))
    lines.append("任务完成率 : {:.1%}".format(summary["pass_rate"]))
    lines.append("平均时延   : {:.2f}s".format(summary["avg_latency"]))
    lines.append("平均步数   : {:.2f}".format(summary["avg_steps"]))
    lines.append("累计 token : {}".format(summary["total_token"]))
    lines.append("-" * 64)
    failed = [r for r in results if not r["ok"]]
    if failed:
        lines.append("失败样例：")
        for r in failed:
            lines.append("  [{}] {} — 输入「{}」".format(r["id"], r["desc"], r["input"]))
            lines.append("       原因：{}".format(r["detail"]))
    else:
        lines.append("全部用例通过。")
    lines.append("=" * 64)
    return "\n".join(lines)


def render_markdown(results, summary, mode):
    lines = []
    lines.append("# 订餐 Agent 端到端评测报告（{}）".format(mode))
    lines.append("")
    lines.append("| 指标 | 值 |")
    lines.append("|---|---|")
    lines.append("| 评测点总数 | {} |".format(summary["total"]))
    lines.append("| 通过数 | {} |".format(summary["passed"]))
    lines.append("| 失败数 | {} |".format(summary["failed"]))
    lines.append("| 完成率 | {:.1%} |".format(summary["pass_rate"]))
    lines.append("| 平均时延 | {:.2f}s |".format(summary["avg_latency"]))
    lines.append("| 平均步数 | {:.2f} |".format(summary["avg_steps"]))
    lines.append("| 累计 token | {} |".format(summary["total_token"]))
    lines.append("")
    lines.append("| ID | 用例 | 输入 | 结果 | 工具 | 时延(s) | token | 说明 |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for r in results:
        m = r["metrics"]
        lines.append("| {} | {} | {} | {} | {} | {} | {} | {} |".format(
            r["id"], r["desc"], r["input"],
            "✅" if r["ok"] else "❌",
            ",".join(r["tools"]) or "-",
            m.get("latency", "-"), m.get("token", "-"),
            r["detail"],
        ))
    return "\n".join(lines)


def render_plot(results, summary, out_path="eval_report.png"):
    """生成延迟柱状图 + 通过率饼图。"""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("缺少 matplotlib，跳过绘图（pip install matplotlib）")
        return None

    plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "sans-serif"]
    plt.rcParams["axes.unicode_minus"] = False

    labels = [str(r["id"]) for r in results]
    latencies = [r["metrics"].get("latency", 0) for r in results]

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 4.5))
    ax1.bar(labels, latencies, color="#007aff")
    ax1.set_xlabel("用例 ID")
    ax1.set_ylabel("时延 (s)")
    ax1.set_title("各用例时延")

    passed = summary["passed"]
    failed = summary["failed"]
    ax2.pie([passed, failed], labels=["通过 {}".format(passed), "失败 {}".format(failed)],
            colors=["#7ee787", "#ff7b72"], autopct="%1.1f%%", startangle=90)
    ax2.set_title("通过率 {:.1%}".format(summary["pass_rate"]))

    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)
    return out_path


# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description="订餐 Agent 端到端评测")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help="后端地址（默认 http://127.0.0.1:8080）")
    parser.add_argument("--cases", help="外部单轮用例 JSON 文件（可选）")
    parser.add_argument("--delay", type=float, default=0.5,
                        help="评测点间隔秒数，避免限流（默认 0.5）")
    parser.add_argument("--skip-multi", action="store_true", help="跳过会真实下单的多轮用例")
    parser.add_argument("--ws", action="store_true", help="走 WebSocket 流式评测")
    parser.add_argument("--out", help="输出 Markdown 报告到文件")
    parser.add_argument("--json", dest="json_out", help="输出 JSON 到文件")
    parser.add_argument("--plot", action="store_true", help="生成 matplotlib 图表")
    args = parser.parse_args()

    mode = "WebSocket 流式" if args.ws else "HTTP 同步"
    runner = run_ws if args.ws else run_http

    # 单轮
    results = runner(args.base_url, load_cases(args.cases), args.delay)

    # 多轮（同 sessionId 保持上下文）
    if not args.skip_multi and not args.ws:
        for case in DEFAULT_MULTI_CASES:
            session_id = "eval-multi-" + str(case["id"])
            for turn in case["turns"]:
                try:
                    resp = call_agent(args.base_url, "eval-user", session_id, turn["user"])
                    tools = extract_tool_names(resp)
                    reply = extract_reply_text(resp)
                    metrics = {
                        "latency": round(resp.get("_latency", 0), 2),
                        "token": resp.get("tokenUsed", 0),
                        "steps": len(resp.get("plan", [])) if isinstance(resp.get("plan"), list) else resp.get("steps", 0),
                    }
                    verdict = evaluate_single(turn, tools, reply, metrics)
                except Exception as exc:  # noqa: BLE001
                    verdict = {"ok": False, "detail": "接口异常：" + str(exc),
                               "tools": [], "reply": "", "metrics": {}}
                results.append({"id": case["id"], "desc": case["desc"],
                                "input": turn["user"], **verdict})
                time.sleep(args.delay)

    summary = summarize(results)

    # 输出
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump({"summary": summary, "results": results}, f,
                      ensure_ascii=False, indent=2)
        print("JSON 已写入 {}".format(args.json_out))
    elif args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(render_markdown(results, summary, mode))
        print("Markdown 报告已写入 {}".format(args.out))
    else:
        print(render_text(results, summary, mode))

    if args.plot:
        path = render_plot(results, summary)
        if path:
            print("图表已生成 {}".format(path))


if __name__ == "__main__":
    main()
