package com.aftersale.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读路径的构造性反幻觉闸门。
 *
 * 写路径的保证本来就是构造性的：金额取自订单实体、政策走 policy_rules 表、归属由注入的 userId 决定，
 * 模型没有"编"的空间。只读路径原先只靠提示词约束（"禁止编造订单信息"），这是全项目唯一的
 * 提示词级保证——同一个项目里两套标准。这个类把它拉回同一条标准：
 *
 *   答复中出现的订单号，必须能在本回合的事实来源（用户原话 + 工具返回内容）里找到出处；
 *   否则视为编造，该答复不予采信，降级为兜底话术，并把违规项记进轨迹。
 *
 * 边界说明（不要过度承诺）：
 *   1) 当前只校验订单号这一类强标识实体。金额、日期、人名暂不校验——在没有结构化事实源时
 *      它们的误报率太高，宁可不做也不要制造假阳性把正常答复拦掉。
 *   2) 这是"降低编造出现在用户可见输出里的概率"，不是"消除幻觉"。
 *      工具返回本身若有误，闸门挡不住。
 */
@Component
public class FabricationGuard {

    /** 订单号形态与 schema.sql 的种子数据一致：ORD + 数字 */
    private static final Pattern ORDER_NO = Pattern.compile("ORD\\d{6,}", Pattern.CASE_INSENSITIVE);

    /** 抽取文本中出现的订单号，统一大写以便比较 */
    public Set<String> extract(String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return found;
        }
        Matcher m = ORDER_NO.matcher(text);
        while (m.find()) {
            found.add(m.group().toUpperCase(Locale.ROOT));
        }
        return found;
    }

    /**
     * @param answer     模型给出的最终答复
     * @param knownFacts 本回合事实来源中出现的订单号集合（用户原话 + 全部工具返回）
     * @return 无来源支撑的订单号；空集表示通过
     */
    public Set<String> violations(String answer, Set<String> knownFacts) {
        Set<String> cited = extract(answer);
        cited.removeAll(knownFacts);
        return cited;
    }
}
