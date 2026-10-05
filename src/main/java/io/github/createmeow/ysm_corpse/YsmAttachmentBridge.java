package io.github.createmeow.ysm_corpse;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.util.INBTSerializable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 与 Yes Steve Model（YSM）的运行时桥接。
 * <p>
 * YSM 把每个玩家的模型/贴图选择保存在一个 NeoForge 附件
 * （{@link AttachmentType}）里，且该类型在不同版本间存在混淆，
 * 因此这里不写死类名，而是扫描玩家已有的附件表：
 * <ul>
 *   <li>值为 YSM 包名前缀下的对象</li>
 *   <li>其类中存在 {@code void (String, String)} 形态的 setter</li>
 * </ul>
 * 找到后即可在死亡快照中序列化外观（{@code model_id}/{@code select_texture}），
 * 或在客户端把外观绑定到假人玩家身上。
 */
public final class YsmAttachmentBridge {
    public static final String MODEL_ID_KEY = "model_id";
    public static final String TEXTURE_KEY = "select_texture";
    static final String[] YSM_PACKAGES = {"com.elfmcys.yesstevemodel", "yesman.ysm"};

    /** type 与 dataClass 的发现结果缓存，key 为持有者实体（弱引用）。 */
    private static final Map<Entity, Discovered> DISCOVERED = new WeakHashMap<>();
    /** 按已发现的 dataClass 缓存 setter，避免重复扫描。 */
    private static volatile Method cachedSetter;
    private static volatile Class<?> cachedDataClass;
    private static volatile AttachmentType<?> cachedType;

    /** YSM 2.6.x 渲染管线实际读取的"渲染状态附件"（非序列化、宿主构造）。 */
    private static volatile AttachmentType<?> cachedRenderType;
    private static volatile Class<?> cachedRenderHolderClass;
    private static volatile Method cachedStateFactory;
    private static volatile Method cachedStateSetModel;
    /** 状态类的"推进/取模型"方法 (float, boolean) -> YSM 模型，负责 join 异步构建的 Future。 */
    private static volatile Method cachedStateTick;
    /** 状态字段缓存：模型 ID（String）、模型对象、onModelSet、内部工厂。 */
    private static volatile Field cachedModelIdField;
    private static volatile Field cachedModelField;
    private static volatile Field cachedInnerField;
    private static volatile Method cachedOnModelSet;
    private static volatile Method cachedInnerFactory;

    /** 最近一次 bindRenderState 的逐步诊断（供 binder 日志输出）。 */
    public static volatile String lastBindDetail = "(not run)";

    /** 最近一次 bindRenderState 是否真正拿到了模型对象（BOUND 判定依据）。 */
    public static volatile boolean lastBindModelOk;

    /** 注册表 Map 的 value 类型（= YSM 模型对象类型），由注册表扫描锚定。 */
    private static volatile Class<?> cachedModelType;

    /** 玩家死亡前捕获的活体渲染模型（模型对象 + 对应 ID）。 */
    public record LiveModel(Object model, String modelId) {
    }

    /**
     * 活体模型缓存：YSM 会在死亡动画后把玩家渲染状态重置成默认/空，
     * 死亡后再读活体状态只能拿到空壳，因此必须在玩家活着时持续缓存。
     */
    private static final Map<java.util.UUID, LiveModel> LIVE_MODELS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 尝试缓存玩家的当前渲染模型与模型 ID。
     * <p>
     * 实测 YSM 活体渲染时状态的模型对象字段可能一直为 null（渲染经由内部
     * 上下文/静态 Map），因此只要内部上下文有效且模型 ID 非空就缓存。
     * YSM 死亡动画结束后会把状态重置成空壳（上下文置 null），此时跳过
     * 缓存以保留死亡前的值。
     * 由客户端 tick / 玩家渲染事件高频调用，必要时先做渲染附件发现。
     */
    public static void cacheLiveModel(Entity player) {
        try {
            if (cachedRenderType == null && !discoverRenderType(player)) {
                return;
            }
            if (cachedModelField == null) {
                Object state = getRenderState(player);
                if (state == null) {
                    return;
                }
                resolveStateFields(state.getClass());
            }
            Object state = getRenderState(player);
            if (state == null) {
                return;
            }
            Object model = cachedModelField != null ? cachedModelField.get(state) : null;
            Object id = cachedModelIdField != null ? cachedModelIdField.get(state) : null;
            String modelId = id instanceof String s ? s : "";
            Object inner = cachedInnerField != null ? cachedInnerField.get(state) : null;
            if (model == null && (modelId.isBlank() || inner == null)) {
                // 状态尚未被 YSM 初始化，或已被死亡重置为空壳——
                // 不覆盖缓存里死亡前的有效值
                return;
            }
            LIVE_MODELS.put(player.getUUID(), new LiveModel(model, modelId));
        } catch (Throwable t) {
            // 缓存失败不影响主流程
        }
    }

