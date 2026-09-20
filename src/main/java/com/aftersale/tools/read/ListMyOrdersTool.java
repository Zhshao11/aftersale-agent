package com.aftersale.tools.read;

import com.aftersale.domain.OrderEntity;
import com.aftersale.repo.OrderRepository;
import com.aftersale.tools.ToolErrorCode;
import com.aftersale.tools.ToolResult;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具契约 — listMyOrders
 * 用途：列出当前用户的订单（按时间倒序），用于「用商品描述指代订单」的查询。
 *      典型问法："我最近买的那个咖啡机的物流" / "我上次买的蓝牙耳机" / "我一个月前买的那个 XXX"。
 * 输入：keyword: string（可选，商品名关键词，用于缩小范围）；limit: int（可选，返回条数上限）
 * 输出：orders[]（orderNo, itemName, status, amount, orderTitle, paidAt…）+ count + note
 * 错误码：INVALID_ARGS
 * 权限边界：只读；**userId 由工具内部从注入的身份取，签名里没有这个参数，模型无法指定他人**。
 *
 * 为什么需要这个工具：原有三个只读工具（getOrder/getLogistics/getPolicy）**全部要求 orderNo**，
 * 查询链路一旦拿不到订单号就无路可走。而真实客服场景里，用户几乎不会先报订单号——
 * 他们会说"我上周买的那个耳机"。没有这个工具，模型只能反问订单号（体验差）
 * 或者乱猜一个（更糟：反幻觉闸门也会拦下）。它是"索引"而非"详情"：
 * 只回答"我有哪些订单"，把"哪个是用户说的那个"留给模型做语义判断。
 *
 * keyword 未命中时的回退：返回最近 limit 条并附 note。因为用户对商品的称呼常与
 * 系统记录不一致（"蓝牙耳机" vs "降噪头戴耳机"、"那个咖啡机" vs "全自动咖啡机"），
 * 直接返回空会让模型误判"用户没有这个订单"。宁可多给几条让模型自己判断。
 */
@Component
public class ListMyOrdersTool {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 默认返回条数：够覆盖"最近买的那个"这类指代，又不至于把上下文塞满 */
    private static final int DEFAULT_LIMIT = 5;

    /** 条数上限：硬边界。列表是给模型做指代消解的，不是给用户导出全部历史 */
    private static final int MAX_LIMIT = 20;

    private final OrderRepository orderRepository;

    public ListMyOrdersTool(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public ToolResult listMyOrders(String keyword, Integer limit, String userId) {
        if (userId == null || userId.isBlank()) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGS, "userId 为必填（应由系统注入）");
        }
        int n = clampLimit(limit);

        List<OrderEntity> mine = orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<OrderEntity> matched = filterByKeyword(mine, keyword);

        boolean fallback = false;
        List<OrderEntity> picked = matched;
        if (matched.isEmpty()) {
            // 关键词未命中 → 退化为"最近若干条"，让模型自己判断是不是用户说的那个
            fallback = !isBlank(keyword) && !mine.isEmpty();
            picked = mine;
        }
        int total = picked.size();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OrderEntity o : picked) {
            if (rows.size() >= n) break;
            rows.add(brief(o));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", rows.size());
        data.put("totalMatched", total);
        data.put("orders", rows);
        if (rows.isEmpty()) {
            data.put("note", "该用户没有任何订单");
        } else if (fallback) {
            data.put("note", "按关键词「" + keyword.trim() + "」没有匹配到订单，已改返回该用户最近的 "
                    + rows.size() + " 条订单供参考；若都不是用户所指，请如实告知未找到。");
        }
        return ToolResult.success(data);
    }

    /** 关键词过滤：大小写不敏感的包含匹配。空白关键词视为不过滤（返回全部，交给 limit 截断） */
    private List<OrderEntity> filterByKeyword(List<OrderEntity> orders, String keyword) {
        if (isBlank(keyword)) {
            return new ArrayList<>(orders);
        }
        String k = keyword.trim().toLowerCase(Locale.ROOT);
        List<OrderEntity> hit = new ArrayList<>();
        for (OrderEntity o : orders) {
            String name = o.getItemName() == null ? "" : o.getItemName().toLowerCase(Locale.ROOT);
            if (name.contains(k)) {
                hit.add(o);
            }
        }
        return hit;
    }

    /**
     * 单条订单的摘要。刻意带上时间与状态：
     * 用户说"一个月前买的"时，模型要靠这些字段自己判断，而不是再问一遍。
     */
    private Map<String, Object> brief(OrderEntity o) {
        Map<String, Object> m = new HashMap<>();
        m.put("orderNo", o.getOrderNo());
        m.put("itemName", o.getItemName());
        m.put("status", o.getStatus().name());
        m.put("amount", String.format("$%.2f", o.getAmountCents() / 100.0));
        m.put("logisticsStatus", o.getLogisticsStatus() == null ? "UNKNOWN" : o.getLogisticsStatus());
        m.put("paidAt", o.getPaidAt() == null ? null : o.getPaidAt().format(F));
        m.put("createdAt", o.getCreatedAt() == null ? null : o.getCreatedAt().format(F));
        return m;
    }

    /** limit 收敛到 [1, MAX_LIMIT]：非法值不报错，退化为默认值（查询类参数不值得让整次调用失败） */
    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}