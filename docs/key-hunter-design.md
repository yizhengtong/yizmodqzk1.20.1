# 通用「内部 Key 判定」攻取设计（KeyHunter）

> 目标：以**反射/Unsafe/字节码**三层手段，撰写一套**不依赖目标模组任何具体名字**的通用方法，
> 获取并攻破「外部 jar 模组内部 key 判定」这一类防护。
>
> 说明：参考素材只用于**归纳防护模式**（模式是通用的），本设计与代码中**不含任何针对该素材的硬编码**
> （无类名、无字段名、无包名假设），一切以「类型特征 + 字节码形状 + 可配置包前缀」驱动。

---

## 一、问题域：什么叫「内部 key 判定」

从素材归纳，这类防护（以及同类模组的「内部 key」系统）几乎总是由**四个支柱**拼成：

| 支柱 | 通用形态 | 在素材中的投影（仅作模式样本） |
|---|---|---|
| ① 密钥字段 | `private static final byte[]/String/long`，启动时随机生成或编译期嵌入 | 128 字节 `SecureRandom.generateSeed` 存进静态字段 |
| ② 调用栈鉴权 | `StackWalker` 遍历调用帧，校验每个帧的包名前缀白名单，并**拒绝 `java.lang.reflect.*` 与 `java.lang.invoke.*` 帧** | 白名单数组 + `isBadFrame` |
| ③ 会话握手 | `ThreadLocal` 存放「当前线程是否已通过特权路径」的令牌，验证时与密钥字段比对 | `ThreadLocal<byte[]>` 与 `baseValue` 比对 |
| ④ 受保护下沉点 | 所有写入 API（Map.put、setHealth…）先过 ②③ 判定才生效 | 包一层 `checkAccess()` 的集合/管理器 |

**「内部 key 判定」= ② + ③ + ① 的组合**：任何外部代码调用时，要么栈上出现
`java.lang.reflect.*`/陌生包帧（② 拦截），要么拿不到 ③ 的 ThreadLocal 令牌（③ 拦截），
要么令牌不等于密钥（① 拦截）。三层中只要有一层失守，判定就被攻破。

## 二、为什么纯反射方案会失败（必须先想清楚）

1. **`setAccessible(true)` 本身就在栈上留下 `java.lang.reflect` 帧** —— ② 的栈鉴权正是为反射设计的，
   反射调 `Field.get` 读密钥 → 栈帧含 `java.lang.reflect.Field` → 直接判负。
2. **`Method.invoke` 同理**，`java.lang.invoke.MethodHandle` 调用也会留下 `java.lang.invoke.*` 帧。
3. **`static final` 字段**：即使没有栈鉴权，反射写 final 字段也可能被 JIT 常量折叠干扰（读则通常无碍）。
4. **ThreadLocal 握手**：反射改变不了「当前线程 ThreadLocal 里的值」这一事实，除非你能同时拿到密钥本身。

结论：**反射负责「发现」，Unsafe 负责「夺取」，字节码层负责「伪造/截取」**。三层各司其职。

## 三、Unsafe 方案的原理（为什么它天然克制这类防护）

- `sun.misc.Unsafe` 的 `staticFieldBase / staticFieldOffset / getObject / putObject`：
  - **不检查访问修饰符**（private/final 一概无视）；
  - **不触发 `setAccessible`**，因此栈上**没有 `java.lang.reflect.*` 帧** —— ② 的「反射帧检测」形同虚设；
  - **不经过方法调用**，因此 ② 的「调用方包名检查」根本看不到我们（我们只动内存，不调用它们的判定方法）；
  - 可以**覆写 static final 引用字段**（引用类型不会被编译期常量折叠）。
- 取 `Unsafe` 实例本身用「构造器 + theUnsafe 兜底」双路（与素材同款路径，说明该路径在此环境可用）。

## 四、通用四步配方（运行时层，无需 agent）

**第 1 步 · 枚举（找到目标 jar 里已加载的所有类）**
- 首选：`Instrumentation.getAllLoadedClasses()`（本模组已有 agent 基础设施，桥接已有）。
- 兜底（无 agent）：Unsafe 读 `java.lang.ClassLoader.classes`（`Vector<Class<?>>`）→ 再读
  `Vector.elementData`，沿「上下文加载器 + 所有线程的上下文加载器 + 父链」枚举 —— 纯内存枚举，
  不加载任何新类、不触发 `<clinit>`。
