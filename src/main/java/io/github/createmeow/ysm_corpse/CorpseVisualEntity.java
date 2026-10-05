package io.github.createmeow.ysm_corpse;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * 每具 Corpse 尸体对应的"视觉 corpse"实体。
 * <p>
 * 服务端持有外观快照并跟随源尸体移动；客户端据其渲染 YSM 外观的
 * 躺卧玩家。源尸体被清理或销毁时自行消亡。
 */
public final class CorpseVisualEntity extends Entity {
    private static final EntityDataAccessor<CompoundTag> SNAPSHOT =
            SynchedEntityData.defineId(CorpseVisualEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Optional<UUID>> SOURCE_CORPSE_UUID =
            SynchedEntityData.defineId(CorpseVisualEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    private static final String SOURCE_CORPSE_UUID_TAG = "source_corpse_uuid";
    /** 源尸体 UUID 暂时缺失（跨区块加载等）时的容忍 tick 数。 */
    private static final int SOURCE_MISSING_GRACE_TICKS = 10;

    private int sourceMissingTicks;

    public CorpseVisualEntity(EntityType<? extends CorpseVisualEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        setNoGravity(true);
    }

    public static CorpseVisualEntity createFromCorpse(Entity source, PlayerCorpseSnapshot snapshot) {
        CorpseVisualEntity visual = new CorpseVisualEntity(YsmCorpseCompat.VISUAL_CORPSE.get(), source.level());
        visual.moveTo(source.getX(), source.getY(), source.getZ(), source.getYRot(), 0.0F);
        visual.setSourceCorpseUuid(source.getUUID());
        visual.setSnapshotTag(snapshot.toTag(source.level().registryAccess()));
        return visual;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(SNAPSHOT, new CompoundTag());
        builder.define(SOURCE_CORPSE_UUID, Optional.empty());
    }

    public void setSnapshotTag(CompoundTag tag) {
        this.entityData.set(SNAPSHOT, tag == null ? new CompoundTag() : tag.copy());
    }

    public CompoundTag snapshotTag() {
        return this.entityData.get(SNAPSHOT);
    }

    public void setSourceCorpseUuid(UUID uuid) {
        this.entityData.set(SOURCE_CORPSE_UUID, Optional.ofNullable(uuid));
        this.sourceMissingTicks = 0;
    }

    public Optional<UUID> sourceCorpseUuid() {
        return this.entityData.get(SOURCE_CORPSE_UUID);
    }

    @Override
    public void tick() {
        super.tick();
        setDeltaMovement(Vec3.ZERO);
        if (level() instanceof ServerLevel serverLevel) {
            if (CorpseVisualLink.get(serverLevel).shouldRemove(getUUID())) {
                discard();
                return;
            }
            UUID sourceUuid = sourceCorpseUuid().orElse(null);
            if (sourceUuid == null) {
                if (++sourceMissingTicks >= SOURCE_MISSING_GRACE_TICKS) {
                    discard();
                }
                return;
            }
            Entity source = serverLevel.getEntity(sourceUuid);
            if (source == null) {
                // 源尸体暂时不可见（区块未加载）：等待
                return;
            }
            if (!CorpseEntityAccess.isCorpse(source) || source.isRemoved()) {
                discard();
                return;
            }
            sourceMissingTicks = 0;
            moveTo(source.getX(), source.getY(), source.getZ(), source.getYRot(), 0.0F);
        }
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void move(MoverType type, Vec3 movement) {
        // 视觉实体固定跟随源尸体，不接受任何位移
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains(PlayerCorpseSnapshot.TAG, Tag.TAG_COMPOUND)) {
            setSnapshotTag(tag.getCompound(PlayerCorpseSnapshot.TAG));
        }
        if (tag.hasUUID(SOURCE_CORPSE_UUID_TAG)) {
            setSourceCorpseUuid(tag.getUUID(SOURCE_CORPSE_UUID_TAG));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put(PlayerCorpseSnapshot.TAG, snapshotTag().copy());
        sourceCorpseUuid().ifPresent(uuid -> tag.putUUID(SOURCE_CORPSE_UUID_TAG, uuid));
    }
}
