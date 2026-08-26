package net.minecraft.client.yiz.tool.icon;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 属性图标注册中心 — 「属性 id → 图标」的单一真值源（blit 路径）。
 *
 * <p>所有 Screen/HUD/自定义 tooltip 组件的图标 blit 统一查询这里。
 * 图标可选：未注册的属性 {@link #get} 返回 null，渲染层 fallback（不 blit、文字不位移）。</p>
 *
 * <p>贴图为 sprite sheet {@code textures/font/attribute_icons.png}（160×128，5列×4行，每格32×32），
 * 由 {@link IconBlitHelper} 用 9-参 blit 按 UV 切格。</p>
 */
public final class AttributeIconRegistry {

    private AttributeIconRegistry() {}

    /** 一个属性图标的 blit 描述：贴图 + sheet UV。 */
    public record Icon(ResourceLocation texture, int u, int v, int regionW, int regionH, int sheetW, int sheetH) {}

    private static final Map<String, Icon> MAP = new HashMap<>();

    private static final ResourceLocation SHEET =
        ResourceLocation.fromNamespaceAndPath("yizmodqzk", "textures/font/attribute_icons.png");

    private static final int GRID = 32;     // 每格像素（源 PNG 尺寸）
    private static final int COLS = 5;      // sheet 列数
    private static final int ROWS = 4;      // sheet 行数
    private static final int SHEET_W = COLS * GRID;  // 160
    private static final int SHEET_H = ROWS * GRID;  // 128

    /** 属性 id → Icon，无图标返回 null。id 兼容 "generic.max_health"(vanilla) 与 "spell_power"(yiz)。 */
    public static Icon get(String attrId) {
        return attrId == null ? null : MAP.get(attrId);
    }

    /** 是否有图标（决定文字是否位移）。 */
    public static boolean has(String attrId) {
        return attrId != null && MAP.containsKey(attrId);
    }

    /** blit 推荐目标尺寸（像素）：源 32 缩半到 16，与文字行协调。 */
    public static int iconPx() {
        return GRID / 2;
    }

    private static Icon build(int sheetIndex) {
        int col = sheetIndex % COLS;
        int row = sheetIndex / COLS;
        return new Icon(SHEET, col * GRID, row * GRID, GRID, GRID, SHEET_W, SHEET_H);
    }

    /** 注册一个 attrId → sheetIndex。同一 sheetIndex 可被多个 attrId 共享。 */
    private static void reg(String attrId, int sheetIndex) {
        MAP.put(attrId, build(sheetIndex));
    }

    static {
        // ── 顺序 = sheet 行优先格序（textures/font/attribute_icons.png 拼接顺序）。 ──
        reg("spell_power",            0);  // 法强
        reg("spell_defense",          1);  // 法术防御
        reg("attack_strength",        2);  // 攻击强度
        reg("armor",                  3);  // 攻击强度防御（yizmodqzk:armor，非 vanilla generic.armor）
        reg("shield_value",           4);  // 护盾值
        reg("damage_block",           5);  // 格挡
        reg("cooldown_value",         6);  // 冷却值
        reg("cooldown_reduction",     7);  // 攻击速度
        reg("attack_range",           8);  // 延距 / 交互距离
        reg("move_speed",             9);  // 移动速度（yiz）
        reg("generic.movement_speed", 9);  // 移动速度（vanilla，共享图标）
        reg("max_run_speed",         10);  // 最大移动速度
        reg("max_mana",              11);  // 最大法力值
        reg("generic.max_health",    12);  // 最大生命
        reg("life_regen_rate",       13);  // 自然恢复（共享）
        reg("life_regen_pct",        13);  // 自然恢复（共享）
        reg("crit_rate",             14);  // 暴击概率
        reg("crit_damage",           15);  // 暴击效果
        reg("mana_cost",             16);  // 法力消耗（共享）
        reg("mana_cost_per_sec",     16);  // 法力消耗（共享）
    }
}