- 按**可配置包前缀**过滤（`/yiz key scan <前缀...>`），保证「通用」而不是「针对」。

**第 2 步 · 定位（按类型普查静态字段，不看名字）**
对每个类 `getDeclaredFields()`（合法 API，无需 setAccessible），只按**字段类型**分类：
- `byte[]` → 密钥候选（长度 ≥ 8 即报，≥ 16 为强候选，报香农熵 + hex 预览）；
- `String` → 密钥候选（hex / base64 / UUID 形态正则过滤，降噪）；
- `java.util.UUID` → 密钥候选；
- `ThreadLocal` → 握手点（③ 的攻击面）；
- `StackWalker` → 鉴权闸门（② 的攻击面，仅当该类同时含「包前缀形 String[]」或类名含
  check/guard/access/secure/stack 等弱特征时才动，避免误伤）；
- `String[]` → 白名单数组候选（元素 ≥50% 形如 `xxx.` 的包前缀才动）。

**第 3 步 · 夺取（Unsafe 直读密钥字段）**
`staticFieldBase + staticFieldOffset + getObject`，全程无反射帧、无访问检查。
密钥若为编译期嵌入 → 直接得手；若为会话随机 → 同样得手（它总得存进某个静态字段/对象字段）。

**第 4 步 · 通行（数据层白名单扩展 + 握手伪造 + 直接调用）**
- **关键约束**：`java.lang.StackWalker` 是 **final 类**（javap 确认 `public final class java.lang.StackWalker`），
  无法子类化出「空帧 Walker」，任何真实实例都走真栈 → 运行时层**不能**用字段置换中和 walk 型闸门
  （只能检测并报告，提示走字节码层）。
- **运行时层能做**：
  1. **白名单数组扩展**：目标静态 `String[]` 白名单追加 `net.minecraft.client.yiz.`（本家族包根，
     Unsafe `putObject` 覆写，无视 private/final）—— 目标若按包前缀校验调用帧，我们的直接调用即合法；
  2. **握手伪造**：用夺取到的密钥对每个 `ThreadLocal` 握手点 `set(key)`，在特权语义下执行后恢复原值；
  3. **调用纪律**：通行消费方必须**直接调用**目标 API（栈上无 `java.lang.reflect.*` /
     `java.lang.invoke.*` 帧）——反射只用于「发现」，从不用于「调用」。
- **walk/getCallerClass 型闸门**（除包前缀外还查调用者身份/注解的硬化目标）留给**字节码层**
  的调用点改写（见第五节）。

四步走完，判定方法**原样未动**，但「调用者身份 + 会话令牌 + 密钥」三要素全部由我们满足 ——
这是**语义级攻破**，比改判定方法返回值更难被自查发现。

## 五、字节码层（agent retransform，对付「密钥不在字段里」的硬化目标）

若密钥是**每次计算出来的**（从不落字段）、或闸门不用 `StackWalker` 而用 `getCallerClass`，
运行时层仍有三件**类型驱动、与名字无关**的武器（`KeyCompareDumpTransformer`）：

1. **`StackWalker.walk` 调用点改写**：凡目标包内出现
   `INVOKEVIRTUAL java/lang/StackWalker.walk (Function)Object`（擦除描述符唯一），
   改写为 `KeyDumpBridge.nullWalk` —— StackWalker 是 final 类、运行时层无法置换实例，
   <b>这是中和 walk 型闸门的唯一通用路径</b>；不依赖字段位置，对混淆/内联也生效。
2. **`StackWalker.getCallerClass` 调用点改写**：`POP + LDC 本类名 + INVOKESTATIC trustedCaller`，
   让「调用者」恒等于闸门自己的类 —— 必在白名单内。
3. **比较点密钥截取（核心思想：密钥永远活在栈上）**：无论密钥怎么算出来，最终必然要与输入做
   `Arrays.compare([B[B) / Arrays.equals / Arrays.mismatch / MessageDigest.isEqual / String.equals`。
   在这些**比较指令前**插入 `DUP2 + LDC site + INVOKESTATIC logCompare`：把两个操作数
   **在比较发生的那一刻**原样转储 —— 其中必有一个是期望密钥。密钥从此与它的存储方式无关。

