# giantwarden / Mbt_test_only 逆向结论与通用加强方案

> 2026-08-21。目标：让「涨跌多空纯减法改血」通用管道按规则 A（直接减法）/ B（真实血量非 getHealth）
> / C（触发模组自我掉落+移除）/ D（不针对任何模组）击穿以下两款「改不动」的模组，且不过拟合。

---

## 一、两款模组的反改血机制（源码已确认）

### 1. giantwarden（`com.example.giantwarden.entity.GiantWarden extends Warden`）

| 层面 | 机制 | 对现有管道的失效点 |
|---|---|---|
| 真实血量 | 两个 `long` 字段 `canonicalHealthA`/`canonicalHealthB`，两种不同加密（encA：`((long)floatBits << 32) | ~floatBits` XOR 长密钥；encB：rotateLeft 包装 XOR），解码时双校验取 max | 现有 codec 只认 **float XOR**，long 字段 + 双密码 + 互补校验全部不认 |
| 冗余副本 | `authHealth`、`lastTickHealth` 各存 4 个 int 字段（`x4(v)={v^XS1..v^XS4}`），`vote()` 3-of-4 多数表决恢复 | 单字段直写会被另外 3 份 + vote 拉回 |
| 死亡门 | `reallyDead` 同样 4 个 int 表决字段；`die/kill/remove/onRemovedFromWorld` 全被 `decReallyDead()` 门控（false 时 forceClearRemovedFlag） | 非 Boolean DataParameter，DeathMarkerAccessor 找不到 |
| 伤害上限 | `DAMAGE_CAP=20`：任何单次 drop>20 都会被 rollback 到 20；tick 里 `drop=prev-now>20→setCanonical(prev-20)` | 规则 A 一次扣 500 被拉回成 20 |
| 复活 | tick 里 `!reallyDead` 时：`isRemoved→forceClearRemovedFlag`、`level.getEntity(id)==null→addFreshEntity` | forceRemoveDeep 后下一 tick 自我重挂 |
| 完整性 | `integrityCheck()` 每 tick 把负/NaN 血回正、auth 对齐 canon | 写 0 会被修回 |

**结论**：要杀它必须（1）解码 long 双密码写 canon A/B；（2）同步写 authHealth/lastTickHealth 各 4 份表决字段
（避免 rollback 与 tick 拉回）；（3）置 `reallyDead` 表决字段为 true 开门；（4）走它自己的 die/remove。

### 2. Mbt_test_only（`Mobiaton.mobiaton_01`，BedrockGuardian 等 extends GuardianHealthBase）

| 层面 | 机制 | 失效点 |
|---|---|---|
| 真实血量 | **外部单例** `AuthorityStation.CACHE`（`static ConcurrentHashMap<UUID, Entry>`），`Entry.accCipher`/`lastCipher` 是 `CipherHelper.Mci` 加密对象；`health = bossMax − decode(accCipher)` | HealthMapRegistry 只认 `Map<K=Entity,V=Number>`，这里 K=UUID、V=Entry(非 Number)，完全漏 |
| Mci 密码 | `encode(f)`：随机类型 A∈{M(/0.1),B(取负),T}，`bits=fwd(floatBits(f))^C`，`fwd=rotateLeft(reverse(x)-0x9E3779B9,17)`，C 随机盐 | 现有 BlackBoxInverseSolver 只做 XOR/scale/inverse，reverse+rotate+盐+类型标签 不认 |
| 死亡/移除门 | 普通 `boolean` 字段 `mobiaton_debug`（首服 tick 后=false）；`die/remove/setRemoved/isRemoved` 全按它门控；`setLevelCallback` 包一层拦截移除并 revive | 非 DataParameter，DeathMarkerAccessor 找不到 |
| 原生死亡 | tick：`readDmgAccPlain() >= bossMax-1` → `mobiatonDebugger("mobiaton")`（置 debug=true + setRemoved(KILLED)）+ discard | 需要先把 AuthorityStation 的 acc 写到 bossMax-1 才会触发 |
| coremod 盾 | `EntityDataShield`：Unsafe 钩 SynchedEntityData，`shouldBlockSet` 拦「foreign key」写 + 拦「血量下降写(f<=current)」；`shieldTick` 每 tick 把 ≤0 的血 DataItem 拉回 `AUTHORITATIVE` | DirectHealthFallback 的 vanilla 通道被拦/拉回 |
| 看门狗 | `MbtTicks.guardHealth`（每 level tick 读 acc vs getHealth，不一致 setAcc 拉回）+ `guardOne`（复活 + reRegister） | 与旧 Mbt 同款，但数据源移到 AuthorityStation |

**结论**：要杀它必须（1）定位 `AuthorityStation.CACHE`（静态 Map<UUID,对象>）；（2）在 Entry 里找到加密 acc 字段并解码
Mci 写 bossMax；（3）或直接置 `mobiaton_debug=true` 开门走它自己的 die/remove；（4）绕/对消 coremod 盾。

---

## 二、通用管道需补的「模式」（不写任何类名/字段名，纯类型签名 + 行为验证）

按通用性优先级（= 分析文档 P0/P1/P2 的延续）：

1. **G0「死亡/移除放行开关」**（普通 boolean 实例字段，false 时 die/remove 被拦/空转）→ 行为验证：仅对
   「已抵抗正常死亡（目标≤0 但死亡链后仍 !isRemoved）」的实体，逐个 boolean 字段设 true → 试 die →
   观察是否放行 → 未命中还原。命中即开门，再走它自己 die（触发掉落+移除）。→ 打 Mbt 的 `mobiaton_debug`、通用拒死型。
2. **S9「外部单例藏血 Map」**：扩展 HealthMapRegistry，除 `Map<K=Entity,V=Number>` 外，识别
   `static Map` 且 K∈{UUID,Integer,String(实体id)}、V∈{Number, 或含数值/密码字段的对象}（对 V=对象 递归下钻
   Entry 找密码字段）。→ 打 Mbt 的 AuthorityStation（V=Entry 需下钻）。
3. **S6b「long 字段 + 多密码 + 互补校验」**：codec 扩展 long 字段、双副本、`((hi<<32)|~lo)` 互补校验形态。
4. **S6c「salt 密码（reverse/rotate/subtract/xor + 类型标签）」**：BlackBoxInverseSolver 增加对
   `rotateLeft/reverse/subtract/xor` 组合 + 随机盐的黑箱反解（黑箱探针仍可：同一字段同一实体上 write→read
   反解盐值，因为盐在对象里可读）。
5. **G4「表决字段（N 个同型 int 字段 XOR 不同密钥、多数表决）」**：识别「一组 3~8 个同型字段共同编码同一状态」，
   写时同步写全部。→ 打 giantwarden 的 authHealth/lastTickHealth/reallyDead。
6. **G7「tick 复活 + 自我重挂」**：已有 forceRemoveDeep 复扫；对 giantwarden 需配合 G4 开门后再复扫。

---

## 三、落地顺序（每步增量、可测、不破坏已解决的 3 款）

1. ✅（本会话）G0 `RemoveGateAccessor`：普通 boolean 死亡/移除开关检测+击穿，接进 `TotalHealthOverride` 死亡路径。
2. 扩 HealthMapRegistry 至 UUID-keyed Map（S9 的 Number 子集）+ V=对象下钻（S9 完整）。
3. codec 扩 long/双密码/互补（S6b）。
4. salt 密码黑箱反解（S6c）。
5. 表决字段组识别（G4）+ giantwarden 验证。

> 全程纪律：新增一律走「类型签名 + 行为验证 + 可配置」，代码零类名/字段名/包名硬编码。
