#!/usr/bin/env python3
"""意图路由三方对照：LLM 路由器 vs 本地规则分类器 vs 混合。

要回答的问题
------------
用户提出：用架构/实现改进大幅降低链路时延，同时保证效果与安全——这是个好讲点。

其中「把意图路由从 LLM 降级成本地分类器」听起来最像架构改进，但它必须先回答：
**降级会不会让判错的请求变多？** 判错的代价不对称：
  · 查询被判成写 → 多走一次计划生成（多花 6~16s），用户多看到一个确认框；
  · 写被判成查询 → **用户的取消/退款请求被当成闲聊**，什么都不发生。
后者是功能性事故，前者只是浪费。所以**召回率优先于精确率**。

关键前提（决定了这件事安不安全）
--------------------------------
拦截与意图是**两个正交维度**（见 intent_cases.json 的 _key_insight）：
被 blocked / policy_refusal 的用例里，有措辞上纯查询的（S3「查查…物流到哪了」、
Q7「帮我看看…这个订单」）。说明**拦截不是意图路由做的**，是下游的
「工具集只有 ReadToolBundle + 订单归属校验 + 确认门」做的。
→ 所以降级意图路由**不削弱任何安全语义**，这正是它可讨论的根据。

用法
----
  # 只测本地分类器（零成本、可无限跑）
  python3 eval/intent_eval.py --local

  # 加上 LLM 路由器作为对照臂（需要 .env，每次请求 4~8s）
  cd aftersale-agent && set -a && . ./.env && set +a
  python3 eval/intent_eval.py --llm --repeat 2
"""
import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "scripts"))

# ==== 逐字对齐 IntentRouter.SYSTEM ====
INTENT_SYSTEM = """你是售后意图分类器。判断用户消息属于哪类：
- QUERY：查询信息（查订单、查物流、查政策、咨询规则等，无状态变更诉求）
- WRITE：请求执行操作（取消订单、退款、退货、换货，或"不想要了/退了吧"等隐含写操作的表达）
同时把用户诉求改写成一句规范的中文短句。
只输出 JSON，格式：{"intent":"QUERY|WRITE","request":"改写后的诉求"}，不要输出其他内容。
"""

# ---------------------------------------------------------------- 本地分类器
# 设计取舍：**宁可误判为 WRITE**。
# 因为「写判成查」= 用户请求静默失效（事故级），
#     「查判成写」= 多一次规划 + 多一个确认框（体验级，且用户可取消）。
# 这个偏向与 IntentRouter.parse() 的兜底方向**相反**（那边失败默认 QUERY），
# 所以混合方案里必须显式处理这个不一致——不能两套兜底各行其是。

# 写诉求的显式动作词
WRITE_VERBS = [
    "取消", "退掉", "退了", "退款", "退货", "换货", "换个", "换一个", "换新",
    "退一下", "退了款", "帮我退", "给我退",
]
# 写诉求的口语表达（不含动作动词，但是明确的诉求）
WRITE_INTENT_PHRASES = [
    "不想要了", "不要了", "买错了", "发错了", "用着有问题", "有问题",
    "颜色和描述不符", "太慢了", "等太久", "太贵了", "戴着不舒服",
    "想退款", "想换货", "给我换", "帮我退", "帮我取消",
]
# 纯查询的信号
QUERY_VERBS = ["查", "看看", "到哪", "什么时候到", "什么状态", "详情",
               "政策", "多少钱", "物流", "咨询", "问一下", "怎么样"]

# 「问规则」而非「要执行」的句式。
# 这条规则是**机制性**的，不是给我踩到的两条用例打补丁：
# 「退款」既可以是动作（帮我退款）也可以是**名物**（退款的政策是什么）。
# 区分两者的语法信号是——动作词被「的政策/规则/多久/怎么算」这类
# **询问包装**修饰时，它是被问的对象，不是要执行的诉求。
# 我第一版没写这条，于是「退款政策是什么」因含「退款」被判成 WRITE，
# 用户问一句政策会先收一个取消订单的确认框——体验事故。
POLICY_ASK = re.compile(
    r"(政策|规则|规定|条款|制度|流程)[^。？！]{0,4}(是什么|说一下|说说|讲一下|介绍|怎样|如何|麻烦说)"
    r"|(多久|几天|多长时间)[^。？！]{0,4}(之内|以内|可以退|能退|能换)"
    r"|(可以|能)(退|换)(吗|么|不)"
)
_NOT_QUERY = "：".join([])