    /** 取玩家死亡前缓存的模型。 */
    public static LiveModel peekLiveModel(java.util.UUID uuid) {
        return uuid == null ? null : LIVE_MODELS.get(uuid);
    }

    /**
     * 服务端死亡捕获用（单人模式客户端/服务端共享 JVM）：
     * 读取客户端在玩家死亡前缓存的模型 ID。
     * YSM 的同步附件未序列化，重进存档后服务端侧为空，
     * 此时只能靠客户端侧的活体状态兜底。
     */
    public static String cachedClientModelId(java.util.UUID uuid) {
        LiveModel cached = peekLiveModel(uuid);
        return cached != null ? cached.modelId() : "";
    }

    /**
     * 客户端外观缓存：客户端每 tick 读取本地玩家的渲染状态模型 ID 与
     * 同步附件外观并写入（新的非空值覆盖旧值），供服务端死亡捕获兜底。
     */
    private static final Map<java.util.UUID, Appearance> CLIENT_APPEARANCES = new java.util.concurrent.ConcurrentHashMap<>();

    /** 记录客户端侧读到的玩家外观（新的非空值直接覆盖——
     * YSM 登录后先给初始值 "default"，真模型异步加载后才出现，
     * 必须允许更新；死亡重置发生在死亡捕获之后，无竞争）。 */
    public static void noteClientAppearance(java.util.UUID uuid, Appearance appearance) {
        if (uuid == null || appearance == null || !appearance.valid()) {
            return;
        }
        CLIENT_APPEARANCES.put(uuid, appearance);
    }

    /** 服务端死亡捕获兜底：读取客户端缓存的玩家外观。 */
    public static Appearance clientAppearance(java.util.UUID uuid) {
        Appearance cached = uuid == null ? null : CLIENT_APPEARANCES.get(uuid);
        return cached != null ? cached : Appearance.EMPTY;
    }

    /** 断开连接时清空客户端外观缓存。 */
    public static void clearClientAppearances() {
        CLIENT_APPEARANCES.clear();
    }

    /** YSM 模型注册表类与其静态模型 Map / 待合入队列 / 查找方法（运行时 jar 扫描发现）。 */
    private static volatile Class<?> cachedRegistryClass;
    private static volatile Field cachedRegistryModelMap;
    private static volatile Field cachedRegistryQueue;
    private static volatile Method cachedRegistryLookup;
    private static volatile String lastRegistryNote = "(none)";

    private YsmAttachmentBridge() {
    }

    /** 记录某实体附件表中找到的 YSM 数据入口。 */
    private record Discovered(AttachmentType<?> type, Object data) {
    }

