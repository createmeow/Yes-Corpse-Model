package io.github.createmeow.ysm_corpse.client;

import io.github.createmeow.ysm_corpse.DebugLog;
import io.github.createmeow.ysm_corpse.PlayerCorpseSnapshot;
import io.github.createmeow.ysm_corpse.YsmAttachmentBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 客户端侧 YSM 外观绑定：
 * <ol>
 *   <li>外观优先取自死亡快照；</li>
 *   <li>快照缺失时（如 YSM 仅客户端安装），从同 UUID 在线玩家的
 *       YSM 同步附件里读取；</li>
 *   <li>先在假人的 YSM 同步附件上调用 void(String, String) setter
 *       （负责贴图选择），再在 YSM 渲染状态附件上调用 setModel(String)
 *       ——后者才是 YSM 2.6.x 渲染管线真正读取的数据。</li>
 * </ol>
 */
public final class YsmModelBinder {
    private static final Logger LOGGER = LoggerFactory.getLogger(YsmModelBinder.class);

    /** 绑定成功后不再重复调用 setter（避免打断 YSM 动画状态）。 */
    private static final Map<AbstractClientPlayer, String> BOUND = new WeakHashMap<>();

    /** 每个假人每类诊断消息仅打印一次（按 entityId+格式串区分）。 */
    private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private YsmModelBinder() {
    }

    public static boolean bind(AbstractClientPlayer dummy, PlayerCorpseSnapshot snapshot) {
        YsmAttachmentBridge.Appearance appearance =
                new YsmAttachmentBridge.Appearance(snapshot.ysmModel(), snapshot.ysmTexture());
        if (!appearance.valid()) {
            appearance = readLiveAppearance(snapshot);
        }
        if (!appearance.valid()) {
            // 服务端 YSM 数据可能为空（同步不走附件），退而取死亡前缓存的模型 ID
            YsmAttachmentBridge.LiveModel cached =
                    YsmAttachmentBridge.peekLiveModel(snapshot.playerUuid());
            if (cached != null && cached.modelId() != null && !cached.modelId().isBlank()) {
                appearance = new YsmAttachmentBridge.Appearance(cached.modelId(), "");
            }
        }
        if (!appearance.valid()) {
            logOnce(dummy, "no appearance for {} (snapshot model='{}')", dummy.getGameProfile().getName(), snapshot.ysmModel());
            return false;
        }
        String texture = appearance.textureId().isBlank() ? "default" : appearance.textureId();
        String binding = appearance.modelId() + "\u0000" + texture;
        if (binding.equals(BOUND.get(dummy))) {
            return true;
        }
        boolean synced = bindSyncAttachment(dummy, appearance, texture);
        AbstractClientPlayer live = findLivePlayer(snapshot);
        boolean rendered = ensureDiscoveries()
                && YsmAttachmentBridge.bindRenderState(dummy, live, appearance.modelId());
        // 仅当模型对象真正注入成功才标记 BOUND；否则（如模型 zip 仍在异步加载）
        // 保持未绑定状态，后续渲染帧继续重试，直至 YSM 注册表合入该模型
        if (rendered && YsmAttachmentBridge.lastBindModelOk) {
            BOUND.put(dummy, binding);
            logOnce(dummy, "bound render state: model='{}' texture='{}' (sync attachment: {}) "
                            + "snapshot-id='{}' dummy-id='{}' live-id='{}'; detail: {}; state fields: {}",
                    appearance.modelId(), texture, synced,
                    appearance.modelId(),
                    YsmAttachmentBridge.readStateModelId(dummy),
                    live != null ? YsmAttachmentBridge.readStateModelId(live) : "no-live",
                    YsmAttachmentBridge.lastBindDetail,
                    YsmAttachmentBridge.describeState(YsmAttachmentBridge.getRenderState(dummy)));
        } else {
            logOnce(dummy, "render state binding unavailable (sync attachment: {}), model='{}'; detail: {}",
                    synced, appearance.modelId(), YsmAttachmentBridge.lastBindDetail);
        }
        return rendered;
    }

    /** 绑定同步数据附件（模型 + 贴图选择），失败不致命。 */
    private static boolean bindSyncAttachment(AbstractClientPlayer dummy,
                                              YsmAttachmentBridge.Appearance appearance, String texture) {
        Object data = YsmAttachmentBridge.getOrCreateData(dummy, dummy.level().registryAccess());
        if (data == null) {
            return false;
        }
        Method setter = YsmAttachmentBridge.appearanceSetter(data.getClass());
        if (setter == null) {
            return false;
        }
        try {
            setter.setAccessible(true);
            setter.invoke(data, appearance.modelId(), texture);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 用场上任一在线玩家发现 YSM 渲染状态附件与同步数据附件（渲染帧会重试直至成功）。 */
    private static boolean ensureDiscoveries() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        boolean any = false;
        for (AbstractClientPlayer player : level.players()) {
            if (YsmAttachmentBridge.discoverRenderType(player)) {
                any = true;
            }
            YsmAttachmentBridge.discoverSyncType(player);
        }
        return any;
    }

    /** 找快照对应的活体玩家（作为模型克隆来源）。 */
    private static AbstractClientPlayer findLivePlayer(PlayerCorpseSnapshot snapshot) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        for (AbstractClientPlayer player : level.players()) {
            if (player.getUUID().equals(snapshot.playerUuid())) {
                return player;
            }
        }
        return null;
    }

    /** 快照没有外观信息时，从在线玩家的 YSM 附件里读。 */
    private static YsmAttachmentBridge.Appearance readLiveAppearance(PlayerCorpseSnapshot snapshot) {
        AbstractClientPlayer player = findLivePlayer(snapshot);
        if (player == null) {
            return YsmAttachmentBridge.Appearance.EMPTY;
        }
        CompoundTag tag = YsmAttachmentBridge.readAppearanceTag(player, player.level().registryAccess());
        return YsmAttachmentBridge.appearanceFromTag(tag);
    }

    private static void logOnce(AbstractClientPlayer dummy, String format, Object... args) {
        // 开关关闭时不做去重，保证打开开关后仍能看到首条诊断
        if (!DebugLog.isEnabled()) {
            return;
        }
        String key = dummy.getId() + "\u0000" + format;
        if (!LOGGED.add(key)) {
            return;
        }
        LOGGER.info("[ysm_corpse] " + format, args);
    }
}