_LABEL = re.compile(r"取消|退款|退掉|退货|换货|换个|退了吧|退一下")


def local_classify(msg, history=None):
    """返回 (intent, 命中理由)。可解释——规则法的唯一优势就在这里。"""
    hits_write = [w for w in WRITE_VERBS if w in msg]
    hits_intent = [p for p in WRITE_INTENT_PHRASES if p in msg]
    hits_query = [q for q in QUERY_VERBS if q in msg]

    # ① 先判「问规则」——优先级高于写动词，因为这里的动作词是**被询问的名物**。
    #    注意 R5「笔记本想换货，可以吗」must NOT be caught here：
    #    它说「想换货」（明确诉求），不是「换货的政策」。所以本规则要求
    #    动作词被询问包装**紧跟**修饰，而不是全句任意位置出现疑问语气。
    if POLICY_ASK.search(msg):
        return "QUERY", "问规则:%s" % POLICY_ASK.search(msg).group(0)
    # ② 再看显式写动词。
    #    「可以吗」「行不行」这类疑问语气**不影响**写诉求是否成立
    #    （见 R5——疑问句但必须判 WRITE）。
    if hits_write:
        return "WRITE", "写动词:%s" % ",".join(hits_write[:2])
    if hits_intent:
        return "WRITE", "写意图短语:%s" % ",".join(hits_intent[:2])
    # 指代型续写：「再帮我取消一下」靠历史里的写诉求消解
    if history and _LABEL.search(msg):
        return "WRITE", "历史指代"
    if hits_query:
        return "QUERY", "查询信号:%s" % ",".join(hits_query[:2])
    # 兜底方向与生产代码相反，此处**故意**偏向 WRITE（见文件头取舍）
    return "WRITE", "兜底(无信号,偏向WRITE)"


def local_classify_strict(msg, history=None):
    """严格版：兜底跟生产代码一致（无信号 → QUERY）。
    用来量化「兜底方向」这一个决定值多少条——而不是靠感觉选一个。"""
    r, why = local_classify(msg, history)
    if why.startswith("兜底"):
        return "QUERY", "兜底(无信号,偏向QUERY)"
    return r, why


# ------------------------------------------------- 提议二：本地重写 normalizedRequest
# 为什么需要它：降级意图路由会**丢掉一次模型生成**，而那次生成顺便产出了
# normalizedRequest（供 PLAN 消费）。不补这个函数，降级就不是无损替换，
# PLAN 拿到的是原始消息而不是归一化诉求——那是功能退化，不能装作没发生。
#
# 归一化只需要做三件事（提示词原话：「把用户诉求改写成一句规范的中文短句」）：
#   1. 抽出订单号 → 2. 把口语诉求映射成规范动作 → 3. 拼成一句短句
# 全是规则可做的，不需要模型。
_ORDER_NO = re.compile(r"\b([A-Z]{2,4}\d{6,})\b")
_ACTION_MAP = [
    (re.compile(r"取消|不想要|不要了|退掉|退了款|退了吧"), "取消订单"),
    (re.compile(r"退款|退货|要退|想退"), "退款"),
    (re.compile(r"换货|换个|换一个|换新|换同款"), "换货"),
]


def normalize_request(msg, intent, order_no=None):
    """把口语诉求压成一句规范中文短句。与 LLM 那条分支的语义对齐，但零成本。"""
    if intent != "WRITE":
        # 查询侧的归一化：保留查询意图 + 订单号，便于 PLAN/回复引用
        return ("查询订单 %s 的状态" % order_no) if order_no else "查询订单/政策相关信息"
    action = "处理订单"
    for pat, name in _ACTION_MAP:
        if pat.search(msg):
            action = name
            break
    return ("%s %s" % (action, order_no)) if order_no else action


# ------------------------------------------------- 提议三：确定性计划缓存
# 依据：写路径的 PLAN 一次生成 13~93s（生产 trace 均值 34.1s），
# 而**同一订单 + 同一动作**的计划在确定性提示词下本该相同。
# 实测这份 43 例里就有 3 条（T5/T6/S6）文案完全一样——
# 它们是断点续跑/幂等重入/绕确认门，恰恰最需要**快速且一致**的响应：
# 如果每次都重新生成，不仅慢，还可能每次给出略微不同的计划。
#
# 缓存键必须**只含确定性量**。绝不能含用户措辞——那正是"相近但不相同"的来源，
# 也是缓存最容易出错的地方。下面用真实用例把这条边界量出来。

