package io.github.createmeow.ysm_corpse.client;

import io.github.createmeow.ysm_corpse.YsmAttachmentBridge;
import io.github.createmeow.ysm_corpse.YsmCorpseCompat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * 客户端事件：高频缓存活体玩家的 YSM 渲染模型（死亡后 YSM 会重置状态，
 * 必须在活着时捕获）；实体离开或断开连接时清理缓存，防止假人泄漏。
 */
@EventBusSubscriber(modid = YsmCorpseCompat.MOD_ID, value = Dist.CLIENT)
public final class ClientGameHandlers {
    private ClientGameHandlers() {
    }

    /** 玩家模型渲染前捕获（死亡动画期间仍在渲染，赶在状态重置前抓到模型）。 */
    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        YsmAttachmentBridge.cacheLiveModel(event.getEntity());
    }

    /** 客户端 tick 兜底捕获（第一人称等不渲染自身模型的场景）。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            YsmAttachmentBridge.cacheLiveModel(mc.player);
            // 独立于模型对象缓存：直接读本地玩家的渲染状态模型 ID 与
            // 同步附件外观（YSM 重进存档后服务端侧为空，靠这里兜底）
            try {
                String stateId = YsmAttachmentBridge.readStateModelId(mc.player);
                YsmAttachmentBridge.Appearance fromSync = YsmAttachmentBridge.appearanceFromTag(
                        YsmAttachmentBridge.readAppearanceTag(mc.player, mc.player.level().registryAccess()));
                if (fromSync.valid()) {
                    YsmAttachmentBridge.noteClientAppearance(mc.player.getUUID(), fromSync);
                } else if (!stateId.isBlank()) {
                    YsmAttachmentBridge.noteClientAppearance(mc.player.getUUID(),
                            new YsmAttachmentBridge.Appearance(stateId, ""));
                }
            } catch (Throwable ignored) {
                // 捕获失败不影响主流程
            }
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            ClientVisualCache.forget(event.getEntity().getId());
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientVisualCache.clear();
        YsmAttachmentBridge.clearClientAppearances();
    }
}