    static boolean isYsmClass(Class<?> type) {
        Package pkg = type.getPackage();
        String name = pkg == null ? type.getName() : pkg.getName();
        for (String prefix : YSM_PACKAGES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 扫描实体附件表，找到 YSM 数据对象与其附件类型。 */
    static Discovered discover(Entity holder) {
        Discovered cached = DISCOVERED.get(holder);
        if (cached != null) {
            return cached;
        }
        Discovered found = null;
        try {
            Field mapField = AttachmentHolder.class.getDeclaredField("attachments");
            mapField.setAccessible(true);
            if (mapField.get(holder) instanceof Map<?, ?> attachments) {
                for (Map.Entry<?, ?> entry : attachments.entrySet()) {
                    Object value = entry.getValue();
                    if (value == null || !(entry.getKey() instanceof AttachmentType<?> type)) {
                        continue;
                    }
                    if (isYsmClass(value.getClass()) && appearanceSetter(value.getClass()) != null) {
                        found = new Discovered(type, value);
                        cachedType = type;
                        cachedDataClass = value.getClass();
                        cachedSetter = appearanceSetter(value.getClass());
                        break;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 附件表不可读
        }
        if (found != null) {
            DISCOVERED.put(holder, found);
        }
        return found;
    }

    /** 在 YSM 数据类上寻找 void(String, String) 的外观 setter。 */
    public static Method appearanceSetter(Class<?> dataClass) {
        for (Method method : dataClass.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == void.class
                    && method.getParameterCount() == 2
                    && method.getParameterTypes()[0] == String.class
                    && method.getParameterTypes()[1] == String.class) {
                return method;
            }
        }
        return null;
    }

    /**
     * 序列化实体上的 YSM 附件数据，取出 {@code model_id}/{@code select_texture}。
     * 实例不存在时可借已发现的缓存类型自动创建（默认值即无外观）。
     */
    public static CompoundTag readAppearanceTag(Entity holder, HolderLookup.Provider provider) {
        Discovered discovered = discover(holder);
        AttachmentType<?> type = discovered != null ? discovered.type() : cachedType;
        if (type == null) {
            return new CompoundTag();
        }
        try {
            // 实例不存在时（YSM 尚未写入同步数据）借缓存类型走默认工厂自动创建；
            // 单人模式下客户端发现缓存的类型与服务端共用同一注册表
            Object data = holder.getExistingDataOrNull(type);
            if (data == null) {
                data = holder.getData(type);
            }
            if (data instanceof INBTSerializable<?> serializable
                    && serializable.serializeNBT(provider) instanceof CompoundTag tag) {
                return tag;
            }
        } catch (RuntimeException | LinkageError e) {
            // 序列化失败视为无外观数据
        }
        return new CompoundTag();
    }

    /**
     * 取出（必要时新建）实体上的 YSM 数据对象。
     * 优先使用已发现缓存的类型；类型未知时尝试从其他玩家借。
     */
    public static Object getOrCreateData(Entity holder, HolderLookup.Provider provider) {
        Discovered discovered = discover(holder);
        AttachmentType<?> type = discovered != null ? discovered.type() : cachedType;
        if (type == null) {
            return null;
        }
        Object existing;
        try {
            existing = holder.getExistingDataOrNull(type);
            if (existing == null) {
                // YSM 附件类型若带默认值则可自动创建，否则手动构造
                existing = holder.getData(type);
                if (existing == null) {
                    Class<?> dataClass = discovered != null ? discovered.data().getClass() : cachedDataClass;
                    if (dataClass != null) {
                        existing = dataClass.getConstructor().newInstance();
                        setDataRaw(holder, type, existing);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
        return existing;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setDataRaw(Entity holder, AttachmentType type, Object value) {
        holder.setData(type, value);
    }

    /**
     * 在在线玩家附件表中发现 YSM "渲染状态"附件（渲染管线真正读取的那份）。
     * 特征：YSM 包、非 INBTSerializable、含宿主实体构造器，
     * 且有无参方法返回"带 void(String) 方法与宿主构造器"的 YSM 状态类。
     *
     * @return 是否已发现（或此前已缓存）
     */
    public static boolean discoverRenderType(Entity livePlayer) {
        if (cachedRenderType != null) {
            return true;
        }
        try {
            Field mapField = AttachmentHolder.class.getDeclaredField("attachments");
            mapField.setAccessible(true);
            if (mapField.get(livePlayer) instanceof Map<?, ?> attachments) {
                for (Map.Entry<?, ?> entry : attachments.entrySet()) {
                    Object value = entry.getValue();
                    if (value == null || !(entry.getKey() instanceof AttachmentType<?> type)) {
                        continue;
                    }
                    Class<?> cls = value.getClass();
                    if (!isYsmClass(cls) || INBTSerializable.class.isAssignableFrom(cls)) {
                        continue;
                    }
                    Constructor<?> holderCtor = entityCtor(cls);
                    if (holderCtor == null) {
                        continue;
                    }
                    for (Method factory : cls.getMethods()) {
                        if (Modifier.isStatic(factory.getModifiers())
                                || factory.getParameterCount() != 0
                                || factory.getReturnType() == void.class
                                || !isYsmClass(factory.getReturnType())) {
                            continue;
                        }
                        Class<?> stateClass = factory.getReturnType();
                        Method setModel = uniqueVoidStringMethod(stateClass);
                        if (setModel == null || entityCtor(stateClass) == null) {
                            continue;
                        }
                        // 强判别：渲染管线真正读取的状态类必须有"取模型"方法
                        // 与内部上下文字段；YSM 挂在玩家身上的其他辅助状态
                        // （如第一人称/装备状态）不具备这两个特征
                        Method tick = findStateTick(stateClass);
                        if (tick == null || !hasInnerContextField(stateClass)) {
                            continue;
                        }
                        cachedRenderType = type;
                        cachedRenderHolderClass = cls;
                        cachedStateFactory = factory;
                        cachedStateSetModel = setModel;
                        cachedStateTick = tick;
                        return true;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 附件表不可读，稍后重试
        }
        return false;
    }

    /** 找唯一的 public void(String) 实例方法；不唯一则视为不符合。 */
    private static Method uniqueVoidStringMethod(Class<?> cls) {
        Method found = null;
        for (Method method : cls.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == void.class
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == String.class) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }

    /**
     * 找状态类继承链上的"取模型"方法：(float, boolean) -> YSM 模型类型
     * （负责 join 异步构建 Future）。优先取最派生类的声明方法，含非 public。
     */
    private static Method findStateTick(Class<?> stateClass) {
        for (Class<?> c = stateClass; c != null && isYsmClass(c); c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 2
                        && method.getParameterTypes()[0] == float.class
                        && method.getParameterTypes()[1] == boolean.class
                        && isYsmClass(method.getReturnType())) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        for (Method method : stateClass.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 2
                    && method.getParameterTypes()[0] == float.class
                    && method.getParameterTypes()[1] == boolean.class
                    && isYsmClass(method.getReturnType())) {
                return method;
            }
        }
        return null;
    }

    /** 状态类继承链上是否存在类型名含 {@code $} 的内部上下文字段（真状态类特征）。 */
    private static boolean hasInnerContextField(Class<?> stateClass) {
        for (Class<?> c = stateClass; c != null && isYsmClass(c); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && field.getType().getName().contains("$")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 在活体玩家上发现 YSM 同步数据附件类型（供假人同步绑定用）。 */
    public static boolean discoverSyncType(Entity livePlayer) {
        discover(livePlayer);
        return cachedType != null;
    }

    /** 是否已有任何已发现的 YSM 同步数据附件类型候选（诊断用）。 */
    public static boolean hasSyncTypeCandidate() {
        return cachedType != null;
    }

    /** 找接受宿主实体（Player 可赋值）的单参 public 构造器。 */
    private static Constructor<?> entityCtor(Class<?> cls) {
        for (Constructor<?> ctor : cls.getConstructors()) {
            if (ctor.getParameterCount() == 1
                    && ctor.getParameterTypes()[0].isAssignableFrom(Player.class)) {
                return ctor;
            }
        }
        return null;
    }

    /**
     * 在假人身上创建渲染状态并绑定模型 ID。
     * 路径：getData（默认工厂按宿主实体自动创建）→ 无参状态工厂 → void(String) setModel
     * → "取模型"方法 join 异步构建。若注册表查不到模型 ID（返回空 Optional），
     * 则从同 UUID 活体玩家的状态里克隆模型对象并直接调用 onModelSet。
     *
     * @return 是否绑定成功
     */
    public static boolean bindRenderState(Entity dummy, Entity appearanceSource, String modelId) {
        AttachmentType<?> type = cachedRenderType;
        if (type == null || modelId == null || modelId.isBlank()) {
            lastBindDetail = "type=null or blank modelId";
            lastBindModelOk = false;
            return false;
        }
        lastBindModelOk = false;
        try {
            Object holder = dummy.getExistingDataOrNull(type);
            if (holder == null) {
                // 默认工厂（参数为宿主实体）会自动创建并绑定假人
                holder = dummy.getData(type);
            }
            if (holder == null && cachedRenderHolderClass != null) {
                Constructor<?> ctor = entityCtor(cachedRenderHolderClass);
                if (ctor != null) {
                    holder = ctor.newInstance(dummy);
                    setDataRaw(dummy, type, holder);
                }
            }
            if (holder == null) {
                lastBindDetail = "holder=null";
                return false;
            }
            Object state = cachedStateFactory.invoke(holder);
            if (state == null) {
                lastBindDetail = "state=null";
                return false;
            }
            resolveStateFields(state.getClass());
            cachedStateSetModel.invoke(state, modelId);
            // 关键：真实玩家由 YSM 每帧调用"取模型"方法来 join 异步构建的
            // Future 并初始化内部上下文/动画字段；假人必须主动补上这一步
            if (cachedStateTick != null) {
                cachedStateTick.setAccessible(true);
                cachedStateTick.invoke(state, 1.0F, true);
            }
            // 兜底：注册表查不到模型 ID 时，从活体玩家状态或死亡前缓存克隆模型对象
            String cloneNote = "";
            if (cachedModelField != null && cachedModelField.get(state) == null) {
                cloneNote = ", registry-miss→clone";
                Object liveState = getRenderState(appearanceSource);
                Object liveModel = liveState != null && cachedModelField != null
                        ? cachedModelField.get(liveState) : null;
                cloneNote += liveState != null ? ", liveState=ok" : ", liveState=null";
                String liveId = null;
                if (liveModel == null) {
                    // YSM 在死亡动画后把活体状态重置成默认/空，改用死亡前缓存
                    LiveModel cached = peekLiveModel(appearanceSource.getUUID());
                    if (cached != null && cached.model() != null) {
                        liveModel = cached.model();
                        liveId = cached.modelId();
                        cloneNote += ", cacheHit=yes";
                    } else {
                        cloneNote += ", cacheHit=no";
                    }
                }
                if (liveModel != null) {
                    cachedModelField.set(state, liveModel);
                    if (cachedModelIdField != null && liveId != null && !liveId.isBlank()) {
                        cachedModelIdField.set(state, liveId);
                    }
                    if (cachedOnModelSet != null) {
                        cachedOnModelSet.setAccessible(true);
                        cachedOnModelSet.invoke(state, liveModel);
                    } else {
                        cloneNote += ", onModelSet-missing";
                    }
                    if (cachedInnerField != null && cachedInnerField.get(state) == null && cachedInnerFactory != null) {
                        cachedInnerFactory.setAccessible(true);
                        cachedInnerFactory.invoke(state, liveModel, Boolean.FALSE);
                    }
                    if (cachedStateTick != null) {
                        cachedStateTick.invoke(state, 1.0F, false);
                    }
                    cloneNote += ", cloned-model=" + (cachedModelField.get(state) != null ? "set" : "null");
                } else {
                    cloneNote += ", liveModel=null";
                }
            }
            if (cachedModelField != null && cachedModelField.get(state) != null) {
                lastBindModelOk = true;
                lastBindDetail = "model=set"
                        + ", modelId=" + (cachedModelIdField != null ? cachedModelIdField.get(state) : "?")
                        + ", tick=" + (cachedStateTick != null ? "ok" : "missing")
                        + ", field=" + cachedModelField.getDeclaringClass().getSimpleName()
                        + "." + cachedModelField.getName()
                        + ", type=" + cachedModelField.getType().getSimpleName()
                        + cloneNote;
                return true;
            }
            // 模型仍为空：注册表直查诊断，必要时触发刷新并重试
            String regNote;
            discoverRegistry();
            String regBefore = describeRegistry(modelId);
            if (cachedRegistryModelMap != null) {
                Map<?, ?> map = (Map<?, ?>) cachedRegistryModelMap.get(null);
                java.util.Queue<?> queue = cachedRegistryQueue != null
                        ? (java.util.Queue<?>) cachedRegistryQueue.get(null) : null;
                boolean hitBefore = map != null && map.containsKey(modelId);
                boolean refreshed = false;
                if (!hitBefore && queue != null && !queue.isEmpty()) {
                    refreshed = tryRegistryRefresh();
                }
                boolean hitAfter = cachedRegistryModelMap.get(null) instanceof Map<?, ?> m2 && m2.containsKey(modelId);
                if (!hitBefore && hitAfter) {
                    // 刷新后注册表已有此模型：重新走 setModel + 阻塞取模型
                    cachedStateSetModel.invoke(state, modelId);
                    if (cachedStateTick != null) {
                        cachedStateTick.invoke(state, 1.0F, true);
                    }
                }
                regNote = regBefore + (refreshed ? " →refreshed" : "")
                        + ", hitAfter=" + hitAfter
                        + ", retryModel=" + (cachedModelField != null && cachedModelField.get(state) != null ? "set" : "null");
            } else {
                regNote = regBefore;
            }
            boolean modelOk = cachedModelField != null && cachedModelField.get(state) != null;
            lastBindModelOk = modelOk;
            lastBindDetail = "model=" + (modelOk ? "set" : "null")
                    + ", modelId=" + (cachedModelIdField != null ? cachedModelIdField.get(state) : "?")
                    + ", tick=" + (cachedStateTick != null ? "ok" : "missing")
                    + cloneNote + ", " + regNote;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            lastBindDetail = "EXCEPTION: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            lastBindModelOk = false;
            return false;
        }
    }

    /**
     * 定位 YSM 模型注册表类（含静态 Map&lt;String, 模型&gt; 字段、待合入队列、查找方法）。
     * 优先类来源 jar；NeoForge 模块加载下 getCodeSource 可能不可用，则扫 mods 目录。
     */
    private static void discoverRegistry() {
        if (cachedRegistryClass != null || cachedStateSetModel == null) {
            return;
        }
        Class<?> origin = cachedStateSetModel.getDeclaringClass();
        String pkg = origin.getPackage().getName().replace('.', '/');
        java.util.List<java.io.File> jars = new java.util.ArrayList<>();
        String note = "";
        try {
            java.net.URI uri = origin.getProtectionDomain().getCodeSource().getLocation().toURI();
            java.io.File root = null;
            if ("file".equals(uri.getScheme())) {
                root = new java.io.File(uri);
            } else if ("union".equals(uri.getScheme())) {
                // union:/E:/...jar%23193!/ —— %23（解码为 #）出现在路径内而非 fragment，
                // 需在 # 处截断再剥掉盘符前导斜杠，否则 isFile 永远失败
                String path = uri.getRawPath();
                if (path == null) {
                    path = uri.getPath();
                }
                if (path != null) {
                    int cut = path.indexOf("%23");
                    if (cut < 0) {
                        cut = path.indexOf('#');
                    }
                    if (cut >= 0) {
                        path = path.substring(0, cut);
                    }
                    if (path.endsWith("!/")) {
                        path = path.substring(0, path.length() - 2);
                    }
                    if (path.length() > 2 && path.charAt(0) == '/' && path.charAt(2) == ':') {
                        path = path.substring(1); // "/E:/..." → "E:/..."
                    }
                    root = new java.io.File(path);
                }
            }
            if (root != null && root.isFile()) {
                jars.add(root);
            } else {
                note = "src=" + uri;
            }
        } catch (Throwable t) {
            note = "src=err:" + t.getClass().getSimpleName();
        }
        if (jars.isEmpty()) {
            try {
                java.nio.file.Path mods = net.neoforged.fml.loading.FMLPaths.MODSDIR.get();
                try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(mods)) {
                    stream.filter(p -> p.toString().endsWith(".jar")).forEach(p -> jars.add(p.toFile()));
                }
            } catch (Throwable t) {
                note += " modsdir=err:" + t.getClass().getSimpleName();
            }
        }
        for (java.io.File jar : jars) {
            if (scanJarForRegistry(jar, pkg, origin.getClassLoader())) {
                lastRegistryNote = "jar=" + jar.getName();
                return;
            }
        }
        lastRegistryNote = note.isEmpty() ? "scan-miss" : note;
    }

    /**
     * 在单个 jar 内按结构特征找注册表类：
     * static ConcurrentLinkedQueue + static Map&lt;String, YSM类&gt; + static (String)→Optional。
     * Map 的 value 类型即模型类型（缓存到 {@link #cachedModelType}）。
     */
    private static boolean scanJarForRegistry(java.io.File jar, String pkg, ClassLoader loader) {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
            for (java.util.Enumeration<java.util.jar.JarEntry> it = jf.entries(); it.hasMoreElements(); ) {
                java.util.jar.JarEntry entry = it.nextElement();
                String name = entry.getName();
                if (!name.startsWith(pkg + "/") || !name.endsWith(".class") || name.contains("$")) {
                    continue;
                }
                String clsName = name.substring(0, name.length() - 6).replace('/', '.');
                Class<?> c;
                try {
                    c = Class.forName(clsName, false, loader);
                } catch (Throwable t) {
                    continue;
                }
                Field mapField = null;
                Field queueField = null;
                // 从 ConcurrentLinkedQueue<Pair<模型类, String>> 提取模型类型。
                // 队列只有一个，不会像 Map 那样被元数据 Map 覆盖
                Class<?> queueModelType = null;
                for (Field field : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (field.getType() == java.util.concurrent.ConcurrentLinkedQueue.class) {
                        queueField = field;
                        java.lang.reflect.Type genType = field.getGenericType();
                        queueModelType = extractFirstYsmClass(genType, loader);
                    }
                }
                if (queueField == null) {
                    continue;
                }
                // 用队列提取的模型类型锚定 Map<String, 模型类>
                for (Field field : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (field.getType() == Map.class
                            && queueModelType != null
                            && field.getGenericType().getTypeName().contains(queueModelType.getName())) {
                        mapField = field;
                        break; // 第一个匹配的 Map（模型 Map）
                    }
                }
                if (mapField != null) {
                    Method lookup = null;
                    for (Method method : c.getMethods()) {
                        if (Modifier.isStatic(method.getModifiers())
                                && method.getParameterCount() == 1
                                && method.getParameterTypes()[0] == String.class
                                && method.getReturnType() == java.util.Optional.class) {
                            lookup = method;
                            break;
                        }
                    }
                    if (lookup != null) {
                        mapField.setAccessible(true);
                        queueField.setAccessible(true);
                        cachedRegistryModelMap = mapField;
                        cachedRegistryQueue = queueField;
                        cachedRegistryLookup = lookup;
                        cachedRegistryClass = c;
                        cachedModelType = queueModelType;
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    /** 递归提取泛型里的第一个 YSM 包类（用于从 Pair<模型类, String> 锚定模型类型）。 */
    private static Class<?> extractFirstYsmClass(java.lang.reflect.Type type, ClassLoader loader) {
        if (type instanceof Class<?> cls) {
            return isYsmClass(cls) ? cls : null;
        }
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            for (java.lang.reflect.Type arg : pt.getActualTypeArguments()) {
                Class<?> r = extractFirstYsmClass(arg, loader);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * 队列非空时尝试触发注册表刷新：逐个调用注册表类的 public static void() 方法，
     * 以"队列被清空或 Map 引用更换"验证哪个是刷新方法。
     */
    private static boolean tryRegistryRefresh() {
        if (cachedRegistryQueue == null || cachedRegistryModelMap == null || cachedRegistryClass == null) {
            return false;
        }
        try {
            java.util.Queue<?> queue = (java.util.Queue<?>) cachedRegistryQueue.get(null);
            if (queue == null || queue.isEmpty()) {
                return false;
            }
            java.util.List<Method> candidates = new java.util.ArrayList<>();
            for (Method method : cachedRegistryClass.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        && method.getReturnType() == void.class
                        && method.getParameterCount() == 0
                        && !Modifier.isNative(method.getModifiers())
                        && !method.isSynthetic()) {
                    candidates.add(method);
                }
            }
            candidates.sort(java.util.Comparator.comparing(Method::getName));
            Object mapBefore = cachedRegistryModelMap.get(null);
            for (Method method : candidates) {
                int before = queue.size();
                try {
                    method.invoke(null);
                } catch (Throwable t) {
                    continue;
                }
                if (queue.size() < before || cachedRegistryModelMap.get(null) != mapBefore) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // 刷新失败则放弃
        }
        return false;
    }

    /** 诊断：注册表 Map 大小、是否包含目标 key、队列长度、key 样本。 */
    @SuppressWarnings("unchecked")
    private static String describeRegistry(String modelId) {
        if (cachedRegistryModelMap == null) {
            return "reg=unresolved(" + lastRegistryNote + ")";
        }
        try {
            Map<String, Object> map = (Map<String, Object>) cachedRegistryModelMap.get(null);
            java.util.Queue<?> queue = cachedRegistryQueue != null
                    ? (java.util.Queue<?>) cachedRegistryQueue.get(null) : null;
            StringBuilder sb = new StringBuilder("reg:size=").append(map.size())
                    .append(", hit=").append(map.containsKey(modelId));
            if (cachedRegistryLookup != null) {
                try {
                    Object direct = ((java.util.Optional<?>) cachedRegistryLookup.invoke(null, modelId)).isPresent();
                    sb.append(", direct=").append(direct);
                } catch (Throwable t) {
                    sb.append(", direct=err");
                }
            }
            sb.append(", queue=").append(queue == null ? "?" : queue.size()).append(", keys=[");
            int shown = 0;
            for (String key : map.keySet()) {
                if (shown++ >= 12) {
                    break;
                }
                sb.append(key);
                if (shown < 12) {
                    sb.append(", ");
                }
            }
            sb.append(']');
            return sb.toString();
        } catch (Throwable t) {
            return "reg=error:" + t.getClass().getSimpleName();
        }
    }

    /** 解析状态继承链上的关键字段与方法（模型 ID、模型对象、onModelSet、内部工厂）。 */
    private static void resolveStateFields(Class<?> stateClass) {
        if (cachedModelIdField != null) {
            return;
        }
        try {
            // 模型类型由注册表扫描锚定（Map<String, 模型> 的 value 类型）；
            // 混淆器重载命名导致"与 tick 同名字段"会误命中动画缓存字段，
            // 必须用注册表 value 类型精确匹配模型对象字段
            Class<?> modelType = cachedModelType;
            if (modelType == null && cachedStateSetModel != null) {
                discoverRegistry();
                modelType = cachedModelType;
            }
            for (Class<?> c = stateClass; c != null && isYsmClass(c); c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    if (field.getType() == String.class && cachedModelIdField == null) {
                        cachedModelIdField = field;
                    } else if (modelType != null && field.getType() == modelType && cachedModelField == null) {
                        cachedModelField = field;
                    } else if (field.getType().getName().contains("$") && cachedInnerField == null) {
                        cachedInnerField = field;
                    }
                }
            }
            // onModelSet：void(模型类型)，沿继承链找最具体的实现
            if (cachedModelField != null) {
                Class<?> mt = cachedModelField.getType();
                for (Class<?> c = stateClass; isYsmClass(c); c = c.getSuperclass()) {
                    for (Method method : c.getDeclaredMethods()) {
                        if (!Modifier.isStatic(method.getModifiers())
                                && method.getReturnType() == void.class
                                && method.getParameterCount() == 1
                                && method.getParameterTypes()[0] == mt) {
                            cachedOnModelSet = method;
                            method.setAccessible(true);
                            break;
                        }
                    }
                    if (cachedOnModelSet != null) {
                        break;
                    }
                }
                // 内部工厂：(模型类型, boolean)，声明于模型 ID 字段所在类或其子类
                for (Class<?> c = stateClass; isYsmClass(c); c = c.getSuperclass()) {
                    for (Method method : c.getDeclaredMethods()) {
                        if (!Modifier.isStatic(method.getModifiers())
                                && method.getParameterCount() == 2
                                && method.getParameterTypes()[0] == mt
                                && method.getParameterTypes()[1] == boolean.class) {
                            cachedInnerFactory = method;
                            method.setAccessible(true);
                            break;
                        }
                    }
                    if (cachedInnerFactory != null) {
                        break;
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // 字段解析失败则跳过克隆兜底
        }
    }

    /** 读取某实体当前的模型 ID（诊断/克隆来源用）。 */
    public static String readStateModelId(Entity entity) {
        try {
            Object state = getRenderState(entity);
            if (state != null && cachedModelIdField != null) {
                Object value = cachedModelIdField.get(state);
                return value instanceof String s ? s : null;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 忽略
        }
        return null;
    }

    /** 取出假人已创建的渲染状态对象（诊断用）。 */
    public static Object getRenderState(Entity dummy) {
        AttachmentType<?> type = cachedRenderType;
        if (type == null) {
            return null;
        }
        try {
            Object holder = dummy.getExistingDataOrNull(type);
            if (holder == null) {
                return null;
            }
            return cachedStateFactory.invoke(holder);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 一次性诊断：转储状态对象继承链上全部实例字段的类型与赋值情况。 */
    public static String describeState(Object state) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c = state.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value;
                try {
                    value = field.get(state);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    continue;
                }
                sb.append(c.getSimpleName()).append('.').append(field.getName())
                        .append(':').append(field.getType().getSimpleName())
                        .append('=').append(value == null ? "null" : "set").append("; ");
            }
        }
        return sb.toString();
    }

    /**
     * 从快照（或在线玩家兜底）解析出的外观键值。
     */
    public record Appearance(String modelId, String textureId) {
        public static final Appearance EMPTY = new Appearance("", "");

        public boolean valid() {
            return !modelId.isBlank() && !"idle".equalsIgnoreCase(modelId);
        }
    }

    /** 从已序列化的 YSM 数据 tag 中读取外观。 */
    public static Appearance appearanceFromTag(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return Appearance.EMPTY;
        }
        return new Appearance(
                tag.contains(MODEL_ID_KEY) ? tag.getString(MODEL_ID_KEY) : "",
                tag.contains(TEXTURE_KEY) ? tag.getString(TEXTURE_KEY) : "");
    }
}