def plan_cache_key(order_no, action):
    """确定性键：(订单号, 规范动作)。不含任何用户原文。"""
    return "%s|%s" % (order_no or "-", action or "-")


def plan_cache_report(cases, history=None):
    """量出两种键口径分别命中多少、以及宽松口径会错在哪。"""
    print("=" * 100)
    print("提议三：确定性计划缓存能覆盖多少（键只含 订单号+规范动作）")
    print("=" * 100)

    WRITE_TYPES = {"write", "second_confirm_gate", "execute_without_confirm",
                   "idempotent_reentry", "resume"}
    rows = []
    for c in cases:
        if c.get("type") not in WRITE_TYPES:
            continue
        msg = c["msg"]
        order = c.get("orderNo") or (_ORDER_NO.search(msg).group(1)
                                     if _ORDER_NO.search(msg) else None)
        _, why = local_classify(msg, history)
        action = "处理订单"
        for pat, name in _ACTION_MAP:
            if pat.search(msg):
                action = name
                break
        rows.append({"id": c["id"], "msg": msg, "order": order, "action": action,
                     "type": c["type"], "exact": plan_cache_key(order, action)})

    from collections import Counter
    EXACT = Counter(r["exact"] for r in rows)
    print("\n  写路径用例 %d 条。按「订单号+动作」分组：" % len(rows))
    hit = 0
    noorder = 0
    for k, n in EXACT.most_common():
        ids = [r["id"] for r in rows if r["exact"] == k]
        # 「-」桶是**不能缓存**的：这些消息正文里没有订单号，
        # 订单是靠上下文/模糊匹配解出来的（生产里是 locate 那一步，candidates=10）。
        # 把四条不同订单的消息塞进同一个 "-|取消订单" 键，会返回别人的计划。
        # 我第一版就是这么数的，把可复用数从 4 高报到了 7——同一个坑的第三次出现。
        if k.startswith("-|"):
            noorder += n
            print("    %-28s %-18s x%d   ⛔ 无订单号，禁入缓存" % (k, ",".join(ids), n))
            continue
        flag = "  ← 可复用 %d 次" % (n - 1) if n > 1 else ""
        if n > 1:
            hit += n - 1
        print("    %-28s %-18s x%d%s" % (k, ",".join(ids), n, flag))
    cacheable = len(rows) - noorder
    print("\n  → 严格键可复用 %d/%d 次" % (hit, len(rows)))
    if cacheable:
        print("  → 扣掉 %d 条无订单号的（禁入缓存）后，可缓存集合为 %d 条，"
              "命中率 %.1f%%" % (noorder, cacheable, hit / cacheable * 100 if cacheable else 0))
        print("     即 **%.1f%% 的写请求无需再生成计划**（分母取 43 例全集则是 %.1f%%）"
              % (hit / cacheable * 100 if cacheable else 0, hit / len(cases) * 100))

    # 宽松键：只按订单号，忽略动作。这是**故意测错**——要量出这个诱惑的代价。
    LOOSE = Counter(r["order"] or "-" for r in rows)
    print("\n  若为了多命中而只用「订单号」做键（不含动作）：")
    merged = [k for k, n in LOOSE.items() if n > 1 and k != "-"]
    bad = 0
    for k in merged:
        grp = [r for r in rows if (r["order"] or "-") == k]
        acts = {r["action"] for r in grp}
        ids = [r["id"] for r in grp]
        if len(acts) > 1:
            bad += 1
            print("    ⚠️ %-18s %s  动作不一致 %s → 缓存会返回**错的计划**"
                  % (k, ",".join(ids), sorted(acts)))
        else:
            print("    ✓ %-18s %s  动作一致 %s" % (k, ",".join(ids), sorted(acts)))
    if bad:
        print("\n    → 有 %d 组订单号被不同动作撞在一起。**这一档不能用**：" % bad)
        print("      缓存命中带来的收益（省一次生成）远小于返回错计划的代价（执行错操作）。")
    print("\n  另需注意：新消息可能复用同一订单号做**新动作**（先问物流、第二天取消），")
    print("  所以键里必须有动作，且确认门之后的执行结果**不进缓存**（否则会绕过确认）。")
    return {"write_cases": len(rows), "reusable": hit}


# ---------------------------------------------------------------- LLM 路由器
def extract_balanced(s):
    """复刻 PlanGenerator.extractBalancedJson——不要用贪婪正则。"""
    if not s:
        return ""
    t = re.sub(r"^```(?:json)?|```$", "", s.strip(), flags=re.M).strip()
    start = t.find("{")
    if start < 0:
        return t
    depth, in_str, esc = 0, False, False
    for i in range(start, len(t)):
        c = t[i]
        if esc:
            esc = False
            continue
        if c == "\\":
            esc = True
            continue
        if c == '"':
            in_str = not in_str
            continue
        if in_str:
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return t[start:i + 1]
    return t


