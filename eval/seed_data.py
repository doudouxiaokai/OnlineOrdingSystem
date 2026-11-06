#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
seed_data.py —— 订餐系统造数脚本

用途：为 Agent 评测（eval_agent.py）与压测（load_test.py）生成基础业务数据，
      让「查餐厅 / 查菜单 / 查库存 / 查历史订单 / 抽用户偏好」都能命中真实数据。

造数范围：
  1. 用户 t_user（Faker 生成中文昵称 + 手机号）
  2. 餐厅 t_restaurant + 菜品 t_dish（含评测脚本用到的店名/菜名，见 SEED_RESTAURANTS）
  3. 历史订单 t_order + 明细 t_order_item（给 eval-user 造「常点大牌猪脚饭」的偏好）

安全设计：
  - 默认 dry-run，只打印将写入的统计，不落库；
  - 加 --apply 才真正写入；
  - 幂等：seed 数据带固定 ID 前缀，写入前先 DELETE 同前缀旧数据，可重复执行。

用法：
  python seed_data.py                    # dry-run 预览
  python seed_data.py --apply            # 真正写入
  python seed_data.py --users 30 --orders 100 --apply

依赖：pymysql、faker。
"""

import argparse
import datetime
import random
import uuid

import pymysql
from faker import Faker

DB_DEFAULT = dict(host="127.0.0.1", port=3306, user="root",
                  password="123456", database="takeaway_db")

# 预置餐厅 + 菜品（覆盖 eval_agent.py 里用到的店名/菜名，保证评测能命中真实数据）
SEED_RESTAURANTS = [
    {
        "name": "大牌猪脚饭", "cuisine": "快餐", "rating": 4.8,
        "address": "中关村大街 1 号",
        "dishes": [
            {"name": "招牌脆皮猪脚饭", "price": 28.0, "is_signature": 1, "is_spicy": 0},
            {"name": "猪脚饭", "price": 22.0, "is_signature": 0, "is_spicy": 0},
            {"name": "香辣排骨饭", "price": 26.0, "is_signature": 0, "is_spicy": 1},
            {"name": "卤蛋", "price": 3.0, "is_signature": 0, "is_spicy": 0},
        ],
    },
    {
        "name": "川味居", "cuisine": "川菜", "rating": 4.6,
        "address": "建设路 88 号",
        "dishes": [
            {"name": "麻婆豆腐", "price": 18.0, "is_signature": 1, "is_spicy": 1},
            {"name": "回锅肉", "price": 32.0, "is_signature": 0, "is_spicy": 1},
            {"name": "口水鸡", "price": 30.0, "is_signature": 0, "is_spicy": 1},
        ],
    },
    {
        "name": "粤式烧腊", "cuisine": "粤菜", "rating": 4.7,
        "address": "体育西路 12 号",
        "dishes": [
            {"name": "叉烧饭", "price": 25.0, "is_signature": 1, "is_spicy": 0},
            {"name": "烧鸭饭", "price": 27.0, "is_signature": 0, "is_spicy": 0},
            {"name": "白切鸡饭", "price": 24.0, "is_signature": 0, "is_spicy": 0},
        ],
    },
    {
        "name": "兰州拉面", "cuisine": "面食", "rating": 4.5,
        "address": "解放大道 5 号",
        "dishes": [
            {"name": "牛肉拉面", "price": 15.0, "is_signature": 1, "is_spicy": 0},
            {"name": "红烧牛肉面", "price": 18.0, "is_signature": 0, "is_spicy": 1},
        ],
    },
    {
        "name": "奶茶时光", "cuisine": "饮品", "rating": 4.4,
        "address": "步行街 100 号",
        "dishes": [
            {"name": "珍珠奶茶", "price": 12.0, "is_signature": 1, "is_spicy": 0},
            {"name": "柠檬茶", "price": 10.0, "is_signature": 0, "is_spicy": 0},
        ],
    },
    {
        "name": "汉堡大王", "cuisine": "西式快餐", "rating": 4.3,
        "address": "科技园 66 号",
        "dishes": [
            {"name": "巨无霸汉堡", "price": 24.0, "is_signature": 1, "is_spicy": 0},
            {"name": "薯条", "price": 9.0, "is_signature": 0, "is_spicy": 0},
            {"name": "可乐", "price": 6.0, "is_signature": 0, "is_spicy": 0},
        ],
    },
]

# 评测用户（eval_agent.py 里用的 userId），给 ta 造「常点大牌猪脚饭」的偏好
EVAL_USER_ID = "eval-user"


def _uid(prefix):
    """生成 32 位内、带前缀的稳定 ID。"""
    return prefix + uuid.uuid4().hex[: 32 - len(prefix)]


def build_plan(args):
    """在内存中生成整套数据（不落库），返回可统计的结构。"""
    fake = Faker("zh_CN")
    rng = random.Random(42)  # 固定种子，保证可复现

    # 1. 用户
    users = [{
        "user_id": "seed_user_{:03d}".format(i),
        "username": fake.name(),
        "phone": "139{:08d}".format(rng.randint(0, 99999999)),
        "gender": rng.randint(0, 2),
        "member_level": rng.randint(0, 3),
        "points": rng.randint(0, 5000),
    } for i in range(1, args.users + 1)]

    # 2. 餐厅 + 菜品
    restaurants, dishes = [], []
    for idx, r in enumerate(SEED_RESTAURANTS, 1):
        rid = "seed_rest_{:03d}".format(idx)
        restaurants.append({
            "restaurant_id": rid, "name": r["name"], "cuisine": r["cuisine"],
            "rating": r["rating"], "address": r["address"],
            "audit_status": "APPROVED", "status": 1,
            "min_order": 15.0, "delivery_fee": 4.0, "monthly_sales": rng.randint(200, 5000),
        })
        for didx, d in enumerate(r["dishes"], 1):
            dishes.append({
                "dish_id": "seed_dish_{:03d}_{:02d}".format(idx, didx),
                "restaurant_id": rid, "name": d["name"], "category": r["cuisine"],
                "price": d["price"], "current_stock": rng.randint(20, 200),
                "safety_stock": 10, "sales": rng.randint(0, 1000),
                "is_signature": d["is_signature"], "is_spicy": d["is_spicy"], "status": 1,
            })

    # 3. 历史订单（eval-user 集中点大牌猪脚饭，形成可抽取的偏好）
    pig_rest = restaurants[0]["restaurant_id"]
    pig_dishes = [d for d in dishes if d["restaurant_id"] == pig_rest]
    orders, items = [], []

    def add_order(uid, rid, dish_list, days_ago):
        total = 0.0
        oid = _uid("seed_order_")
        orders.append({
            "order_id": oid,
            "order_no": "S" + uuid.uuid4().hex[:20].upper(),
            "user_id": uid, "restaurant_id": rid,
            "status": "COMPLETED", "address": fake.address(),
            "created_at": datetime.datetime.now() - datetime.timedelta(days=days_ago),
        })
        for d in dish_list:
            qty = rng.randint(1, 2)
            subtotal = round(d["price"] * qty, 2)
            total += subtotal
            items.append({
                "item_id": _uid("seed_item_"),
                "order_id": oid, "dish_id": d["dish_id"],
                "dish_name": d["name"], "quantity": qty, "subtotal": subtotal,
            })
        orders[-1]["total_amount"] = round(total, 2)

    # eval-user 的偏好订单（最近 30 天内 6 笔，全点大牌猪脚饭）
    for i in range(6):
        pick = [rng.choice(pig_dishes)]
        add_order(EVAL_USER_ID, pig_rest, pick, days_ago=i * 3 + 1)

    # 随机用户的随机订单
    for _ in range(args.orders):
        uid = rng.choice(users)["user_id"]
        rid = rng.choice(restaurants)["restaurant_id"]
        rid_dishes = [d for d in dishes if d["restaurant_id"] == rid]
        pick = rng.sample(rid_dishes, rng.randint(1, min(3, len(rid_dishes))))
        add_order(uid, rid, pick, days_ago=rng.randint(0, 60))

    return {
        "users": users, "restaurants": restaurants, "dishes": dishes,
        "orders": orders, "items": items,
    }


def apply(conn, plan):
    """把 plan 里的数据写入数据库（幂等：先删同前缀旧数据）。"""
    cur = conn.cursor()
    try:
        cur.execute("DELETE FROM t_order_item WHERE item_id LIKE 'seed_item_%'")
        cur.execute("DELETE FROM t_order WHERE order_id LIKE 'seed_order_%'")
        cur.execute("DELETE FROM t_dish WHERE dish_id LIKE 'seed_dish_%'")
        cur.execute("DELETE FROM t_restaurant WHERE restaurant_id LIKE 'seed_rest_%'")
        cur.execute("DELETE FROM t_user WHERE user_id LIKE 'seed_user_%'")

        for u in plan["users"]:
            cur.execute(
                "INSERT INTO t_user (user_id, username, phone, gender, member_level, points, status) "
                "VALUES (%s, %s, %s, %s, %s, %s, 1)",
                (u["user_id"], u["username"], u["phone"], u["gender"],
                 u["member_level"], u["points"]))
        for r in plan["restaurants"]:
            cur.execute(
                "INSERT INTO t_restaurant "
                "(restaurant_id, name, cuisine, rating, address, audit_status, status, "
                " min_order, delivery_fee, monthly_sales) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
                (r["restaurant_id"], r["name"], r["cuisine"], r["rating"], r["address"],
                 r["audit_status"], r["status"], r["min_order"], r["delivery_fee"], r["monthly_sales"]))
        for d in plan["dishes"]:
            cur.execute(
                "INSERT INTO t_dish "
                "(dish_id, restaurant_id, name, category, price, current_stock, safety_stock, "
                " sales, is_signature, is_spicy, status) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
                (d["dish_id"], d["restaurant_id"], d["name"], d["category"], d["price"],
                 d["current_stock"], d["safety_stock"], d["sales"], d["is_signature"],
                 d["is_spicy"], d["status"]))
        for o in plan["orders"]:
            cur.execute(
                "INSERT INTO t_order "
                "(order_id, order_no, user_id, restaurant_id, total_amount, status, address, created_at) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s)",
                (o["order_id"], o["order_no"], o["user_id"], o["restaurant_id"],
                 o["total_amount"], o["status"], o["address"], o["created_at"]))
        for it in plan["items"]:
            cur.execute(
                "INSERT INTO t_order_item "
                "(item_id, order_id, dish_id, dish_name, quantity, subtotal) "
                "VALUES (%s, %s, %s, %s, %s, %s)",
                (it["item_id"], it["order_id"], it["dish_id"], it["dish_name"],
                 it["quantity"], it["subtotal"]))
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        cur.close()


def print_plan(plan):
    print("造数计划（dry-run）：")
    print("  用户       : {}".format(len(plan["users"])))
    print("  餐厅       : {}".format(len(plan["restaurants"])))
    print("  菜品       : {}".format(len(plan["dishes"])))
    print("  历史订单   : {}".format(len(plan["orders"])))
    print("  订单明细   : {}".format(len(plan["items"])))
    print("  预置店名   : {}".format(", ".join(r["name"] for r in plan["restaurants"])))


def main():
    parser = argparse.ArgumentParser(description="订餐系统造数脚本")
    parser.add_argument("--host", default=DB_DEFAULT["host"])
    parser.add_argument("--port", type=int, default=DB_DEFAULT["port"])
    parser.add_argument("--user", default=DB_DEFAULT["user"])
    parser.add_argument("--password", default=DB_DEFAULT["password"])
    parser.add_argument("--database", default=DB_DEFAULT["database"])
    parser.add_argument("--users", type=int, default=20, help="随机用户数")
    parser.add_argument("--orders", type=int, default=50, help="随机历史订单数")
    parser.add_argument("--apply", action="store_true", help="真正写入数据库（默认 dry-run）")
    args = parser.parse_args()

    plan = build_plan(args)
    print_plan(plan)

    if not args.apply:
        print("\n[dry-run] 未写入。加 --apply 才会真正落库。")
        return

    conn = pymysql.connect(
        host=args.host, port=args.port, user=args.user,
        password=args.password, database=args.database, charset="utf8mb4")
    try:
        apply(conn, plan)
        print("\n[OK] 已写入数据库 {}@{}:{}/{}".format(
            args.user, args.host, args.port, args.database))
    finally:
        conn.close()


if __name__ == "__main__":
    main()
