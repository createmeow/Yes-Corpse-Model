package io.github.createmeow.ysm_corpse;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端事件：
 * <ol>
 *   <li>玩家死亡时抓取外观快照；</li>
 *   <li>Corpse 尸体实体进入世界时生成配套视觉实体；</li>
 *   <li>尸体被清理时联动销毁视觉实体。</li>
 * </ol>
 */
public final class GameEventHandlers {
    /** 持久化在尸体 persistent data 中的链接标记（沿用旧版本键名以兼容存档）。 */
    public static final String VISUAL_CREATED_KEY = "ysm_corpse_visual_created";
    public static final String VISUAL_UUID_KEY = "ysm_corpse_visual_uuid";

    /** 死亡玩家 UUID -> 快照，等待尸体实体进入世界时消费。 */
    private static final Map<UUID, PlayerCorpseSnapshot> PENDING_DEATHS = new ConcurrentHashMap<>();

    private GameEventHandlers() {
    }

    /** 最高优先级：在其他模组改写死亡流程前抓取外观。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof Player player) {
            try {
                PENDING_DEATHS.put(player.getUUID(), PlayerCorpseSnapshot.capture(player));
            } catch (RuntimeException | LinkageError e) {
                // 快照失败绝不能影响死亡流程
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity entity = event.getEntity();
        if (!CorpseEntityAccess.isCorpse(entity)) {
            return;
        }
        CompoundTag data = entity.getPersistentData();
        if (data.hasUUID(VISUAL_UUID_KEY) || data.contains(VISUAL_CREATED_KEY)) {
            return;
        }
        try {
            UUID playerUuid = CorpseEntityAccess.playerUuid(entity).orElse(null);
            PlayerCorpseSnapshot snapshot = playerUuid != null ? PENDING_DEATHS.remove(playerUuid) : null;
            if (snapshot == null) {
                // 加载已有存档的尸体：从尸体自身重建快照
                snapshot = PlayerCorpseSnapshot.fromCorpse(entity);
            }
            if (snapshot == null) {
                return;
            }
            snapshot.attachTo(entity);
            CorpseVisualEntity visual = CorpseVisualEntity.createFromCorpse(entity, snapshot);
            if (serverLevel.addFreshEntity(visual)) {
                data.putUUID(VISUAL_UUID_KEY, visual.getUUID());
                data.putBoolean(VISUAL_CREATED_KEY, true);
            }
        } catch (RuntimeException | LinkageError e) {
            // 兼容逻辑出错时绝不阻断 Corpse 自身的死亡处理
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity entity = event.getEntity();
        RemovalReason reason = entity.getRemovalReason();
        if (reason == null || !reason.shouldDestroy()) {
            return;
        }
        CorpseVisualLink links = CorpseVisualLink.get(serverLevel);
        if (entity instanceof CorpseVisualEntity visual) {
            links.consumeRemoval(visual.getUUID());
            return;
        }
        if (!CorpseEntityAccess.isCorpse(entity)) {
            return;
        }
        CompoundTag data = entity.getPersistentData();
        UUID visualUuid = data.hasUUID(VISUAL_UUID_KEY) ? data.getUUID(VISUAL_UUID_KEY) : null;
        if (visualUuid != null) {
            links.scheduleRemoval(visualUuid);
        }
        // 移除当前已加载的所有关联视觉实体（按视觉 UUID 或源 UUID 匹配）
        List<CorpseVisualEntity> linked = new ArrayList<>();
        for (Entity candidate : serverLevel.getAllEntities()) {
            if (candidate instanceof CorpseVisualEntity visual) {
                boolean byVisualUuid = visualUuid != null && visualUuid.equals(visual.getUUID());
                boolean bySourceUuid = visual.sourceCorpseUuid()
                        .map(uuid -> uuid.equals(entity.getUUID()))
                        .orElse(false);
                if (byVisualUuid || bySourceUuid) {
                    linked.add(visual);
                }
            }
        }
        linked.forEach(CorpseVisualEntity::discard);
    }
}
