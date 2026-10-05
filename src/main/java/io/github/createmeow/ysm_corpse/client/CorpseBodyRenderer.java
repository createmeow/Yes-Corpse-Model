package io.github.createmeow.ysm_corpse.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.createmeow.ysm_corpse.CorpseEntityAccess;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Corpse 尸体实体的渲染器覆盖：
 * <ul>
 *   <li>骷髅形态或 YSM 缺席：回退为 Corpse 原渲染器；</li>
 *   <li>其他情况：自身不绘制任何内容（由视觉 corpse 实体负责 YSM 外观渲染）。</li>
 * </ul>
 * 通过反射构造 Corpse 原渲染器，保持零编译依赖。
 */
public final class CorpseBodyRenderer extends EntityRenderer<Entity> {
    private final EntityRenderer<Entity> fallback;

    public CorpseBodyRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.fallback = createFallback(context);
    }

    private static EntityRenderer<Entity> createFallback(EntityRendererProvider.Context context) {
        try {
            Class<?> rendererClass = Class.forName(
                    "de.maxhenkel.corpse.entities.CorpseRenderer", false, CorpseBodyRenderer.class.getClassLoader());
            Object renderer = rendererClass
                    .getConstructor(EntityRendererProvider.Context.class)
                    .newInstance(context);
            @SuppressWarnings("unchecked")
            EntityRenderer<Entity> cast = (EntityRenderer<Entity>) renderer;
            return cast;
        } catch (LinkageError | ReflectiveOperationException e) {
            return null;
        }
    }

    @Override
    public ResourceLocation getTextureLocation(Entity entity) {
        return fallback != null ? fallback.getTextureLocation(entity) : null;
    }

    @Override
    public void render(Entity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        boolean showVanillaBody = !CorpseEntityAccess.isCorpse(entity)
                || CorpseEntityAccess.isSkeleton(entity)
                || !ClientCompat.isYsmPresent();
        if (showVanillaBody && fallback != null) {
            fallback.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
        }
    }
}