第 3 条是「攻克此类内部 key 项目」的**终极通用手段**：防静态字段扫描、防 Unsafe 读、防名字混淆，
都防不住「比较发生时操作数在操作数栈上」这个物理事实。

## 六、升级阶梯与反制对照表

| 防守方手段 | 通用攻取手段 | 层级 |
|---|---|---|
| 私有/私有 final 密钥字段 | Unsafe 静态读（无反射帧） | 运行时 |
| 栈帧包名白名单 | 白名单数组追加本家族包根 + 直接调用（无反射帧） | 运行时 |
| 拒绝 reflect/invoke 帧 | 全程不用反射调用（只 Unsafe 内存读写） | 运行时 |
| ThreadLocal 握手 | 夺取密钥 + `set(key)` 伪造 | 运行时 |
| 字段名混淆 | **按类型普查**（byte[]/ThreadLocal/StackWalker/String[]），不看名字 | 运行时 |
| 密钥每次重算、不落字段 | 比较点 `DUP2` 转储（密钥活在栈上） | 字节码 |
| walk/getCallerClass 调用点内联 | agent 调用点改写（nullWalk/trustedCaller） | 字节码 |
| 判定方法整体混淆 | 以上全部与判定方法名无关，无需定位判定方法 | — |
| 多个冗余判定器 | `/yiz key watch` 前缀批量 retransform，全覆盖 | 字节码 |
| native/服务端校验 | 超出本方案：native 走 JVMTI，服务端校验需协议层伪造（另案） | — |

## 七、防守侧启示（写给自己的模组，如何防这类通用攻取）

1. **不要在静态字段里存原始密钥**：静态字段是 Unsafe 普查的头号目标；改为每次从不可逆向的
   服务端挑战/响应派生，或拆分到实例字段并混入随机盐。
2. **不要依赖「StackWalker 字段本身」**：`StackWalker` 虽为 final 类（字段无法被实例置换），
   但 `walk/getCallerClass` **调用点**可以被 agent 改写（nullWalk/trustedCaller）；
   对判定方法做自身字节码校验才能发现这类注入。
3. **校验调用方字节码指纹**：比对判定方法自己的 classfile 哈希（防 retransform）。
4. **关键判定双路交叉**：一条走 JVM 内判定，一条走 native/服务端，单点失守不致命。
5. **白名单数组内容本身参与校验**（如哈希进密钥派生），防「数组追加」式扩展。

## 八、代码清单（对应实现）

| 文件 | 职责 |
|---|---|
| `src/main/java/net/minecraft/client/yiz/tool/key/UnsafeAccess.java` | Unsafe 单例（构造器 + theUnsafe 兜底） |
| `.../tool/key/FieldHandle.java` | 静态/实例字段的 Unsafe 读写句柄（get/put Object/long/int） |
| `.../tool/key/LoadedClassEnumerator.java` | 目标包前缀下已加载类枚举（Instrumentation → ClassLoader.classes 兜底） |
| `.../tool/key/StaticFieldCensus.java` | 按类型普查静态字段：密钥候选/闸门/白名单/握手点 |
| `.../tool/key/StackGateNeutralizer.java` | 白名单数组追加扩展（StackWalker 为 final 类，运行时仅检测不置换） |
| `.../tool/key/HandshakeForger.java` | ThreadLocal 握手伪造（set 密钥 → 执行 → 恢复） |
| `.../tool/key/KeyDumpBridge.java` | agent 注入代码的落点：nullWalk / trustedCaller / logCompare / 密钥仓库 |
| `.../tool/key/KeyHunter.java` | 四步配方门面 + KeyHuntReport |
| `agent/src/net/minecraft/client/yiz/agent/KeyCompareDumpTransformer.java` | walk/getCallerClass 调用点改写 + 比较点密钥转储 |
| `.../core/asm/AgentBridge.java`（扩展） | key watch 前缀管理 + 按需 retransform |
| `.../tool/YizKeyCommand.java` | `/yiz key scan|watch|unwatch|report` |
