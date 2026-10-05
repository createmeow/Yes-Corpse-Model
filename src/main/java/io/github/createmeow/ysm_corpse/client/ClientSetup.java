package io.github.createmeow.ysm_corpse.client;

import io.github.createmeow.ysm_corpse.CorpseEntityAccess;
import io.github.createmeow.ysm_corpse.YsmCorpseCompat;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端注册：视觉实体渲染器 + Corpse 尸体渲染器覆盖。
 */
@EventBusSubscriber(modid = YsmCorpseCompat.MOD_ID, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // 视觉 corpse 实体渲染器
        event.registerEntityRenderer(YsmCorpseCompat.VISUAL_CORPSE.get(), VisualCorpseRenderer::new);
        // 覆盖 Corpse 尸体渲染器（依赖顺序在我们之后，注册会生效）
        overrideCorpseRenderer(type -> event.registerEntityRenderer(castEntityType(type), CorpseBodyRenderer::new));
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 双保险：若注册顺序未生效，在客户端初始化阶段再覆盖一次
        event.enqueueWork(() ->
                overrideCorpseRenderer(type -> EntityRenderers.register(castEntityType(type), CorpseBodyRenderer::new)));
    }

    @SuppressWarnings("unchecked")
    private static EntityType<Entity> castEntityType(EntityType<?> type) {
        return (EntityType<Entity>) type;
    }

    private static void overrideCorpseRenderer(java.util.function.Consumer<EntityType<?>> registrar) {
        if (!net.neoforged.fml.ModList.get().isLoaded(CorpseEntityAccess.MOD_ID)) {
            return;
        }
        CorpseEntityAccess.EntityTypeRef ref = CorpseEntityAccess.corpseEntityType();
        if (ref == null) {
            return;
        }
        registrar.accept(ref.type());
    }
}