def llm_classify(conn, model, msg, history, max_tokens=256):
    from llm_latency_bottleneck import stream_call
    hist = "".join("\n%s: %s" % (r, c) for r, c in (history or []))
    prompt = INTENT_SYSTEM + "\n" + hist + "\n\n用户消息：" + msg
    r, err = stream_call(conn, model, prompt, max_tokens)
    if err:
        return None, "ERR:" + err[:40], 0.0
    j = extract_balanced(r.get("text") or "")
    try:
        o = json.loads(j)
    except Exception:
        return None, "PARSE_FAIL", r.get("total_s") or 0
    if o.get("intent") not in ("QUERY", "WRITE"):
        return None, "BAD_LABEL", r.get("total_s") or 0
    return o["intent"], "ok", r.get("total_s") or 0


# ---------------------------------------------------------------- 主流程
def load_cases():
    """读意图用例，并把 split 从 cases.json 合并进来。

    为什么不把 split 抄进 intent_cases.json：那是**第二份真相**。
    划分一旦调整，两份文件就会不一致，而 holdout 的整个价值就在于它没被动过。
    所以 split 永远从 cases.json 现取。
    """
    base = os.path.join(HERE, "intent_cases.json")
    cases = json.load(open(base, encoding="utf-8"))["cases"]
    split = {}
    src = os.path.join(HERE, "cases.json")
    if os.path.exists(src):
        for c in json.load(open(src, encoding="utf-8"))["cases"]:
            split[c["id"]] = c.get("split") or "dev"
    for c in cases:
        c.setdefault("split", split.get(c["id"], "dev"))
    missing = [c["id"] for c in cases if c["id"] not in split]
    if missing:
        print("⚠️ 以下用例在 cases.json 中找不到，无法判定是否属于 holdout：%s\n" % missing)
    return cases


def summarise(name, rows):
    """不对称代价下的评分：写召回率单独看，不混进总准确率。"""
    n = len(rows)
    acc = sum(1 for r in rows if r["pred"] == r["expect"])
    # 写召回：期望 WRITE 中被正确判为 WRITE 的比例。这是事故级指标。
    w = [r for r in rows if r["expect"] == "WRITE"]
    w_rec = sum(1 for r in w if r["pred"] == "WRITE")
    # 查误判为写：多花一次规划调用，体验级
    q = [r for r in rows if r["expect"] == "QUERY"]
    q_fp = sum(1 for r in q if r["pred"] == "WRITE")
    lats = [r["lat"] for r in rows if r.get("lat")]
    import statistics
    med = statistics.median(lats) if lats else 0.0
    print("%-22s 总准确 %2d/%d (%5.1f%%)   写召回 %2d/%d   查误判为写 %d/%d   中位耗时 %.2fs"
          % (name, acc, n, acc / n * 100, w_rec, len(w), q_fp, len(q), med))
    return {"name": name, "acc": acc, "n": n, "w_rec": w_rec, "w_n": len(w),
            "q_fp": q_fp, "q_n": len(q), "med": med}


