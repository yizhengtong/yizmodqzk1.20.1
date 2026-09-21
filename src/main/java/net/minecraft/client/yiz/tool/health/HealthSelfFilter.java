package net.minecraft.client.yiz.tool.health;

/**
 * 「本模组自己的类」判据（发现藏血结构时排除）。
 *
 * <p><b>为什么必须排除：</b>本模组的记账 map（如 {@code EntityASMUtil.DREAM_ACCUM / DREAM_ABS_ACCUM}，
 * {@code Map<UUID,Float>} 存的是等比累积分数）完全符合「静态 Map + 键是实体/UUID + 值是数值」的
 * 藏血判据。一旦被自己的发现器当成"外部藏血 map"，读血就会读到累积分数而不是血量（生产实测 0.0336），
 * 判定阶段会把活着的实体判死、写又写不进去。这是纯正确性过滤，与"发现范围"无关——
 * 发现范围仍按旧版语义：全类路径扫、按类数量变化重扫，强度不打折。</p>
 */
public final class HealthSelfFilter {

    private HealthSelfFilter() {}

    /** 是否本模组自己的类（上游 {@code net.minecraft.client.yiz} 与下游 {@code ...yiz.xian} 同前缀）。 */
    public static boolean isOwnClass(String className) {
        return className != null && className.startsWith("net.minecraft.client.yiz");
    }
}
