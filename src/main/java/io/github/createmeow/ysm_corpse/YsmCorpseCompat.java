package io.github.createmeow.ysm_corpse;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * YSM 与 Corpse 模组的兼容层。
 * <p>
 * 玩家死亡后，Corpse 模组会生成尸体实体。本模组在每个尸体旁挂载一个
 * 同步用的"视觉 corpse"实体，携带玩家死亡瞬间的外观快照（装备、皮肤
 * 显示设置、YSM 模型与贴图）。客户端渲染时用该快照构造一个隐形假人
 * 玩家，绑定 YSM 外观后代理渲染成躺卧姿态。
 */
@Mod(YsmCorpseCompat.MOD_ID)
public final class YsmCorpseCompat {
    public static final String MOD_ID = "yes_corpse_model";

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);

    /** 视觉实体：无碰撞、不可交互，仅负责同步快照并跟随源尸体。 */
    public static final Supplier<EntityType<CorpseVisualEntity>> VISUAL_CORPSE =
            ENTITY_TYPES.register(MOD_ID, () -> EntityType.Builder
                    .of(CorpseVisualEntity::new, MobCategory.MISC)
                    .sized(0.8F, 0.35F)
                    .clientTrackingRange(64)
                    .updateInterval(1)
                    .fireImmune()
                    .build(MOD_ID));

    public YsmCorpseCompat(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        NeoForge.EVENT_BUS.register(GameEventHandlers.class);
    }
}