def split_report(label, rows):
    """dev / holdout 分开报。

    为什么必须分开：规则分类器是我**照着 dev 用例**调的（POLICY_ASK 就是被
    Q3/Q4 逼出来的）。如果只报 43 例总准确，就把「调参集」和「测试集」混成一个数了——
    那是典型的自证式优化。holdout 的 12 条里含 Q7/S5/S6/Q11/Q12 等措辞陷阱，
    它们**从头到尾没参与调参**，才是这个分类器真实泛化能力的读数。
    """
    dev = [r for r in rows if r.get("split") != "holdout"]
    hold = [r for r in rows if r.get("split") == "holdout"]
    for tag, sub in (("dev ", dev), ("holdout", hold)):
        if not sub:
            continue
        acc = sum(1 for r in sub if r["pred"] == r["expect"])
        w = [r for r in sub if r["expect"] == "WRITE"]
        w_rec = sum(1 for r in w if r["pred"] == "WRITE")
        print("    %-8s %s  %2d/%d (%5.1f%%)   写召回 %d/%d"
              % (label, tag, acc, len(sub), acc / len(sub) * 100, w_rec, len(w)))
    return {"label": label, "dev": (sum(1 for r in dev if r["pred"] == r["expect"]), len(dev)),
            "holdout": (sum(1 for r in hold if r["pred"] == r["expect"]), len(hold))}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--llm", action="store_true", help="跑 LLM 对照臂（需要 .env）")
    ap.add_argument("--local", action="store_true", help="只跑本地臂（默认行为）")
    ap.add_argument("--repeat", type=int, default=1, help="LLM 臂重复次数")
    ap.add_argument("--model", default=os.environ.get("LLM_MODEL", "glm-5.3"))
    args = ap.parse_args()

    cases = load_cases()
    print("意图测试集 %d 例（写 %d / 查 %d）\n"
          % (len(cases), sum(1 for c in cases if c["expect"] == "WRITE"),
             sum(1 for c in cases if c["expect"] == "QUERY")))

    # ---- 本地臂 1：偏向 WRITE 的兜底
    rows1 = []
    for c in cases:
        pred, why = local_classify(c["msg"])
        rows1.append({**c, "pred": pred, "why": why, "lat": 0.0})
    # ---- 本地臂 2：与生产一致的严格兜底
    rows2 = []
    for c in cases:
        pred, why = local_classify_strict(c["msg"])
        rows2.append({**c, "pred": pred, "why": why, "lat": 0.0})

    print("=" * 100)
    print("本地分类器（零成本、零延迟）")
    print("=" * 100)
    s1 = summarise("本地·兜底偏WRITE", rows1)
    s2 = summarise("本地·兜底偏QUERY", rows2)
    print("\n  dev / holdout 分开报（调参集 vs 未参与调参的泛化读数）：")
    split_report("兜底偏WRITE", rows1)
    split_report("兜底偏QUERY", rows2)

    # 列出所有判错项——这才是要讨论的东西
    for tag, rows in (("兜底偏WRITE", rows1), ("兜底偏QUERY", rows2)):
        bad = [r for r in rows if r["pred"] != r["expect"]]
        print("\n  [%s] 判错 %d 条：" % (tag, len(bad)))
        if not bad:
            print("    （无）")
        for r in bad:
            print("    %-4s 期望 %-5s 预测 %-5s | %s" % (r["id"], r["expect"], r["pred"], r["msg"]))
            print("         → %s" % r["why"])
            if r.get("trap"):
                print("         ⚠️ %s" % r["trap"])

    results = [s1, s2]

    # ---- 提议三：确定性计划缓存的可覆盖率
    plan_cache_report(cases)

    # ---- LLM 臂
    if args.llm:
        try:
            from llm_latency_bottleneck import Conn, parse_endpoint
        except Exception as e:
            sys.exit("无法导入探针公共模块：%s" % e)
        host, port, path, raw = parse_endpoint()
        conn = Conn(host, port)
        conn.path = path
        print("\n" + "=" * 100)
        print("LLM 路由器（对照臂，%s，x%d）" % (args.model, args.repeat))
        print("=" * 100)
        agg = []
        for i in range(args.repeat):
            rows = []
            for c in cases:
                pred, why, lat = llm_classify(conn, args.model, c["msg"], None)
                rows.append({**c, "pred": pred or "ERR", "why": why, "lat": lat})
            r = summarise("LLM 第%d轮" % (i + 1), rows)
            results.append(r)
            agg.append(rows)
        if agg:
            # 逐条稳定性：同一用例多轮是否给了不同答案
            print("\n  逐条稳定性（多轮不一致 = 意图路由本身就有随机性）：")
            unstable = 0
            for idx, c in enumerate(cases):
                preds = {rows[idx]["pred"] for rows in agg}
                if len(preds) > 1:
                    unstable += 1
                    print("    %-4s 出现 %s | %s" % (c["id"], sorted(preds), c["msg"]))
            if not unstable:
                print("    （全部稳定）")

    print("\n" + "=" * 100)
    print("结论口径")
    print("=" * 100)
    best = max(results, key=lambda r: (r["w_rec"], -r["q_fp"]))
    print("  写召回最高的臂：%s（%d/%d）" % (best["name"], best["w_rec"], best["w_n"]))
    print("  · 事故级指标是**写召回**：写被判成查 = 用户请求静默失效。")
    print("  · 『查误判为写』只是多一次规划调用 + 多一个确认框，用户可取消。")
    print("  · 若本地臂的写召回不低于 LLM 臂，则降级意图路由**不引入新风险**，")
    print("    且省下每一次请求 6~32s 的串行模型调用。")


if __name__ == "__main__":
    main()