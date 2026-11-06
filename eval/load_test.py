#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
load_test.py —— 订餐 Agent 业务级压测脚本

定位：与 JMeter 互补的「业务级压测」。JMeter 测 HTTP 层 QPS/吞吐；
本脚本压测的是 Agent 特有的业务指标——负载下是否还答得对、成本多少：
  - QPS、成功率、P50/P95/P99 时延
  - 每次调用的 token 消耗、规划步数（从响应里提取）
  - 失败样例归因

注意：
  - 每次请求都会真实调用 LLM（烧 token），请控制 --total，勿大规模压。
  - 内置压测输入均为只读工具（查餐厅/查菜单/查历史），不会真实下单，安全。

用法：
  python load_test.py                                   # 默认 10 请求 / 并发 2
  python load_test.py --total 20 --concurrency 5
  python load_test.py --base-url http://127.0.0.1:8080 --plot

依赖：requests；--plot 需 matplotlib。
"""

import argparse
import concurrent.futures
import json
import time

import requests

DEFAULT_BASE_URL = "http://127.0.0.1:8080"

# 内置压测输入（只读工具，安全）
DEFAULT_INPUTS = [
    "附近有哪些餐厅",
    "大牌猪脚饭有什么菜",
    "我上次点了什么",
    "有没有营业中的川菜馆",
]


def one_request(base_url, text):
    """发起一次 /api/agent/chat，返回耗时/成功/token/步数等。"""
    url = base_url.rstrip("/") + "/api/agent/chat"
    body = {"userId": "load-user", "sessionId": None, "text": text}
    t0 = time.time()
    try:
        resp = requests.post(url, json=body, timeout=120)
        latency = time.time() - t0
        if resp.status_code != 200:
            return {"ok": False, "latency": latency, "input": text,
                    "http": resp.status_code, "error": resp.text[:120]}
        data = resp.json()
        plan = data.get("plan")
        steps = len(plan) if isinstance(plan, list) else data.get("steps", 0)
        return {"ok": True, "latency": latency, "input": text,
                "token": data.get("tokenUsed", 0), "steps": steps}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "latency": time.time() - t0, "input": text,
                "error": str(exc)}


def percentile(sorted_vals, p):
    """计算分位数（线性插值）。"""
    if not sorted_vals:
        return 0
    k = (len(sorted_vals) - 1) * p
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


def run(args):
    texts = []
    idx = 0
    for _ in range(args.total):
        texts.append(args.inputs[idx % len(args.inputs)])
        idx += 1

    results = []
    t_start = time.time()
    done = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = {pool.submit(one_request, args.base_url, t): t for t in texts}
        for fut in concurrent.futures.as_completed(futures):
            results.append(fut.result())
            done += 1
            if done % max(1, args.total // 10) == 0 or done == args.total:
                print("  进度 {}/{}".format(done, args.total))
    wall = time.time() - t_start

    return results, wall


def summarize(results, wall):
    total = len(results)
    ok = [r for r in results if r["ok"]]
    bad = [r for r in results if not r["ok"]]
    latencies = sorted(r["latency"] for r in results)
    tokens = [r.get("token", 0) for r in ok]
    steps = [r.get("steps", 0) for r in ok]

    return {
        "total": total,
        "ok": len(ok),
        "fail": len(bad),
        "success_rate": len(ok) / total if total else 0,
        "wall": round(wall, 2),
        "qps": round(total / wall, 2) if wall else 0,
        "p50": round(percentile(latencies, 0.50), 2),
        "p95": round(percentile(latencies, 0.95), 2),
        "p99": round(percentile(latencies, 0.99), 2),
        "avg_latency": round(sum(latencies) / len(latencies), 2) if latencies else 0,
        "total_token": sum(tokens),
        "avg_token": round(sum(tokens) / len(tokens), 1) if tokens else 0,
        "avg_steps": round(sum(steps) / len(steps), 2) if steps else 0,
        "fail_samples": bad,
    }


def render(summary):
    lines = []
    lines.append("=" * 64)
    lines.append("订餐 Agent 业务压测报告")
    lines.append("=" * 64)
    lines.append("总请求数 : {}".format(summary["total"]))
    lines.append("成功数   : {}".format(summary["ok"]))
    lines.append("失败数   : {}".format(summary["fail"]))
    lines.append("成功率   : {:.1%}".format(summary["success_rate"]))
    lines.append("总耗时   : {:.2f}s".format(summary["wall"]))
    lines.append("QPS      : {:.2f}".format(summary["qps"]))
    lines.append("-" * 64)
    lines.append("时延 P50  : {:.2f}s".format(summary["p50"]))
    lines.append("时延 P95  : {:.2f}s".format(summary["p95"]))
    lines.append("时延 P99  : {:.2f}s".format(summary["p99"]))
    lines.append("平均时延 : {:.2f}s".format(summary["avg_latency"]))
    lines.append("-" * 64)
    lines.append("累计 token : {}".format(summary["total_token"]))
    lines.append("平均 token : {}".format(summary["avg_token"]))
    lines.append("平均步数   : {}".format(summary["avg_steps"]))
    if summary["fail_samples"]:
        lines.append("-" * 64)
        lines.append("失败样例：")
        for r in summary["fail_samples"][:5]:
            lines.append("  「{}」 → {}".format(r["input"], r.get("error") or ("HTTP " + str(r.get("http")))))
    lines.append("=" * 64)
    return "\n".join(lines)


def plot(results, summary, out_path="load_report.png"):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("缺少 matplotlib，跳过绘图（pip install matplotlib）")
        return None

    plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "sans-serif"]
    plt.rcParams["axes.unicode_minus"] = False

    latencies = [r["latency"] for r in results]
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 4.5))

    ax1.hist(latencies, bins=20, color="#007aff", edgecolor="white")
    ax1.set_xlabel("时延 (s)")
    ax1.set_ylabel("次数")
    ax1.set_title("时延分布（P50 {:.2f}s / P95 {:.2f}s）".format(summary["p50"], summary["p95"]))

    ax2.bar(["成功", "失败"], [summary["ok"], summary["fail"]],
            color=["#7ee787", "#ff7b72"])
    ax2.set_title("成功率 {:.1%}".format(summary["success_rate"]))

    fig.tight_layout()
    fig.savefig(out_path, dpi=120)
    plt.close(fig)
    return out_path


def main():
    parser = argparse.ArgumentParser(description="订餐 Agent 业务压测")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--total", type=int, default=10, help="总请求数（默认 10，注意 LLM 成本）")
    parser.add_argument("--concurrency", type=int, default=2, help="并发数（默认 2）")
    parser.add_argument("--inputs", help="压测输入文本文件（每行一句，可选）")
    parser.add_argument("--plot", action="store_true", help="生成 matplotlib 图表")
    args = parser.parse_args()

    if args.inputs:
        with open(args.inputs, "r", encoding="utf-8") as f:
            args.inputs = [ln.strip() for ln in f if ln.strip()]
    else:
        args.inputs = DEFAULT_INPUTS

    print("压测开始：{} 请求 / 并发 {} / 目标 {}".format(
        args.total, args.concurrency, args.base_url))
    results, wall = run(args)
    summary = summarize(results, wall)
    print(render(summary))

    if args.plot:
        path = plot(results, summary)
        if path:
            print("图表已生成 {}".format(path))


if __name__ == "__main__":
    main()
