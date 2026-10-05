package io.github.createmeow.ysm_corpse.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.createmeow.ysm_corpse.CorpseEntityAccess;
import io.github.createmeow.ysm_corpse.CorpseVisualEntity;
import io.github.createmeow.ysm_corpse.PlayerCorpseSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 视觉 corpse 实体的渲染器：
 * 以快照构造隐形假人玩家，绑定 YSM 外观后，按 Corpse 配置把假人
 * 旋转成躺卧/趴伏姿态，再代理其玩家渲染器完成绘制。
 * 代理过程中强制剥除受击红色覆盖层。
 */
public final class VisualCorpseRenderer extends EntityRenderer<CorpseVisualEntity> {
    public VisualCorpseRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(CorpseVisualEntity entity) {
        return null;
    }

    @Override
    public void render(CorpseVisualEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        PlayerCorpseSnapshot snapshot = ClientVisualCache.snapshot(entity);
        DummyCorpsePlayer dummy = snapshot == null ? null : ClientVisualCache.dummy(entity, snapshot);
        if (dummy == null) {
            return;
        }
        poseStack.pushPose();
        try {
            // 遗体碰撞盒是长方形，长边沿玩家死亡时的朝向。
            // 模型需沿碰撞盒长边躺下：东西朝向时绕 Y 轴转 90° 对齐，
            // 南北朝向不转。Y 转角必须是 90 的整数倍（不能是任意 yaw），
            // 否则绕 X 轴 ±90° 后会侧躺。先 Y(0或90) 再 X(±90) 矩阵验证：
            // 模型前方仍朝 ±Y（仰躺/趴），不会侧躺。
            int yawStep = Math.round(entity.getYRot() / 90.0F);
            boolean eastWest = yawStep % 2 != 0;
            if (eastWest) {
                poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
            }
            if (CorpseEntityAccess.lieOnFace()) {
                poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
                poseStack.translate(0.0D, -1.0D, -0.125625D);
            } else {
                poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
                poseStack.translate(0.0D, -1.0D, 0.125625D);
            }
            // 绑定失败也照样走 dispatcher：YSM 的有效性检查不过会退回
            // 原版玩家渲染，保证遗体至少可见，不会凭空消失
            YsmModelBinder.bind(dummy, snapshot);
            dummy.setPose(net.minecraft.world.entity.Pose.STANDING);
            dummy.clearHurtState();
            // 必须经过 EntityRenderDispatcher#render：YSM 的接管 mixin 挂在
            // dispatcher 层，直接调 renderer.render 会绕过 YSM 渲染路径
            EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
            // 临时摘掉玩家渲染器的阴影半径，避免 dispatcher.render 末尾
            // 再叠一层原版圆形阴影（遗体脚下会出现一圈黑影）。
            // shadowRadius 在 EntityRenderer 中是 protected，用反射改写
            renderNoShadow(dispatcher, dummy, poseStack, buffers, packedLight);
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * 调用 dispatcher.render 期间临时屏蔽玩家渲染器的圆形阴影。
     * shadowRadius 在 EntityRenderer 中是 protected，反射改写；
     * 反射失败则退回普通渲染（仍有阴影但不会崩）。
     */
    private static void renderNoShadow(EntityRenderDispatcher dispatcher,
                                       DummyCorpsePlayer dummy, PoseStack poseStack,
                                       MultiBufferSource buffers, int packedLight) {
        EntityRenderer<?> dummyRenderer = dispatcher.getRenderer(dummy);
        java.lang.reflect.Field shadowField = null;
        float prevShadow = 0.0F;
        try {
            shadowField = EntityRenderer.class.getDeclaredField("shadowRadius");
            shadowField.setAccessible(true);
            prevShadow = shadowField.getFloat(dummyRenderer);
            shadowField.setFloat(dummyRenderer, 0.0F);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // 字段名变化等：跳过阴影屏蔽，照常渲染
        }
        try {
            dispatcher.render(dummy, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, poseStack,
                    new NoRedOverlayBufferSource(buffers), packedLight);
        } finally {
            if (shadowField != null) {
                try {
                    shadowField.setFloat(dummyRenderer, prevShadow);
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    // 恢复失败影响不大（仅阴影半径）
                }
            }
        }
    }

    /**
     * 包装缓冲源：把受击覆盖层的 v 坐标固定为 10（OverlayTexture 无覆盖值），
     * 防止 YSM/原版渲染在尸体上叠加红色闪烁。
     */
    private static final class NoRedOverlayBufferSource implements MultiBufferSource {
        private final MultiBufferSource parent;
        private final Map<RenderType, VertexConsumer> consumers = new IdentityHashMap<>();

        NoRedOverlayBufferSource(MultiBufferSource parent) {
            this.parent = parent;
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            return consumers.computeIfAbsent(renderType, type -> new NoRedOverlayConsumer(parent.getBuffer(type)));
        }
    }

    private static final class NoRedOverlayConsumer implements VertexConsumer {
        private final VertexConsumer parent;

        NoRedOverlayConsumer(VertexConsumer parent) {
            this.parent = parent;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            parent.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            parent.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            parent.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            // 覆盖层 v 强制为 10 = OverlayTexture.NO_OVERLAY
            parent.setUv1(u, 10);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            parent.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            parent.setNormal(x, y, z);
            return this;
        }
    }
}
